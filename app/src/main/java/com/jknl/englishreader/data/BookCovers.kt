package com.jknl.englishreader.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.util.AtomicFile
import java.io.File

/** Copies a bounded, upright thumbnail into private storage; no persistent gallery permission. */
class BookCovers(private val context: Context) {
    private val directory get() = File(context.filesDir, "covers").apply { mkdirs() }
    fun file(id: String): File {
        require(id.matches(Regex("[a-f0-9]{24}")))
        return File(directory, "$id.jpg")
    }
    fun remove(id: String) { val target = file(id); AtomicFile(target).delete(); cache.remove(target.path) }
    fun load(id: String): Bitmap? {
        val target = file(id)
        return cache.get(target.path) ?: runCatching {
            AtomicFile(target).openRead().use { BitmapFactory.decodeStream(it) }?.also { cache.put(target.path, it) }
        }.getOrNull()
    }

    fun save(id: String, uri: Uri) = saveImage(id) { context.contentResolver.openInputStream(uri) }
    fun save(id: String, bytes: ByteArray) = saveImage(id) { bytes.inputStream() }

    fun savePdf(id: String, source: File) {
        android.os.ParcelFileDescriptor.open(source, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            android.graphics.pdf.PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(0).use { page ->
                    val scale = 1024f / maxOf(page.width, page.height)
                    val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1),
                        (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val bytes = java.io.ByteArrayOutputStream()
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes))
                        save(id, bytes.toByteArray())
                    } finally { bitmap.recycle() }
                }
            }
        }
    }

    private fun saveImage(id: String, open: () -> java.io.InputStream?) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth in 1..60000 && bounds.outHeight in 1..60000) { "这张图片无法读取，请选择 JPG、PNG 或 WebP 图片" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
        val bitmap = open()?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("无法读取图片")
        var upright: Bitmap = bitmap
        try {
            val orientation = runCatching { open()?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } }.getOrNull()
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                }
            }
            if (!matrix.isIdentity) upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            val atomic = AtomicFile(file(id))
            val stream = atomic.startWrite()
            try { check(upright.compress(Bitmap.CompressFormat.JPEG, 87, stream)); atomic.finishWrite(stream); cache.remove(file(id).path) }
            catch (e: Exception) { atomic.failWrite(stream); throw e }
        } finally {
            if (upright !== bitmap) upright.recycle()
            bitmap.recycle()
        }
    }
    companion object {
        private val cache = object : android.util.LruCache<String, Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }
    }
}
