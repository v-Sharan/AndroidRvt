#include <jni.h>
#include <stdatomic.h>
#include <stdint.h>
#include <string.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <gst/gst.h>
#include <gst/video/videooverlay.h>

#define LOG_TAG "RvtVideo"
#define LOGE(...) __android_log_print (ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define TICK_MS 250
#define TICKS_PER_STATS 4
#define REC_FINALIZE_TIMEOUT_MS 3000
#define TEST_PATTERN_URL "test"

/* Values shared with VideoEngine.Phase in Kotlin. */
typedef enum
{
  ST_STOPPED = 0,
  ST_CONNECTING = 1,
  ST_PLAYING = 2,
  ST_RECONNECTING = 3
} VideoState;

/* From decodebin's GstAutoplugSelectResult, which is not in a public header. */
enum
{
  AUTOPLUG_TRY = 0,
  AUTOPLUG_SKIP = 2
};

typedef struct
{
  gchar *url;
  gchar *transport;
  gint latency_ms;
  gint frame_timeout_ms;
  gint connect_timeout_ms;
  gboolean hardware_decoder;
} Config;

typedef struct
{
  GstElement *queue;
  GstElement *mux;
  GstElement *sink;
  GstPad *tee_pad;
  gchar *path;
  gboolean finalizing;
  GSource *guard;
} Recording;

/*
 * Pipeline state is owned by the engine thread; JNI calls hop onto it with
 * run_sync(). Only the atomics are touched from GStreamer streaming threads.
 */
typedef struct
{
  GMainContext *context;
  GMainLoop *loop;
  GThread *thread;
  JNIEnv *thread_env;
  jobject listener;

  Config cfg;
  gboolean want_playing;
  ANativeWindow *window;

  GstElement *pipeline;
  GstElement *overlay;
  GstElement *tee;
  GstPad *sink_pad;
  GSource *bus_source;

  VideoState state;
  gint attempt;
  gboolean awaiting_first_frame;
  gint64 session_start_us;
  GSource *retry_source;
  gchar *decoder_name;

  GMutex teardown_lock;
  GCond teardown_cond;
  gint teardowns;

  atomic_llong last_frame_us;
  atomic_uint frames;
  atomic_ullong bytes;
  guint tick_count;
  gint64 last_stats_us;

  gboolean rec_requested;
  gchar *rec_dir;
  Recording *rec;
} Engine;

typedef void (*EngineFunc) (Engine * engine, gpointer arg);

typedef struct
{
  Engine *engine;
  EngineFunc func;
  gpointer arg;
  GMutex mutex;
  GCond cond;
  gboolean done;
} SyncCall;

typedef struct
{
  Engine *engine;
  GstElement *pipeline;
  GMainContext *context;
} TeardownJob;

static JavaVM *java_vm;
static jmethodID on_state_method;
static jmethodID on_stats_method;
static jmethodID on_log_method;
static jmethodID on_recording_done_method;
static jmethodID on_recording_error_method;

static void try_start (Engine * e);
static gboolean on_teardown_done (gpointer data);

JNIEXPORT jint JNICALL
JNI_OnLoad (JavaVM * vm, void *reserved)
{
  java_vm = vm;
  return JNI_VERSION_1_6;
}

static jstring
to_jstring (JNIEnv * env, const gchar * text)
{
  gchar *valid = g_utf8_make_valid (text ? text : "", -1);
  jstring result = (*env)->NewStringUTF (env, valid);

  g_free (valid);
  return result;
}

static void
clear_java_exception (JNIEnv * env)
{
  if ((*env)->ExceptionCheck (env)) {
    LOGE ("Kotlin callback threw an exception");
    (*env)->ExceptionClear (env);
  }
}

static void
call_with_text (Engine * e, jmethodID method, const gchar * text)
{
  JNIEnv *env = e->thread_env;
  jstring jtext = to_jstring (env, text);

  (*env)->CallVoidMethod (env, e->listener, method, jtext);
  clear_java_exception (env);
  (*env)->DeleteLocalRef (env, jtext);
}

static void
log_line (Engine * e, const gchar * text)
{
  call_with_text (e, on_log_method, text);
}

static void
publish_state (Engine * e, VideoState state, const gchar * detail)
{
  JNIEnv *env = e->thread_env;
  jstring jdetail = to_jstring (env, detail);

  e->state = state;
  (*env)->CallVoidMethod (env, e->listener, on_state_method, (jint) state,
      (jint) e->attempt, jdetail);
  clear_java_exception (env);
  (*env)->DeleteLocalRef (env, jdetail);
}

