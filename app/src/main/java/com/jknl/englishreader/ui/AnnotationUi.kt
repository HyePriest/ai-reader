package com.jknl.englishreader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.jknl.englishreader.data.Paragraph

/** A separate floating frame leaves the current translation and reading position in place. */
@Composable
internal fun AnnotationDialog(paragraph: Paragraph, index: Int, onDismiss: () -> Unit) {
    val note = paragraph.annotations[index]
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .35f)),
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).testTag("annotation-panel")) {
            Column(Modifier.padding(20.dp)) {
                SelectionContainer(Modifier.weight(1f, fill = false)) {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(note.text, fontSize = 17.sp, lineHeight = 29.sp)
                    }
                }
            }
        }
    }
}
