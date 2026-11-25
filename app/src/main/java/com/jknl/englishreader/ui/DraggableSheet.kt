package com.jknl.englishreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A bottom sheet with exactly two resting states, open and closed. Its offset follows the finger and
 * settles with a spring, and it never recomputes resting positions when its content changes size,
 * which is what made the platform sheet bounce. When the content is scrolled to the top, pulling
 * further down drags the sheet.
 */
@Composable
internal fun DraggableSheet(onDismiss: () -> Unit, heightFraction: Float = .9f, content: @Composable ColumnScope.() -> Unit) {
    val scope = rememberCoroutineScope()
    var height by remember { mutableFloatStateOf(0f) }
    // Starts far below; the first measured height places it off-screen, then it springs in.
    var offset by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    var closing by remember { mutableStateOf(false) }
    val latestDismiss by rememberUpdatedState(onDismiss)
    suspend fun settle(velocity: Float) {
        val dismiss = velocity > 1800f || offset > height * .3f
        if (dismiss) {
            closing = true
            animate(offset, height, velocity.coerceAtLeast(0f), tween(200)) { v, _ -> offset = v }
            latestDismiss()
        } else animate(offset, 0f, velocity, spring(dampingRatio = .78f, stiffness = Spring.StiffnessMediumLow)) { v, _ -> offset = v }
    }
    fun close() { if (!closing) scope.launch { closing = true; animate(offset, height, 0f, tween(200)) { v, _ -> offset = v }; latestDismiss() } }
    BackHandler(onBack = ::close)
    // Slide in once, after the first measurement. Keyed on nothing, so a later size change
    // (insets, keyboard) cannot cancel the entrance and leave the sheet below the screen.
    LaunchedEffect(Unit) {
        val measured = snapshotFlow { height }.first { it > 0f }
        offset = measured
        animate(measured, 0f, 0f, spring(dampingRatio = .85f, stiffness = Spring.StiffnessMediumLow)) { v, _ -> offset = v }
    }
    val connection = remember {
        object : NestedScrollConnection {
            // Pulling up while the sheet is lowered raises the sheet before the list scrolls.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0 && offset > 0f && offset != Float.MAX_VALUE && source == NestedScrollSource.UserInput) {
                    val next = (offset + available.y).coerceAtLeast(0f)
                    val used = next - offset
                    offset = next
                    return Offset(0f, used)
                }
                return Offset.Zero
            }
            // Pulling down past the top of the list lowers the sheet.
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0 && source == NestedScrollSource.UserInput && !closing) {
                    offset = (offset + available.y).coerceAtMost(height)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (offset > 0f && offset != Float.MAX_VALUE && !closing) { settle(available.y); return available }
                return Velocity.Zero
            }
        }
    }
    val progress = if (height <= 0f || offset == Float.MAX_VALUE) 0f else (1f - offset / height).coerceIn(0f, 1f)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .32f * progress))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = ::close))
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(heightFraction)
            .onSizeChanged { height = it.height.toFloat() }
            .offset { IntOffset(0, if (offset == Float.MAX_VALUE) 100_000 else offset.roundToInt()) }
            .nestedScroll(connection).testTag("draggable-sheet"),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = MaterialTheme.colorScheme.background, shadowElevation = 8.dp) {
            Column(Modifier.fillMaxSize()) {
                // The handle strip drags the sheet directly.
                Box(Modifier.fillMaxWidth().height(22.dp).draggable(rememberDraggableState { d -> if (!closing) offset = (offset + d).coerceIn(0f, height) },
                    Orientation.Vertical, onDragStopped = { v -> settle(v) }).testTag("sheet-handle"), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(36.dp, 4.dp).background(MaterialTheme.colorScheme.outline, CircleShape))
                }
                content()
            }
        }
    }
}
