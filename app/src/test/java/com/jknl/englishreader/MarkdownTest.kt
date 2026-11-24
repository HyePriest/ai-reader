package com.jknl.englishreader.ui

import org.junit.Assert.*
import org.junit.Test

class MarkdownTest {
    @Test fun parsesBlocksAnswersUse() {
        val blocks = MarkdownParser.parse("""
            ## 全文大意
            第一行
            第二行

            1. 一
            2. 二
            - 点
              - 子点
            > 引用
            > 续
            ---
            | 题号 | 答案 |
            |---|:---:|
            | 1 | B |
            ```
            code
            ```
        """.trimIndent())
        assertEquals(MdBlock.Heading(2, "全文大意"), blocks[0])
        assertEquals(MdBlock.Paragraph("第一行\n第二行"), blocks[1])
        assertEquals(MdBlock.Item(true, "1.", 0, "一"), blocks[2])
        assertEquals(MdBlock.Item(false, "•", 1, "子点"), blocks[5])
        assertEquals(MdBlock.Quote("引用\n续"), blocks[6])
        assertEquals(MdBlock.Rule, blocks[7])
        assertEquals(MdBlock.Table(listOf("题号", "答案"), listOf(listOf("1", "B"))), blocks[8])
        assertEquals(MdBlock.Code("code"), blocks[9])
    }

    @Test fun inlineMarkersAreRemovedAndStyled() {
        val s = MarkdownParser.inline("这是**重点**和*斜体*以及`code`，2*3 不变")
        assertEquals("这是重点和斜体以及code，2*3 不变", s.text)
        assertEquals(3, s.spanStyles.size)
    }
}