static void
publish_stats (Engine * e)
{
  JNIEnv *env = e->thread_env;
  gint64 now = g_get_monotonic_time ();
  gdouble seconds = MAX (now - e->last_stats_us, 1) / 1000000.0;
  guint frames = atomic_exchange (&e->frames, 0);
  guint64 bytes = atomic_exchange (&e->bytes, 0);
  gint width = 0, height = 0;
  jstring decoder;

  e->last_stats_us = now;

  if (e->sink_pad) {
    GstCaps *caps = gst_pad_get_current_caps (e->sink_pad);

    if (caps) {
      const GstStructure *s = gst_caps_get_structure (caps, 0);

      gst_structure_get_int (s, "width", &width);
      gst_structure_get_int (s, "height", &height);
      gst_caps_unref (caps);
    }
  }

  decoder = to_jstring (env, e->decoder_name);
  (*env)->CallVoidMethod (env, e->listener, on_stats_method,
      (jdouble) (frames / seconds), (jdouble) (bytes * 8.0 / 1000.0 / seconds),
      (jint) width, (jint) height, decoder);
  clear_java_exception (env);
  (*env)->DeleteLocalRef (env, decoder);
}

static gboolean
is_test_pattern (Engine * e)
{
  return g_strcmp0 (e->cfg.url, TEST_PATTERN_URL) == 0;
}

static void
rec_cancel_guard (Recording * r)
{
  if (r->guard) {
    g_source_destroy (r->guard);
    g_source_unref (r->guard);
    r->guard = NULL;
  }
}

/* Forget a branch whose elements are going away with the whole pipeline. The
 * MP4 is fragmented, so the file stays playable without a clean finish. */
static void
rec_drop (Engine * e)
{
  Recording *r = e->rec;

  if (!r)
    return;
  e->rec = NULL;
  rec_cancel_guard (r);
  gst_object_unref (r->tee_pad);
  call_with_text (e, on_recording_done_method, r->path);
  g_free (r->path);
  g_free (r);
}

static void
rec_remove_branch (Engine * e)
{
  Recording *r = e->rec;
  GstElement *elements[3];
  guint i;

  if (!r)
    return;
  e->rec = NULL;
  rec_cancel_guard (r);

  elements[0] = r->queue;
  elements[1] = r->mux;
  elements[2] = r->sink;
  for (i = 0; i < G_N_ELEMENTS (elements); i++) {
    gst_element_set_state (elements[i], GST_STATE_NULL);
    gst_bin_remove (GST_BIN (e->pipeline), elements[i]);
  }
  gst_element_release_request_pad (e->tee, r->tee_pad);
  gst_object_unref (r->tee_pad);

  call_with_text (e, on_recording_done_method, r->path);
  g_free (r->path);
  g_free (r);
}

static gboolean
on_rec_eos (gpointer data)
{
  Engine *e = data;

  if (e->rec && e->rec->finalizing)
    rec_remove_branch (e);
  return G_SOURCE_REMOVE;
}

static GstPadProbeReturn
rec_sink_event_probe (GstPad * pad, GstPadProbeInfo * info, gpointer data)
{
  Engine *e = data;

  if (GST_EVENT_TYPE (GST_PAD_PROBE_INFO_EVENT (info)) == GST_EVENT_EOS)
    g_main_context_invoke (e->context, on_rec_eos, e);
  return GST_PAD_PROBE_OK;
}

static GstPadProbeReturn
rec_keyframe_gate (GstPad * pad, GstPadProbeInfo * info, gpointer data)
{
  GstBuffer *buffer = GST_PAD_PROBE_INFO_BUFFER (info);

  if (GST_BUFFER_FLAG_IS_SET (buffer, GST_BUFFER_FLAG_DELTA_UNIT))
    return GST_PAD_PROBE_DROP;
  return GST_PAD_PROBE_REMOVE;
}

static GstPadProbeReturn
rec_unlink_when_idle (GstPad * pad, GstPadProbeInfo * info, gpointer data)
{
  GstPad *peer = gst_pad_get_peer (pad);

  if (peer) {
    gst_pad_unlink (pad, peer);
    gst_pad_send_event (peer, gst_event_new_eos ());
    gst_object_unref (peer);
  }
  return GST_PAD_PROBE_REMOVE;
}

static gboolean
on_rec_guard_timeout (gpointer data)
{
  Engine *e = data;

  if (e->rec) {
    /* The source is removed by returning; don't let the cleanup destroy it. */
    g_source_unref (e->rec->guard);
    e->rec->guard = NULL;
    rec_remove_branch (e);
  }
  return G_SOURCE_REMOVE;
}

static void
rec_stop (Engine * e)
{
  Recording *r = e->rec;

  if (!r || r->finalizing)
    return;
  r->finalizing = TRUE;
  r->guard = g_timeout_source_new (REC_FINALIZE_TIMEOUT_MS);
  g_source_set_callback (r->guard, on_rec_guard_timeout, e, NULL);
  g_source_attach (r->guard, e->context);
  gst_pad_add_probe (r->tee_pad, GST_PAD_PROBE_TYPE_IDLE, rec_unlink_when_idle,
      NULL, NULL);
}

