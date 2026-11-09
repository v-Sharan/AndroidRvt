package com.androidrvt.viewer.video

import android.content.Context
import org.freedesktop.gstreamer.GStreamer

object GstRuntime {
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        System.loadLibrary("gstreamer_android")
        System.loadLibrary("rvtvideo")
        GStreamer.init(context.applicationContext)
        initialized = true
    }
}
