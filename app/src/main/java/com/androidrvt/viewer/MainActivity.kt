package com.androidrvt.viewer

import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.androidrvt.viewer.ui.LiveScreen
import com.androidrvt.viewer.ui.LogScreen
import com.androidrvt.viewer.ui.SettingsScreen
import kotlin.math.abs

private enum class Overlay { NONE, SETTINGS, LOG }

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.installCrashHandler(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var overlay by remember { mutableStateOf(Overlay.NONE) }

                // The live view stays underneath so opening settings doesn't interrupt the video.
                Box(Modifier.fillMaxSize()) {
                    LiveScreen(
                        viewModel = viewModel,
                        onOpenSettings = { overlay = Overlay.SETTINGS },
                        onOpenLog = { overlay = Overlay.LOG },
                    )
                    when (overlay) {
                        Overlay.SETTINGS -> SettingsScreen(viewModel, onClose = { overlay = Overlay.NONE })
                        Overlay.LOG -> LogScreen(onClose = { overlay = Overlay.NONE })
                        Overlay.NONE -> Unit
                    }
                }
                BackHandler(enabled = overlay != Overlay.NONE) { overlay = Overlay.NONE }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onForeground()
    }

    override fun onStop() {
        viewModel.onBackground()
        super.onStop()
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val isStick = event.isFromSource(InputDevice.SOURCE_JOYSTICK) && event.action == MotionEvent.ACTION_MOVE
        if (!isStick) return super.dispatchGenericMotionEvent(event)
        viewModel.setGamepadAxes(
            pan = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_X)),
            tilt = -applyDeadzone(event.getAxisValue(MotionEvent.AXIS_Y)),
            zoom = -applyDeadzone(event.getAxisValue(MotionEvent.AXIS_RZ)),
        )
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_GAMEPAD)) return super.dispatchKeyEvent(event)
        val pressed = event.action == KeyEvent.ACTION_DOWN
        val firstPress = pressed && event.repeatCount == 0
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> if (firstPress) viewModel.trackPoint(0.5f, 0.5f)
            KeyEvent.KEYCODE_BUTTON_B -> if (firstPress) viewModel.trackStop()
            KeyEvent.KEYCODE_BUTTON_X -> if (firstPress) viewModel.requestSnapshot()
            KeyEvent.KEYCODE_BUTTON_Y -> if (firstPress) viewModel.toggleRecording()
            KeyEvent.KEYCODE_BUTTON_R1 -> viewModel.setZoomRate(if (pressed) 1f else 0f)
            KeyEvent.KEYCODE_BUTTON_L1 -> viewModel.setZoomRate(if (pressed) -1f else 0f)
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    private fun applyDeadzone(value: Float): Float = if (abs(value) < GAMEPAD_DEADZONE) 0f else value

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private companion object {
        const val GAMEPAD_DEADZONE = 0.12f
    }
}
