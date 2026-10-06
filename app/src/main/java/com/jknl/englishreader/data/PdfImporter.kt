package com.jknl.englishreader.data

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException

/** A text PDF is reflowed into page sections. Image-only pages are reported explicitly. */
object PdfImporter {
    fun read(bytes: ByteArray, fileName: String): Book {
        val document = try { PDDocument.load(bytes) }
        catch (_: InvalidPasswordException) { error("PDF 需要密码，当前无法直接打开") }
        document.use { pdf ->
            require(pdf.currentAccessPermission.canExtractContent()) { "PDF 限制了文字提取" }
            require(pdf.numberOfPages in 1..3000) { "PDF 页数为空或超过 3000 页" }
            val stripper = PDFTextStripper().apply {
                sortByPosition = true
                paragraphStart = "\n\n"
                paragraphEnd = "\n\n"
            }
            val chapters = (1..pdf.numberOfPages).map { page ->
                stripper.startPage = page
                stripper.endPage = page
                val text = stripper.getText(pdf).trim()
                val paragraphs = text.split(Regex("\\n\\s*\\n")).mapNotNull { block ->
                    block.trim().takeIf { it.isNotEmpty() }?.replace(Regex("(?<!\\n)\\n(?!\\n)"), " ")
                }.mapIndexed { index, block -> Paragraph("p$index", block) }
                Chapter("page-$page", "第 $page 页", paragraphs.ifEmpty {
                    listOf(Paragraph("image-page", "第 $page 页 · 原页阅读", heading = true, pdfPage = page - 1))
                }, emptyList())
            }
            val info = pdf.documentInformation
            val contents = mutableListOf<ContentsEntry>()
            fun outline(first: com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem?, depth: Int) {
                var item = first
                var count = 0
                while (item != null && count++ < 6000 && depth < 32) {
                    val current = item
                    val page = runCatching { current.findDestinationPage(pdf) }.getOrNull()
                    val index = page?.let { pdf.pages.indexOf(it) } ?: -1
                    if (index in chapters.indices && current.title.orEmpty().isNotBlank())
                        contents += ContentsEntry(current.title, index, depth = depth)
                    outline(current.firstChild, depth + 1)
                    item = current.nextSibling
                }
            }
            outline(pdf.documentCatalog.documentOutline?.firstChild, 0)
            return Book(contentId("pdf:" + bytes.sha256()), info.title?.trim().orEmpty().ifBlank { fileName.substringBeforeLast('.') },
                info.author?.trim().orEmpty().ifBlank { "作者未注明" }, chapters, System.currentTimeMillis(), sourceFormat = "pdf", contents = contents)
        }
    }
}