static void
rec_start (Engine * e)
{
  Recording *r;
  GstElement *queue, *mux, *sink;
  GstPad *queue_pad, *sink_pad;
  GDateTime *now;
  gchar *stamp;

  if (e->rec || !e->rec_requested || !e->tee || e->state != ST_PLAYING)
    return;

  queue = gst_element_factory_make ("queue", NULL);
  mux = gst_element_factory_make ("mp4mux", NULL);
  sink = gst_element_factory_make ("filesink", NULL);
  if (!queue || !mux || !sink) {
    call_with_text (e, on_recording_error_method,
        "Recording elements are missing");
    gst_clear_object (&queue);
    gst_clear_object (&mux);
    gst_clear_object (&sink);
    e->rec_requested = FALSE;
    return;
  }

  r = g_new0 (Recording, 1);
  r->queue = queue;
  r->mux = mux;
  r->sink = sink;

  now = g_date_time_new_now_local ();
  stamp = g_date_time_format (now, "%Y%m%d_%H%M%S");
  r->path = g_strdup_printf ("%s/RVT_%s.mp4", e->rec_dir, stamp);
  g_free (stamp);
  g_date_time_unref (now);

  /* Leaky so a slow disk can never stall the live picture. */
  g_object_set (queue, "max-size-buffers", 0, "max-size-bytes", 0,
      "max-size-time", (guint64) (4 * GST_SECOND), NULL);
  gst_util_set_object_arg (G_OBJECT (queue), "leaky", "downstream");
  g_object_set (mux, "fragment-duration", 1000, NULL);
  g_object_set (sink, "location", r->path, "async", FALSE, NULL);

  gst_bin_add_many (GST_BIN (e->pipeline), queue, mux, sink, NULL);
  gst_element_link_many (queue, mux, sink, NULL);

  sink_pad = gst_element_get_static_pad (sink, "sink");
  gst_pad_add_probe (sink_pad, GST_PAD_PROBE_TYPE_EVENT_DOWNSTREAM,
      rec_sink_event_probe, e, NULL);
  gst_object_unref (sink_pad);

  gst_element_sync_state_with_parent (sink);
  gst_element_sync_state_with_parent (mux);
  gst_element_sync_state_with_parent (queue);

  /* A recording has to begin on a keyframe to be decodable. */
  queue_pad = gst_element_get_static_pad (queue, "sink");
  gst_pad_add_probe (queue_pad, GST_PAD_PROBE_TYPE_BUFFER, rec_keyframe_gate,
      NULL, NULL);
  r->tee_pad = gst_element_request_pad_simple (e->tee, "src_%u");
  gst_pad_link (r->tee_pad, queue_pad);
  gst_object_unref (queue_pad);

  e->rec = r;
  log_line (e, "Recording segment started");
}

static gpointer
teardown_worker (gpointer data)
{
  TeardownJob *job = data;
  Engine *e = job->engine;

  gst_element_set_state (job->pipeline, GST_STATE_NULL);
  gst_object_unref (job->pipeline);

  g_mutex_lock (&e->teardown_lock);
  e->teardowns--;
  g_cond_broadcast (&e->teardown_cond);
  g_mutex_unlock (&e->teardown_lock);

  /* The context reference keeps this safe if the engine is shutting down. */
  g_main_context_invoke (job->context, on_teardown_done, e);
  g_main_context_unref (job->context);
  g_free (job);
  return NULL;
}

static gboolean
on_teardown_done (gpointer data)
{
  try_start ((Engine *) data);
  return G_SOURCE_REMOVE;
}

/* Stopping rtspsrc can block on a dead network, so it never runs here. */
static void
detach_pipeline (Engine * e)
{
  TeardownJob *job;

  if (e->bus_source) {
    g_source_destroy (e->bus_source);
    g_source_unref (e->bus_source);
    e->bus_source = NULL;
  }
  if (!e->pipeline)
    return;

  rec_drop (e);
  gst_clear_object (&e->sink_pad);
  gst_clear_object (&e->overlay);
  gst_clear_object (&e->tee);
  e->awaiting_first_frame = FALSE;
  g_clear_pointer (&e->decoder_name, g_free);

  g_mutex_lock (&e->teardown_lock);
  e->teardowns++;
  g_mutex_unlock (&e->teardown_lock);

  job = g_new0 (TeardownJob, 1);
  job->engine = e;
  job->pipeline = e->pipeline;
  job->context = g_main_context_ref (e->context);
  e->pipeline = NULL;
  g_thread_unref (g_thread_new ("rvt-teardown", teardown_worker, job));
}

static void
wait_for_teardowns (Engine * e)
{
  g_mutex_lock (&e->teardown_lock);
  while (e->teardowns > 0)
    g_cond_wait (&e->teardown_cond, &e->teardown_lock);
  g_mutex_unlock (&e->teardown_lock);
}

