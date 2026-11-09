package com.androidrvt.viewer

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** In-app log, so problems can be diagnosed on a phone with no debugger attached. */
object AppLog {
    private const val MAX_LINES = 500
    private const val CRASH_FILE = "last_crash.txt"
    private val credentials = Regex("(rtsps?://[^:/@\\s]+:)[^@\\s]+@")

    private val mutableLines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = mutableLines.asStateFlow()

    fun add(message: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$time  ${mask(message)}"
        mutableLines.update { (it + line).takeLast(MAX_LINES) }
    }

    fun clear() {
        mutableLines.value = emptyList()
    }

    fun mask(text: String): String = text.replace(credentials, "$1***@")

    /** Keeps the last Kotlin crash so it can be read on the next launch. */
    fun installCrashHandler(context: Context) {
        val file = File(context.filesDir, CRASH_FILE)
        if (file.exists()) {
            add("Previous crash:\n${file.readText()}")
            file.delete()
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file.writeText(error.stackTraceToString()) }
            previous?.uncaughtException(thread, error)
        }
    }
}
