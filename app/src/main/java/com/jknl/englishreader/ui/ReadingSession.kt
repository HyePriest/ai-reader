package com.jknl.englishreader.ui

import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.jknl.englishreader.data.ReadingTime
import com.jknl.englishreader.data.ReadingTimer
import kotlinx.coroutines.delay

@Composable
internal fun ReadingSession(bookId: String, enabled: Boolean) {
    val context = LocalContext.current.applicationContext
    val owner = LocalLifecycleOwner.current
    val time = remember { ReadingTime(context) }
    val timer = remember(bookId) { ReadingTimer(SystemClock::elapsedRealtime) }
    var resumed by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner, timer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    // Flush immediately, even when a paused composition cannot run effects.
                    time.add(bookId, timer.setRunning(false)); resumed = false
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); time.add(bookId, timer.setRunning(false)) }
    }
    LaunchedEffect(enabled, resumed, timer) {
        time.add(bookId, timer.setRunning(enabled && resumed))
        if (enabled && resumed) while (true) {
            delay(10_000)
            time.add(bookId, timer.checkpoint())
        }
    }
}