static void
cancel_retry (Engine * e)
{
  if (e->retry_source) {
    g_source_destroy (e->retry_source);
    g_source_unref (e->retry_source);
    e->retry_source = NULL;
  }
}

static gboolean
on_retry_due (gpointer data)
{
  Engine *e = data;

  g_source_unref (e->retry_source);
  e->retry_source = NULL;
  try_start (e);
  return G_SOURCE_REMOVE;
}

static void
begin_reconnect (Engine * e, const gchar * reason)
{
  static const guint delays_ms[] = { 0, 1000, 2000, 4000, 5000 };
  guint index;

  if (!e->pipeline)
    return;

  if (e->state == ST_PLAYING)
    e->attempt = 0;
  index = MIN ((guint) e->attempt, G_N_ELEMENTS (delays_ms) - 1);
  e->attempt++;

  log_line (e, reason);
  publish_state (e, ST_RECONNECTING, reason);
  detach_pipeline (e);

  cancel_retry (e);
  e->retry_source = g_timeout_source_new (delays_ms[index]);
  g_source_set_callback (e->retry_source, on_retry_due, e, NULL);
  g_source_attach (e->retry_source, e->context);
}

static GstPadProbeReturn
on_frame_probe (GstPad * pad, GstPadProbeInfo * info, gpointer data)
{
  Engine *e = data;

  atomic_store (&e->last_frame_us, g_get_monotonic_time ());
  atomic_fetch_add (&e->frames, 1);
  return GST_PAD_PROBE_OK;
}

static GstPadProbeReturn
on_encoded_probe (GstPad * pad, GstPadProbeInfo * info, gpointer data)
{
  Engine *e = data;

  atomic_fetch_add (&e->bytes, gst_buffer_get_size (GST_PAD_PROBE_INFO_BUFFER
          (info)));
  return GST_PAD_PROBE_OK;
}

static gint
on_autoplug_select (GstElement * bin, GstPad * pad, GstCaps * caps,
    GstElementFactory * factory, gpointer data)
{
  gboolean hardware_allowed = GPOINTER_TO_INT (data);

  if (!hardware_allowed
      && g_str_has_prefix (GST_OBJECT_NAME (factory), "amcviddec"))
    return AUTOPLUG_SKIP;
  return AUTOPLUG_TRY;
}

static void
post_application_text (GstElement * src, const gchar * text)
{
  gst_element_post_message (src, gst_message_new_application (GST_OBJECT (src),
          gst_structure_new ("rvt-log", "text", G_TYPE_STRING, text, NULL)));
}

/* Runs on a streaming thread; the parent lookup avoids touching Engine. */
static void
on_rtsp_pad_added (GstElement * src, GstPad * pad, gpointer data)
{
  GstObject *parent = gst_object_get_parent (GST_OBJECT (src));
  GstBin *bin = GST_BIN (parent);
  GstCaps *caps = gst_pad_get_current_caps (pad);
  GstElement *tee = NULL, *depay = NULL, *parse = NULL, *filter = NULL;
  GstCaps *depay_caps = NULL, *output_caps = NULL;
  GstPad *tee_sink = NULL, *depay_sink = NULL;
  const GstStructure *s;
  const gchar *media, *encoding;

  if (!caps)
    caps = gst_pad_query_caps (pad, NULL);
  s = gst_caps_get_structure (caps, 0);
  media = gst_structure_get_string (s, "media");
  encoding = gst_structure_get_string (s, "encoding-name");

  if (g_strcmp0 (media, "video") != 0)
    goto done;

  tee = gst_bin_get_by_name (bin, "t");
  tee_sink = gst_element_get_static_pad (tee, "sink");
  if (gst_pad_is_linked (tee_sink))
    goto done;

  if (g_strcmp0 (encoding, "H264") == 0) {
    depay = gst_element_factory_make ("rtph264depay", NULL);
    parse = gst_element_factory_make ("h264parse", NULL);
    depay_caps = gst_caps_from_string ("video/x-h264,alignment=au");
    output_caps = gst_caps_from_string
        ("video/x-h264,stream-format=avc,alignment=au");
  } else if (g_strcmp0 (encoding, "H265") == 0) {
    depay = gst_element_factory_make ("rtph265depay", NULL);
    parse = gst_element_factory_make ("h265parse", NULL);
    depay_caps = gst_caps_from_string ("video/x-h265,alignment=au");
    output_caps = gst_caps_from_string
        ("video/x-h265,stream-format=hvc1,alignment=au");
  } else {
    gchar *text = g_strdup_printf ("Unsupported video codec: %s",
        encoding ? encoding : "unknown");

    post_application_text (src, text);
    g_free (text);
    goto done;
  }

  filter = gst_element_factory_make ("capsfilter", NULL);
  g_object_set (filter, "caps", output_caps, NULL);

  gst_bin_add_many (bin, depay, parse, filter, NULL);
  if (!gst_element_link_filtered (depay, parse, depay_caps)
      || !gst_element_link_many (parse, filter, tee, NULL)) {
    post_application_text (src, "Could not link the video parser");
    goto done;
  }
  gst_element_sync_state_with_parent (filter);
  gst_element_sync_state_with_parent (parse);
  gst_element_sync_state_with_parent (depay);

  depay_sink = gst_element_get_static_pad (depay, "sink");
  if (GST_PAD_LINK_FAILED (gst_pad_link (pad, depay_sink)))
    post_application_text (src, "Could not link the RTSP stream");

done:
  if (depay_sink)
    gst_object_unref (depay_sink);
  if (tee_sink)
    gst_object_unref (tee_sink);
  if (tee)
    gst_object_unref (tee);
  if (depay_caps)
    gst_caps_unref (depay_caps);
  if (output_caps)
    gst_caps_unref (output_caps);
  gst_caps_unref (caps);
  gst_object_unref (parent);
}

