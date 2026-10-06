package com.jknl.englishreader.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.jknl.englishreader.data.Book

@Composable
internal fun PdfOriginalDialog(book: Book, initialPage: Int, onDismiss: () -> Unit) {
    var page by remember { mutableIntStateOf(initialPage) }
    val sourceDirectory = LocalContext.current.filesDir
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onDismiss) { Text("返回正文") }
                    TextButton(enabled = page > 0, onClick = { page-- }) { Text("上一页") }
                    TextButton(enabled = page < book.chapters.lastIndex, onClick = { page++ }) { Text("下一页") }
                }
                Text("${page + 1} / ${book.chapters.size} · 点击原页放大")
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
                    item(key = page) { PdfPageImage(book.id, page, sourceDirectory) }
                }
            }
        }
    }
}

@Composable
internal fun PdfPageImage(bookId: String, pageIndex: Int, sourceDirectory: File? = null) {
    val context = LocalContext.current
    val filesDirectory = sourceDirectory ?: context.filesDir
    var image by remember(bookId, pageIndex) { mutableStateOf<Bitmap?>(null) }
    var error by remember(bookId, pageIndex) { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(bookId, pageIndex) {
        try {
            image = withContext(Dispatchers.IO) {
                require(bookId.matches(Regex("[a-f0-9]{24}")))
                val file = File(filesDirectory, "library/$bookId.pdf")
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        renderer.openPage(pageIndex).use { page ->
                            val ratio = 1600f / maxOf(page.width, page.height)
                            Bitmap.createBitmap((page.width * ratio).toInt().coerceAtLeast(1),
                                (page.height * ratio).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also { bitmap ->
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            android.util.Log.w("PdfPage", "Unable to render page $pageIndex", e)
            error = "原 PDF 文件无法打开，请重新导入这本书。"
        }
    }
    val bitmap = image
    when {
        error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
        bitmap == null -> CircularProgressIndicator(Modifier.padding(24.dp))
        else -> Image(bitmap.asImageBitmap(), "第 ${pageIndex + 1} 页，点击放大", contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height).clickable { expanded = true })
    }
    if (expanded && bitmap != null) Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Surface(Modifier.fillMaxSize()) {
            Column {
                TextButton(onClick = { expanded = false }) { Text("关闭 · 双指缩放和移动") }
                Box(Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 5f); offset += pan }
                }) {
                    Image(bitmap.asImageBitmap(), "第 ${pageIndex + 1} 页", contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
                }
            }
        }
    }
}
