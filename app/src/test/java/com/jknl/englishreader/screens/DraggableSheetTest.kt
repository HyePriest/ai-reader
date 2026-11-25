package com.jknl.englishreader.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.jknl.englishreader.ui.DraggableSheet
import com.jknl.englishreader.ui.theme.EnglishReaderTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The chat sheet must follow drags, spring back on a short pull, close on a long one, and scroll its list normally. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w392dp-h850dp-xxhdpi")
class DraggableSheetTest {
    @get:Rule val compose = createComposeRule()
    private val dismissed = mutableIntStateOf(0)

    private fun show() {
        compose.setContent {
            EnglishReaderTheme("paper") {
                DraggableSheet(onDismiss = { dismissed.intValue++ }) {
                    LazyColumn(Modifier.fillMaxSize().testTag("list")) { items(60) { Text("row $it", Modifier.height(48.dp).testTag("row-$it")) } }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun sheetTop() = compose.onNodeWithTag("draggable-sheet").fetchSemanticsNode().positionInRoot.y

    @Test fun shortPullSpringsBack() {
        show()
        val open = sheetTop()
        compose.onNodeWithTag("list").performTouchInput { swipeDown(startY = top + 20f, endY = top + 140f, durationMillis = 400) }
        compose.waitForIdle()
        assertEquals(0, dismissed.intValue)
        assertEquals(open, sheetTop(), 2f)
    }

    @Test fun longPullCloses() {
        show()
        compose.onNodeWithTag("list").performTouchInput { swipeDown(startY = top + 20f, endY = top + height * .7f, durationMillis = 400) }
        compose.waitForIdle()
        assertEquals(1, dismissed.intValue)
    }

    @Test fun handleDragCloses() {
        show()
        compose.onNodeWithTag("sheet-handle").performTouchInput { swipeDown(startY = centerY, endY = centerY + 1400f, durationMillis = 300) }
        compose.waitForIdle()
        assertEquals(1, dismissed.intValue)
    }

    @Test fun scrollingTheListDoesNotMoveTheSheet() {
        show()
        val open = sheetTop()
        compose.onNodeWithTag("list").performTouchInput { swipeUp(startY = bottom - 40f, endY = top + 40f, durationMillis = 400) }
        compose.waitForIdle()
        assertEquals(open, sheetTop(), 2f)
        compose.onNodeWithTag("row-0").assertIsNotDisplayed()
        // Scrolling back down returns to the top of the list before the sheet itself moves.
        compose.onNodeWithTag("list").performTouchInput { swipeDown(startY = top + 40f, endY = top + 300f, durationMillis = 400) }
        compose.waitForIdle()
        assertEquals(0, dismissed.intValue)
        assertEquals(open, sheetTop(), 2f)
    }
}