static gboolean
on_bus_message (GstBus * bus, GstMessage * message, gpointer data)
{
  Engine *e = data;
  GstObject *src = GST_MESSAGE_SRC (message);

  switch (GST_MESSAGE_TYPE (message)) {
    case GST_MESSAGE_ERROR:{
      GError *error = NULL;
      gchar *text;

      gst_message_parse_error (message, &error, NULL);
      text = g_strdup_printf ("%s: %s", GST_OBJECT_NAME (src), error->message);

      if (e->rec && (src == GST_OBJECT (e->rec->queue)
              || src == GST_OBJECT (e->rec->mux)
              || src == GST_OBJECT (e->rec->sink))) {
        /* A full disk must not interrupt the live video. */
        e->rec_requested = FALSE;
        call_with_text (e, on_recording_error_method, text);
        rec_remove_branch (e);
      } else {
        begin_reconnect (e, text);
      }
      g_free (text);
      g_clear_error (&error);
      break;
    }
    case GST_MESSAGE_WARNING:{
      GError *error = NULL;

      gst_message_parse_warning (message, &error, NULL);
      log_line (e, error->message);
      g_clear_error (&error);
      break;
    }
    case GST_MESSAGE_EOS:
      begin_reconnect (e, "Stream ended");
      break;
    case GST_MESSAGE_APPLICATION:{
      const GstStructure *s = gst_message_get_structure (message);

      if (gst_structure_has_name (s, "rvt-log"))
        log_line (e, gst_structure_get_string (s, "text"));
      break;
    }
    default:
      break;
  }
  return G_SOURCE_CONTINUE;
}

static GstElement *
build_pipeline (Engine * e, GError ** error)
{
  GstElement *pipeline, *src, *dec, *sink;
  GstPad *tee_sink;

  if (is_test_pattern (e)) {
    pipeline = gst_parse_launch ("videotestsrc is-live=true pattern=smpte "
        "horizontal-speed=4 ! video/x-raw,width=1280,height=720,framerate=30/1 "
        "! glimagesink name=sink sync=false", error);
    if (*error)
      return pipeline;
  } else {
    pipeline = gst_parse_launch ("rtspsrc name=src "
        "tee name=t ! queue max-size-buffers=120 max-size-bytes=0 "
        "max-size-time=0 ! decodebin name=dec "
        "! glimagesink name=sink sync=false", error);
    if (*error)
      return pipeline;

    src = gst_bin_get_by_name (GST_BIN (pipeline), "src");
    g_object_set (src, "location", e->cfg.url,
        "latency", (guint) e->cfg.latency_ms,
        "drop-on-latency", TRUE,
        "tcp-timeout", (guint64) e->cfg.connect_timeout_ms * 1000, NULL);
    gst_util_set_object_arg (G_OBJECT (src), "protocols", e->cfg.transport);
    g_signal_connect (src, "pad-added", G_CALLBACK (on_rtsp_pad_added), NULL);
    gst_object_unref (src);

    dec = gst_bin_get_by_name (GST_BIN (pipeline), "dec");
    g_signal_connect (dec, "autoplug-select", G_CALLBACK (on_autoplug_select),
        GINT_TO_POINTER (e->cfg.hardware_decoder));
    gst_object_unref (dec);

    e->tee = gst_bin_get_by_name (GST_BIN (pipeline), "t");
    tee_sink = gst_element_get_static_pad (e->tee, "sink");
    gst_pad_add_probe (tee_sink, GST_PAD_PROBE_TYPE_BUFFER, on_encoded_probe,
        e, NULL);
    gst_object_unref (tee_sink);
  }

  /* Counting after the decoder means a stalled decoder also triggers a reconnect. */
  sink = gst_bin_get_by_name (GST_BIN (pipeline), "sink");
  e->sink_pad = gst_element_get_static_pad (sink, "sink");
  gst_pad_add_probe (e->sink_pad,
      GST_PAD_PROBE_TYPE_BUFFER | GST_PAD_PROBE_TYPE_BUFFER_LIST,
      on_frame_probe, e, NULL);
  gst_object_unref (sink);
  return pipeline;
}

