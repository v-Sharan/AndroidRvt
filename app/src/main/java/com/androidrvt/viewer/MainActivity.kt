package com.androidrvt.viewer

import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.androidrvt.viewer.video.GstRuntime
import com.androidrvt.viewer.video.VideoEngine

private const val TEST_PATTERN =
    "videotestsrc is-live=true pattern=smpte horizontal-speed=4 " +
        "! video/x-raw,width=1280,height=720,framerate=30/1 " +
        "! glimagesink name=sink"

class MainActivity : ComponentActivity() {
    private var engine: VideoEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        // Shown on screen because cloud-built APKs are tested without a debugger attached.
        val startupError = try {
            GstRuntime.init(this)
            engine = VideoEngine(TEST_PATTERN)
            null
        } catch (t: Throwable) {
            "${t.javaClass.simpleName}: ${t.message}"
        }

        setContent {
            VideoScreen(engine, startupError)
        }
    }

    override fun onStart() {
        super.onStart()
        engine?.play()
    }

    override fun onStop() {
        engine?.stop()
        super.onStop()
    }

    override fun onDestroy() {
        engine?.close()
        engine = null
        super.onDestroy()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

@Composable
private fun VideoScreen(engine: VideoEngine?, startupError: String?) {
    val status = engine?.status?.collectAsState()?.value ?: startupError ?: "Not started"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (engine != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    SurfaceView(context).apply {
                        holder.addCallback(EngineSurfaceCallback(engine))
                    }
                },
            )
        }
        Text(
            text = status,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .background(Color(0x99000000))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

private class EngineSurfaceCallback(private val engine: VideoEngine) : SurfaceHolder.Callback {
    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        engine.setSurface(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        engine.setSurface(null)
    }
}
