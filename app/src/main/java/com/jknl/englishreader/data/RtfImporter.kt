package com.jknl.englishreader.data

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Reads RTF text groups, code pages and Unicode fallback without treating controls as prose. */
object RtfImporter {
    private data class State(val skip: Boolean = false, val unicodeFallback: Int = 1, val codePage: String = "windows-1252")
    private val destinations = setOf("fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "header", "footer", "headerl", "headerr", "footerl", "footerr", "fldinst", "listtable", "listoverridetable", "generator", "xmlnstbl", "datastore", "themedata")

    fun read(bytes: ByteArray, fileName: String): Book {
        val raw = bytes.toString(Charsets.ISO_8859_1)
        require(raw.startsWith("{\\rtf")) { "这不是有效的 RTF 文件" }
        val output = StringBuilder()
        val pending = ByteArrayOutputStream()
        val states = ArrayDeque<State>()
        var state = State()
        var fallback = 0
        var index = 0
        fun flush() {
            if (pending.size() == 0) return
            val decoder = Charset.forName(state.codePage).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            output.append(decoder.decode(ByteBuffer.wrap(pending.toByteArray())))
            pending.reset()
        }
        fun byte(value: Int) { if (fallback > 0) fallback-- else if (!state.skip) pending.write(value) }
        fun text(value: String) { flush(); if (!state.skip) output.append(value) }
        while (index < raw.length) {
            when (val c = raw[index++]) {
                '{' -> { flush(); require(states.size < 256) { "RTF 嵌套过深" }; states.addLast(state) }
                '}' -> { flush(); require(states.isNotEmpty()) { "RTF 分组损坏" }; state = states.removeLast() }
                '\n', '\r' -> Unit
                '\\' -> {
                    require(index < raw.length) { "RTF 控制符截断" }
                    val symbol = raw[index]
                    if (symbol == '\'') {
                        index++
                        require(index + 2 <= raw.length) { "RTF 字符编码截断" }
                        byte(raw.substring(index, index + 2).toInt(16)); index += 2
                    } else if (!symbol.isLetter()) {
                        index++
                        when (symbol) {
                            '*' -> { flush(); state = state.copy(skip = true) }
                            '\\', '{', '}' -> byte(symbol.code)
                            '~' -> { if (fallback > 0) fallback-- else text("\u00a0") }
                            '_' -> text("\u2011")
                        }
                    } else {
                        flush()
                        val start = index
                        while (index < raw.length && raw[index].isLetter()) index++
                        val word = raw.substring(start, index)
                        val numberStart = index
                        if (raw.getOrNull(index) == '-') index++
                        while (index < raw.length && raw[index].isDigit()) index++
                        val number = raw.substring(numberStart, index).toIntOrNull()
                        if (raw.getOrNull(index) == ' ') index++
                        when {
                            word in destinations -> state = state.copy(skip = true)
                            word == "ansicpg" -> state = state.copy(codePage = when (number) {
                                65001 -> "UTF-8"; 936 -> "GBK"; 950 -> "Big5"; 932 -> "Shift_JIS"; 949 -> "EUC-KR"
                                else -> "windows-${number ?: 1252}"
                            })
                            word == "uc" -> state = state.copy(unicodeFallback = (number ?: 1).also { require(it in 0..16) })
                            word == "u" -> { if (!state.skip) output.append((requireNotNull(number) and 0xffff).toChar()); fallback = state.unicodeFallback }
                            word == "par" || word == "line" -> text("\n")
                            word == "tab" -> text("\t")
                            word == "emdash" -> text("—")
                            word == "endash" -> text("–")
                            word == "lquote" -> text("‘")
                            word == "rquote" -> text("’")
                            word == "ldblquote" -> text("“")
                            word == "rdblquote" -> text("”")
                            word == "bin" -> { val size = requireNotNull(number); require(size >= 0 && size <= raw.length - index); index += size }
                        }
                    }
                }
                else -> byte(c.code)
            }
        }
        flush()
        require(states.isEmpty()) { "RTF 分组截断" }
        return DocumentImporter.text(output.toString(), fileName.substringBeforeLast('.'))
    }
}