static void
try_start (Engine * e)
{
  GError *error = NULL;
  GstElement *pipeline;
  GstBus *bus;
  guint pending;

  g_mutex_lock (&e->teardown_lock);
  pending = e->teardowns;
  g_mutex_unlock (&e->teardown_lock);

  if (!e->want_playing || !e->window || !e->cfg.url || e->pipeline
      || e->retry_source || pending > 0)
    return;

  atomic_store (&e->last_frame_us, 0);
  atomic_store (&e->frames, 0);
  atomic_store (&e->bytes, 0);

  pipeline = build_pipeline (e, &error);
  if (error) {
    publish_state (e, ST_STOPPED, error->message);
    log_line (e, error->message);
    g_clear_error (&error);
    gst_clear_object (&e->tee);
    gst_clear_object (&pipeline);
    return;
  }

  gst_element_set_state (pipeline, GST_STATE_READY);
  e->overlay = gst_bin_get_by_interface (GST_BIN (pipeline),
      GST_TYPE_VIDEO_OVERLAY);
  if (!e->overlay) {
    publish_state (e, ST_STOPPED, "Pipeline has no video output");
    gst_element_set_state (pipeline, GST_STATE_NULL);
    gst_object_unref (pipeline);
    gst_clear_object (&e->tee);
    gst_clear_object (&e->sink_pad);
    return;
  }
  e->pipeline = pipeline;
  gst_video_overlay_set_window_handle (GST_VIDEO_OVERLAY (e->overlay),
      (guintptr) e->window);

  bus = gst_element_get_bus (pipeline);
  e->bus_source = gst_bus_create_watch (bus);
  g_source_set_callback (e->bus_source, (GSourceFunc) on_bus_message, e, NULL);
  g_source_attach (e->bus_source, e->context);
  gst_object_unref (bus);

  e->session_start_us = g_get_monotonic_time ();
  e->awaiting_first_frame = TRUE;

  if (e->attempt > 0) {
    gchar *text = g_strdup_printf ("Attempt %d", e->attempt);

    publish_state (e, ST_RECONNECTING, text);
    g_free (text);
  } else {
    publish_state (e, ST_CONNECTING, "");
  }

  gst_element_set_state (pipeline, GST_STATE_PLAYING);
}

static void
update_decoder_name (Engine * e)
{
  GstIterator *it;
  GValue item = G_VALUE_INIT;
  gboolean done = FALSE;

  g_clear_pointer (&e->decoder_name, g_free);
  if (is_test_pattern (e)) {
    e->decoder_name = g_strdup ("test pattern");
    return;
  }

  it = gst_bin_iterate_recurse (GST_BIN (e->pipeline));
  while (!done) {
    switch (gst_iterator_next (it, &item)) {
      case GST_ITERATOR_OK:{
        GstElement *element = g_value_get_object (&item);
        const gchar *klass = gst_element_get_metadata (element,
            GST_ELEMENT_METADATA_KLASS);

        if (klass && strstr (klass, "Decoder") && strstr (klass, "Video")) {
          GstElementFactory *factory = gst_element_get_factory (element);

          e->decoder_name = g_strdup (factory ? GST_OBJECT_NAME (factory)
              : GST_OBJECT_NAME (element));
          done = TRUE;
        }
        g_value_reset (&item);
        break;
      }
      case GST_ITERATOR_RESYNC:
        gst_iterator_resync (it);
        break;
      default:
        done = TRUE;
        break;
    }
  }
  g_value_unset (&item);
  gst_iterator_free (it);
}

static void
on_first_frame (Engine * e)
{
  gchar *text;

  e->awaiting_first_frame = FALSE;
  e->attempt = 0;
  update_decoder_name (e);

  text = g_strdup_printf ("Video started (%s)",
      e->decoder_name ? e->decoder_name : "unknown decoder");
  log_line (e, text);
  g_free (text);

  publish_state (e, ST_PLAYING, "");
  rec_start (e);
}

static gboolean
on_tick (gpointer data)
{
  Engine *e = data;

  if (e->pipeline) {
    gint64 now = g_get_monotonic_time ();
    gint64 last = atomic_load (&e->last_frame_us);

    if (e->awaiting_first_frame) {
      if (last > 0) {
        on_first_frame (e);
      } else if ((now - e->session_start_us) / 1000 > e->cfg.connect_timeout_ms) {
        gchar *text = g_strdup_printf ("No video within %d ms",
            e->cfg.connect_timeout_ms);

        begin_reconnect (e, text);
        g_free (text);
      }
    } else if (e->state == ST_PLAYING
        && (now - last) / 1000 > e->cfg.frame_timeout_ms) {
      gchar *text = g_strdup_printf ("No frames for %d ms",
          e->cfg.frame_timeout_ms);

      begin_reconnect (e, text);
      g_free (text);
    }
  }

  if (++e->tick_count % TICKS_PER_STATS == 0)
    publish_stats (e);
  return G_SOURCE_CONTINUE;
}

