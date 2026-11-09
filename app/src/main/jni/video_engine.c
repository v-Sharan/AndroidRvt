#include <jni.h>
#include <stdint.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <gst/gst.h>
#include <gst/video/videooverlay.h>

#define LOG_TAG "RvtVideo"
#define LOGE(...) __android_log_print (ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* All pipeline state is owned by the engine thread; JNI calls hop onto it with run_sync(). */
typedef struct
{
  GMainContext *context;
  GMainLoop *loop;
  GThread *thread;
  JNIEnv *thread_env;
  jobject listener;

  gchar *description;
  gboolean want_playing;
  ANativeWindow *window;

  GstElement *pipeline;
  GstElement *sink;
  GSource *bus_source;
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

static JavaVM *java_vm;
static jmethodID on_state_method;
static jmethodID on_error_method;

JNIEXPORT jint JNICALL
JNI_OnLoad (JavaVM * vm, void *reserved)
{
  java_vm = vm;
  return JNI_VERSION_1_6;
}

static void
notify (Engine * engine, jmethodID method, const gchar * message)
{
  JNIEnv *env = engine->thread_env;
  jstring text = (*env)->NewStringUTF (env, message);

  (*env)->CallVoidMethod (env, engine->listener, method, text);
  if ((*env)->ExceptionCheck (env)) {
    LOGE ("Kotlin callback threw an exception");
    (*env)->ExceptionClear (env);
  }
  (*env)->DeleteLocalRef (env, text);
}

static void
teardown_pipeline (Engine * engine)
{
  if (engine->bus_source) {
    g_source_destroy (engine->bus_source);
    g_source_unref (engine->bus_source);
    engine->bus_source = NULL;
  }
  if (engine->pipeline)
    gst_element_set_state (engine->pipeline, GST_STATE_NULL);
  gst_clear_object (&engine->sink);
  gst_clear_object (&engine->pipeline);
}

static gboolean
on_bus_message (GstBus * bus, GstMessage * message, gpointer user_data)
{
  Engine *engine = user_data;

  switch (GST_MESSAGE_TYPE (message)) {
    case GST_MESSAGE_ERROR:{
      GError *error = NULL;
      gchar *text;

      gst_message_parse_error (message, &error, NULL);
      text = g_strdup_printf ("%s: %s", GST_OBJECT_NAME (message->src),
          error->message);
      notify (engine, on_error_method, text);
      g_free (text);
      g_clear_error (&error);
      teardown_pipeline (engine);
      break;
    }
    case GST_MESSAGE_EOS:
      notify (engine, on_error_method, "Stream ended");
      teardown_pipeline (engine);
      break;
    case GST_MESSAGE_STATE_CHANGED:
      if (GST_MESSAGE_SRC (message) == GST_OBJECT (engine->pipeline)) {
        GstState new_state;

        gst_message_parse_state_changed (message, NULL, &new_state, NULL);
        notify (engine, on_state_method, gst_element_state_get_name (new_state));
      }
      break;
    default:
      break;
  }
  return G_SOURCE_CONTINUE;
}

static void
start_if_ready (Engine * engine)
{
  GError *error = NULL;
  GstElement *pipeline;
  GstBus *bus;

  if (!engine->want_playing || !engine->window || engine->pipeline)
    return;

  pipeline = gst_parse_launch (engine->description, &error);
  if (error) {
    notify (engine, on_error_method, error->message);
    g_clear_error (&error);
    gst_clear_object (&pipeline);
    return;
  }

  gst_element_set_state (pipeline, GST_STATE_READY);
  engine->sink = gst_bin_get_by_interface (GST_BIN (pipeline),
      GST_TYPE_VIDEO_OVERLAY);
  if (!engine->sink) {
    notify (engine, on_error_method, "Pipeline has no video sink");
    gst_element_set_state (pipeline, GST_STATE_NULL);
    gst_object_unref (pipeline);
    return;
  }
  engine->pipeline = pipeline;

  gst_video_overlay_set_window_handle (GST_VIDEO_OVERLAY (engine->sink),
      (guintptr) engine->window);

  bus = gst_element_get_bus (pipeline);
  engine->bus_source = gst_bus_create_watch (bus);
  g_source_set_callback (engine->bus_source, (GSourceFunc) on_bus_message,
      engine, NULL);
  g_source_attach (engine->bus_source, engine->context);
  gst_object_unref (bus);

  gst_element_set_state (pipeline, GST_STATE_PLAYING);
}

static void
do_set_window (Engine * engine, gpointer arg)
{
  ANativeWindow *window = arg;

  if (window && window == engine->window) {
    /* Same surface, only its size changed: drop the extra reference and redraw. */
    ANativeWindow_release (window);
    if (engine->sink) {
      gst_video_overlay_expose (GST_VIDEO_OVERLAY (engine->sink));
      gst_video_overlay_expose (GST_VIDEO_OVERLAY (engine->sink));
    }
    return;
  }

  /* The sink must stop using the old surface before it is released. */
  teardown_pipeline (engine);
  if (engine->window)
    ANativeWindow_release (engine->window);
  engine->window = window;
  start_if_ready (engine);
}

static void
do_play (Engine * engine, gpointer arg)
{
  engine->want_playing = TRUE;
  start_if_ready (engine);
}

static void
do_stop (Engine * engine, gpointer arg)
{
  engine->want_playing = FALSE;
  teardown_pipeline (engine);
  notify (engine, on_state_method, "STOPPED");
}

static void
do_shutdown (Engine * engine, gpointer arg)
{
  teardown_pipeline (engine);
  if (engine->window) {
    ANativeWindow_release (engine->window);
    engine->window = NULL;
  }
  g_main_loop_quit (engine->loop);
}

static gboolean
sync_call_trampoline (gpointer user_data)
{
  SyncCall *call = user_data;

  call->func (call->engine, call->arg);

  g_mutex_lock (&call->mutex);
  call->done = TRUE;
  g_cond_signal (&call->cond);
  g_mutex_unlock (&call->mutex);
  return G_SOURCE_REMOVE;
}

static void
run_sync (Engine * engine, EngineFunc func, gpointer arg)
{
  SyncCall call = {.engine = engine,.func = func,.arg = arg };

  g_mutex_init (&call.mutex);
  g_cond_init (&call.cond);
  g_main_context_invoke (engine->context, sync_call_trampoline, &call);

  g_mutex_lock (&call.mutex);
  while (!call.done)
    g_cond_wait (&call.cond, &call.mutex);
  g_mutex_unlock (&call.mutex);

  g_cond_clear (&call.cond);
  g_mutex_clear (&call.mutex);
}

static gpointer
engine_thread (gpointer user_data)
{
  Engine *engine = user_data;

  (*java_vm)->AttachCurrentThread (java_vm, &engine->thread_env, NULL);
  g_main_context_push_thread_default (engine->context);
  g_main_loop_run (engine->loop);
  g_main_context_pop_thread_default (engine->context);
  (*java_vm)->DetachCurrentThread (java_vm);
  return NULL;
}

static Engine *
engine_from_handle (jlong handle)
{
  return (Engine *) (intptr_t) handle;
}

JNIEXPORT jlong JNICALL
Java_com_androidrvt_viewer_video_VideoEngine_nativeCreate (JNIEnv * env,
    jobject thiz, jstring description)
{
  Engine *engine;
  const char *chars;

  if (!on_state_method) {
    jclass klass = (*env)->GetObjectClass (env, thiz);

    on_state_method = (*env)->GetMethodID (env, klass, "onNativeState",
        "(Ljava/lang/String;)V");
    on_error_method = (*env)->GetMethodID (env, klass, "onNativeError",
        "(Ljava/lang/String;)V");
    if (!on_state_method || !on_error_method)
      return 0;
  }

  engine = g_new0 (Engine, 1);
  chars = (*env)->GetStringUTFChars (env, description, NULL);
  engine->description = g_strdup (chars);
  (*env)->ReleaseStringUTFChars (env, description, chars);

  engine->listener = (*env)->NewGlobalRef (env, thiz);
  engine->context = g_main_context_new ();
  engine->loop = g_main_loop_new (engine->context, FALSE);
  engine->thread = g_thread_new ("rvt-video", engine_thread, engine);
  return (jlong) (intptr_t) engine;
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
Java_com_androidrvt_viewer_video_VideoEngine_nativeDestroy (JNIEnv * env,
    jobject thiz, jlong handle)
{
  Engine *engine = engine_from_handle (handle);

  run_sync (engine, do_shutdown, NULL);
  g_thread_join (engine->thread);
  g_main_loop_unref (engine->loop);
  g_main_context_unref (engine->context);
  (*env)->DeleteGlobalRef (env, engine->listener);
  g_free (engine->description);
  g_free (engine);
}
