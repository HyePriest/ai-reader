package com.jknl.englishreader

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.mutableStateOf
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jknl.englishreader.ui.ReaderApp

class MainActivity : ComponentActivity() {
    var readingAiTrigger: (() -> Unit)? = null
    /** Text or a file shared into the app, waiting for the UI to open it. */
    val incoming = mutableStateOf<Intent?>(null)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action != Intent.ACTION_MAIN) incoming.value = intent
    }
    private val keyHandler = Handler(Looper.getMainLooper())
    private var volumeHeld = false
    private val wakeReadingAi = Runnable {
        if (volumeHeld && hasWindowFocus()) readingAiTrigger?.invoke()
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                volumeHeld = true
                keyHandler.removeCallbacks(wakeReadingAi)
                if (readingAiTrigger != null) keyHandler.postDelayed(wakeReadingAi, 300L)
            } else if (event.action == KeyEvent.ACTION_UP) cancelVolumeWake()
        }
        // Volume keeps its normal behavior. No haptic feedback or special permission.
        return super.dispatchKeyEvent(event)
    }
    private fun cancelVolumeWake() { volumeHeld = false; keyHandler.removeCallbacks(wakeReadingAi) }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) cancelVolumeWake()
    }
    override fun onPause() { cancelVolumeWake(); super.onPause() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (savedInstanceState == null && intent?.action != Intent.ACTION_MAIN) incoming.value = intent
        setContent { ReaderApp(incoming.value, onIncomingHandled = { incoming.value = null }) }
    }
}
