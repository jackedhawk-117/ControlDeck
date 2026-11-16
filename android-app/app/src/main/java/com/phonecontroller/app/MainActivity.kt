package com.phonecontroller.app

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.phonecontroller.app.ui.HomeScreen
import com.phonecontroller.app.ui.PhoneControllerTheme
import com.phonecontroller.app.ui.PlayScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhoneControllerTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val scope = rememberCoroutineScope()
    val model = remember { AppModel(scope) }
    val conn = model.connection
    val playing = conn != null
    val activity = LocalActivity.current as ComponentActivity

    // Gameplay is landscape-only (spec §9); the connection/editor screens
    // remain free to rotate.
    LaunchedEffect(playing) {
        activity.requestedOrientation = if (playing) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // If the activity is no longer visible (screen locked, user switched
    // away), close the session immediately so the receiver releases every
    // held key/button instead of waiting for its input watchdog. The screen
    // may already be locked before ON_STOP, so do not rely on the watchdog.
    DisposableEffect(activity, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) model.disconnect()
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }

    // Keep the screen on and hide system bars while playing so touches near
    // the edges do not summon navigation chrome mid-game.
    DisposableEffect(playing) {
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (playing) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    if (conn == null) {
        HomeScreen(model)
    } else {
        PlayScreen(model, conn)
    }
}
