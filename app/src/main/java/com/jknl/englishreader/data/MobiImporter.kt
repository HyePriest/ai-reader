package com.jknl.englishreader.data

object MobiImporter {
    init { System.loadLibrary("ebook_import") }
    private external fun extract(bytes: ByteArray): Array<ByteArray>

    fun read(bytes: ByteArray, fileName: String, onCover: (String, ByteArray) -> Unit = { _, _ -> }): Book {
        val parts = extract(bytes)
        val id = contentId("kindle:" + bytes.sha256())
        val markup = parts.drop(4).chunked(2).associate { it[0].toString(Charsets.UTF_8) to it[1].toString(Charsets.UTF_8) }
        val book = KindleBookReader.read(id, parts[0].toString(Charsets.UTF_8).ifBlank { fileName.substringBeforeLast('.') },
            parts[1].toString(Charsets.UTF_8).ifBlank { "作者未注明" }, markup, parts[3].toString(Charsets.UTF_8))
        if (parts[2].isNotEmpty()) onCover(id, parts[2])
        return book
    }
}

internal fun ByteArray.sha256(): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(this).joinToString("") { "%02x".format(it) }
