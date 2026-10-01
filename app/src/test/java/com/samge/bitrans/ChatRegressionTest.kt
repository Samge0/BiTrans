package com.samge.bitrans

import com.samge.bitrans.ui.MdNode
import com.samge.bitrans.ui.MdSpan
import com.samge.bitrans.ui.MiniMarkdown
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Self-regression tests for v1.4.x chat features (run before every release):
 *  - SSE delta parsing (the exact shape vLLM emits)
 *  - MiniMarkdown parsing (headings/bold/lists/code)
 */
class ChatRegressionTest {

    // ---- SSE parsing (mirrors LlmEngine.chatStream loop) ----

    private fun parseSseLines(lines: List<String>): String {
        val full = StringBuilder()
        for (line in lines) {
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload == "[DONE]" || payload.isEmpty()) continue
            runCatching {
                val delta = JSONObject(payload)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .optJSONObject("delta")
                    ?.optString("content") ?: ""
                if (delta.isNotEmpty()) full.append(delta)
            }
        }
        return full.toString()
    }

    @Test
    fun sse_accumulatesContentDeltas() {
        val lines = listOf(
            """data: {"choices":[{"delta":{"role":"assistant","content":""}}]}""",
            """data: {"choices":[{"delta":{"content":"今天"}}]}""",
            """data: {"choices":[{"delta":{"content":"天气"}}]}""",
            """data: {"choices":[{"delta":{"content":"很好"}}]}""",
            """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}""",
            """data: [DONE]""",
        )
        assertEquals("今天天气很好", parseSseLines(lines))
    }

    @Test
    fun sse_skipsRoleOnlyAndEmptyDeltas() {
        val lines = listOf(
            """data: {"choices":[{"delta":{"role":"assistant"}}]}""",
            ": keep-alive comment",
            """data: {"choices":[{"delta":{"content":"OK"}}]}""",
        )
        assertEquals("OK", parseSseLines(lines))
    }

    // ---- MiniMarkdown ----

    @Test
    fun md_headingAndBold() {
        val nodes = MiniMarkdown.parse("## 主题\n这是**重点**内容")
        assertEquals(2, nodes.size)
        val h = nodes[0] as MdNode.Heading
        assertEquals(2, h.level)
        assertEquals("主题", h.text)
        val p = nodes[1] as MdNode.Paragraph
        assertEquals(
            listOf(MdSpan.Plain("这是"), MdSpan.Bold("重点"), MdSpan.Plain("内容")),
            p.spans,
        )
    }

    @Test
    fun md_bulletLists() {
        val nodes = MiniMarkdown.parse("- 第一条\n- 第二条\n1. 有序")
        assertEquals(3, nodes.size)
        assertTrue(nodes[0] is MdNode.Bullet && !(nodes[0] as MdNode.Bullet).ordered)
        assertTrue(nodes[2] is MdNode.Bullet && (nodes[2] as MdNode.Bullet).ordered)
    }

    @Test
    fun md_codeFenceAndInlineCode() {
        val nodes = MiniMarkdown.parse("行内 `code` 示例\n```\nblock code\n```")
        val p = nodes[0] as MdNode.Paragraph
        assertTrue(p.spans.any { it is MdSpan.Code && it.text == "code" })
        val block = nodes[1] as MdNode.Code
        assertEquals("block code", block.text)
    }

    @Test
    fun md_plainTextPassthrough() {
        val nodes = MiniMarkdown.parse("普通文本没有标记")
        assertEquals(1, nodes.size)
        val p = nodes[0] as MdNode.Paragraph
        assertEquals(listOf(MdSpan.Plain("普通文本没有标记")), p.spans)
    }
}