static void
config_clear (Config * cfg)
{
  g_clear_pointer (&cfg->url, g_free);
  g_clear_pointer (&cfg->transport, g_free);
}

static void
restart_session (Engine * e)
{
  cancel_retry (e);
  detach_pipeline (e);
  e->attempt = 0;
  try_start (e);
}

static void
do_configure (Engine * e, gpointer arg)
{
  Config *next = arg;

  config_clear (&e->cfg);
  e->cfg = *next;
  g_free (next);
  restart_session (e);
}

static void
do_set_window (Engine * e, gpointer arg)
{
  ANativeWindow *window = arg;

  if (window && window == e->window) {
    /* Same surface, only its size changed: drop the extra reference and redraw. */
    ANativeWindow_release (window);
    if (e->overlay) {
      gst_video_overlay_expose (GST_VIDEO_OVERLAY (e->overlay));
      gst_video_overlay_expose (GST_VIDEO_OVERLAY (e->overlay));
    }
    return;
  }

  /* The sink must have let go of the old surface before it is released. */
  detach_pipeline (e);
  wait_for_teardowns (e);
  if (e->window)
    ANativeWindow_release (e->window);
  e->window = window;
  try_start (e);
}

static void
do_play (Engine * e, gpointer arg)
{
  e->want_playing = TRUE;
  e->attempt = 0;
  try_start (e);
}

static void
do_stop (Engine * e, gpointer arg)
{
  e->want_playing = FALSE;
  e->rec_requested = FALSE;
  cancel_retry (e);
  detach_pipeline (e);
  e->attempt = 0;
  publish_state (e, ST_STOPPED, "");
}

static void
do_reconnect_now (Engine * e, gpointer arg)
{
  if (e->want_playing && e->state != ST_PLAYING)
    restart_session (e);
}

static void
do_start_recording (Engine * e, gpointer arg)
{
  g_free (e->rec_dir);
  e->rec_dir = arg;

  if (is_test_pattern (e)) {
    call_with_text (e, on_recording_error_method,
        "Recording needs a live RTSP stream");
    return;
  }
  e->rec_requested = TRUE;
  rec_start (e);
}

static void
do_stop_recording (Engine * e, gpointer arg)
{
  e->rec_requested = FALSE;
  rec_stop (e);
}

static void
do_shutdown (Engine * e, gpointer arg)
{
  e->want_playing = FALSE;
  e->rec_requested = FALSE;
  cancel_retry (e);
  detach_pipeline (e);
  wait_for_teardowns (e);
  if (e->window) {
    ANativeWindow_release (e->window);
    e->window = NULL;
  }
  g_main_loop_quit (e->loop);
}

static gboolean
sync_call_trampoline (gpointer data)
{
  SyncCall *call = data;

  call->func (call->engine, call->arg);

  g_mutex_lock (&call->mutex);
  call->done = TRUE;
  g_cond_signal (&call->cond);
  g_mutex_unlock (&call->mutex);
  return G_SOURCE_REMOVE;
}

static void
run_sync (Engine * e, EngineFunc func, gpointer arg)
{
  SyncCall call = {.engine = e,.func = func,.arg = arg };

  g_mutex_init (&call.mutex);
  g_cond_init (&call.cond);
  g_main_context_invoke (e->context, sync_call_trampoline, &call);

  g_mutex_lock (&call.mutex);
  while (!call.done)
    g_cond_wait (&call.cond, &call.mutex);
  g_mutex_unlock (&call.mutex);

  g_cond_clear (&call.cond);
  g_mutex_clear (&call.mutex);
}

static gpointer
engine_thread (gpointer data)
{
  Engine *e = data;
  GSource *tick;

  (*java_vm)->AttachCurrentThread (java_vm, &e->thread_env, NULL);
  g_main_context_push_thread_default (e->context);

  e->last_stats_us = g_get_monotonic_time ();
  tick = g_timeout_source_new (TICK_MS);
  g_source_set_callback (tick, on_tick, e, NULL);
  g_source_attach (tick, e->context);
  g_source_unref (tick);

  g_main_loop_run (e->loop);

  g_main_context_pop_thread_default (e->context);
  (*java_vm)->DetachCurrentThread (java_vm);
  return NULL;
}

static Engine *
engine_from_handle (jlong handle)
{
  return (Engine *) (intptr_t) handle;
}

static gchar *
dup_jstring (JNIEnv * env, jstring text)
{
  const char *chars = (*env)->GetStringUTFChars (env, text, NULL);
  gchar *copy = g_strdup (chars);

  (*env)->ReleaseStringUTFChars (env, text, chars);
  return copy;
}

