package com.jknl.englishreader.data

import org.json.JSONObject

/** Public JSON uses literal quotes so agents do not need to calculate UTF-16 offsets. */
object BookAnnotations {
    fun read(owner: JSONObject, body: String, location: String, offset: Int = 0): List<BookAnnotation> {
        if (!owner.has("annotations")) return emptyList()
        val entries = owner.getJSONArray("annotations")
        return (0 until entries.length()).map { index ->
            val note = entries.getJSONObject(index)
            val label = "$location 的第 ${index + 1} 条注释"
            val text = note.getString("text").trim()
            require(text.isNotBlank()) { "$label 内容不能为空" }
            var start = 0
            var end = body.length
            if (note.has("quote")) {
                val quote = note.getString("quote")
                require(quote.isNotBlank()) { "$label 的 quote 不能为空" }
                val matches = mutableListOf<Int>()
                var at = body.indexOf(quote)
                while (at >= 0) {
                    matches += at
                    at = body.indexOf(quote, at + 1)
                }
                require(matches.isNotEmpty()) { "$label 的 quote 在对应正文中找不到" }
                require(matches.size == 1 || note.has("occurrence")) { "$label 的 quote 出现多次，请用 occurrence 指定第几次（从 1 开始）" }
                val occurrence = if (note.has("occurrence")) {
                    val value = note.get("occurrence")
                    require(value is Number && value.toDouble() == value.toInt().toDouble()) { "$label 的 occurrence 必须是整数" }
                    value.toInt()
                } else 1
                require(occurrence in 1..matches.size) { "$label 的 occurrence 超出出现次数" }
                start = matches[occurrence - 1]
                end = start + quote.length
            } else {
                require(!note.has("occurrence")) { "$label 使用 occurrence 时必须提供 quote" }
            }
            BookAnnotation(start + offset, end + offset, text, note.optString("title", ""), note.optString("source", ""))
        }
    }

    /** Reimport only replaces annotations in supplied, annotated paragraphs; original content stays intact. */
    fun supplement(existing: Book, incoming: Book): Book {
        require(existing.id == incoming.id) { "注释文件不属于同一本书" }
        val chapters = incoming.chapters.associateBy { it.id }
        return existing.copy(chapters = existing.chapters.map { chapter ->
            val incomingParagraphs = chapters[chapter.id]?.paragraphs.orEmpty().associateBy { it.id }
            chapter.copy(paragraphs = chapter.paragraphs.map { p ->
                val addition = incomingParagraphs[p.id]
                if (addition == null || addition.annotations.isEmpty()) p else {
                    require(addition.text == p.text) { "注释对应的正文已变化：${p.id}" }
                    p.copy(annotations = addition.annotations)
                }
            }).also(Alignments::validate)
        })
    }
}