static gboolean
cache_callbacks (JNIEnv * env, jobject thiz)
{
  jclass klass;

  if (on_state_method)
    return TRUE;

  klass = (*env)->GetObjectClass (env, thiz);
  on_stats_method = (*env)->GetMethodID (env, klass, "onNativeStats",
      "(DDIILjava/lang/String;)V");
  on_log_method = (*env)->GetMethodID (env, klass, "onNativeLog",
      "(Ljava/lang/String;)V");
  on_recording_done_method = (*env)->GetMethodID (env, klass,
      "onNativeRecordingDone", "(Ljava/lang/String;)V");
  on_recording_error_method = (*env)->GetMethodID (env, klass,
      "onNativeRecordingError", "(Ljava/lang/String;)V");
  on_state_method = (*env)->GetMethodID (env, klass, "onNativeState",
      "(IILjava/lang/String;)V");
  return on_state_method && on_stats_method && on_log_method
      && on_recording_done_method && on_recording_error_method;
}

JNIEXPORT jlong JNICALL
Java_com_androidrvt_viewer_video_VideoEngine_nativeCreate (JNIEnv * env,
    jobject thiz)
{
  Engine *e;

  if (!cache_callbacks (env, thiz))
    return 0;

  e = g_new0 (Engine, 1);
  e->state = ST_STOPPED;
  g_mutex_init (&e->teardown_lock);
  g_cond_init (&e->teardown_cond);
  e->listener = (*env)->NewGlobalRef (env, thiz);
  e->context = g_main_context_new ();
  e->loop = g_main_loop_new (e->context, FALSE);
  e->thread = g_thread_new ("rvt-video", engine_thread, e);
  return (jlong) (intptr_t) e;
}

JNIEXPORT void JNICALL
Java_com_androidrvt_viewer_video_VideoEngine_nativeConfigure (JNIEnv * env,
    jobject thiz, jlong handle, jstring url, jstring transport,
    jint latency_ms, jint frame_timeout_ms, jint connect_timeout_ms,
    jboolean hardware_decoder)
{
  Config *cfg = g_new0 (Config, 1);

  cfg->url = dup_jstring (env, url);
  cfg->transport = dup_jstring (env, transport);
  cfg->latency_ms = latency_ms;
  cfg->frame_timeout_ms = frame_timeout_ms;
  cfg->connect_timeout_ms = connect_timeout_ms;
  cfg->hardware_decoder = hardware_decoder;
  run_sync (engine_from_handle (handle), do_configure, cfg);
}

JNIEXPORT void JNICALL
Java_com_androidrvt_viewer_video_VideoEngine_nativeSetSurface (JNIEnv * env,
    jobject thiz, jlong handle, jobject surface)
{
  ANativeWindow *window =
      surface ? ANativeWindow_fromSurface (env, surface) : NULL;

  run_sync (engine_from_handle (handle), do_set_window, window);
}

JNIEXPORT void JNICALL
Java_com_androidrvt_viewer_video_VideoEngine_nativePlay (JNIEnv * env,
    jobject thiz, jlong handle)
{
  run_sync (engine_from_handle (handle), do_play, NULL);
}

JNIEXPORT void JNICALL
Java_com_androidrvt_viewer_video_VideoEngine_nativeStop (JNIEnv * env,
    jobject thiz, jlong handle)
{
  run_sync (engine_from_handle (handle), do_stop, NULL);
}

JNIEXPORT void JNICALL
Java_com_androidrvt_viewer_video_VideoEngine_nativeReconnectNow (JNIEnv * env,
    jobject thiz, jlong handle)
{
  run_sync (engine_from_handle (handle), do_reconnect_now, NULL);
}

JNIEXPORT void JNICALL
Java_com_androidrvt_viewer_video_VideoEngine_nativeStartRecording (JNIEnv * env,
    jobject thiz, jlong handle, jstring directory)
{
  run_sync (engine_from_handle (handle), do_start_recording,
      dup_jstring (env, directory));
}

JNIEXPORT void JNICALL
Java_com_androidrvt_viewer_video_VideoEngine_nativeStopRecording (JNIEnv * env,
    jobject thiz, jlong handle)
{
  run_sync (engine_from_handle (handle), do_stop_recording, NULL);
}

JNIEXPORT void JNICALL
Java_com_androidrvt_viewer_video_VideoEngine_nativeDestroy (JNIEnv * env,
    jobject thiz, jlong handle)
{
  Engine *e = engine_from_handle (handle);

  run_sync (e, do_shutdown, NULL);
  g_thread_join (e->thread);
  g_main_loop_unref (e->loop);
  g_main_context_unref (e->context);
  (*env)->DeleteGlobalRef (env, e->listener);
  config_clear (&e->cfg);
  g_free (e->rec_dir);
  g_free (e->decoder_name);
  g_cond_clear (&e->teardown_cond);
  g_mutex_clear (&e->teardown_lock);
  g_free (e);
}
