package com.samge.bitrans.ui

/**
 * Minimal markdown model for chat bubbles (zero-dependency).
 * Supports: headings (#..###), bold **x**, italic *x*, inline code `x`,
 * unordered lists (- / *), ordered lists (1.), code fences, blockquotes.
 */
sealed interface MdNode {
    data class Heading(val level: Int, val text: String) : MdNode
    data class Paragraph(val spans: List<MdSpan>) : MdNode
    data class Bullet(val spans: List<MdSpan>, val ordered: Boolean, val index: Int) : MdNode
    data class Quote(val spans: List<MdSpan>) : MdNode
    data class Code(val text: String) : MdNode
}

sealed interface MdSpan {
    data class Plain(val text: String) : MdSpan
    data class Bold(val text: String) : MdSpan
    data class Italic(val text: String) : MdSpan
    data class Code(val text: String) : MdSpan
}

object MiniMarkdown {

    fun parse(src: String): List<MdNode> {
        val out = ArrayList<MdNode>()
        val lines = src.lines()
        var i = 0
        val paragraph = StringBuilder()

        fun flushParagraph() {
            val t = paragraph.toString().trim()
            if (t.isNotEmpty()) out.add(MdNode.Paragraph(parseSpans(t)))
            paragraph.setLength(0)
        }

        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trimEnd()
            when {
                line.startsWith("```") -> {
                    flushParagraph()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimEnd().startsWith("```")) {
                        code.appendLine(lines[i]); i++
                    }
                    i++ // skip closing fence
                    out.add(MdNode.Code(code.toString().trimEnd()))
                }
                Regex("^(#{1,6})\\s+(.*)$").matchEntire(line)?.let { m ->
                    flushParagraph()
                    out.add(MdNode.Heading(m.groupValues[1].length, m.groupValues[2].trim()))
                } != null -> Unit
                Regex("^\\s*[-*+]\\s+(.*)$").matchEntire(line)?.let { m ->
                    flushParagraph()
                    out.add(MdNode.Bullet(parseSpans(m.groupValues[1].trim()), ordered = false, index = 0))
                } != null -> Unit
                Regex("^\\s*(\\d+)[.)]\\s+(.*)$").matchEntire(line)?.let { m ->
                    flushParagraph()
                    out.add(
                        MdNode.Bullet(
                            parseSpans(m.groupValues[2].trim()),
                            ordered = true,
                            index = m.groupValues[1].toIntOrNull() ?: 0,
                        )
                    )
                } != null -> Unit
                line.startsWith("> ") || line == ">" -> {
                    flushParagraph()
                    val q = StringBuilder()
                    while (i < lines.size && (lines[i].trimEnd().startsWith("> ") || lines[i].trimEnd() == ">")) {
                        q.append(lines[i].trimEnd().removePrefix(">").trim()).append(' ')
                        i++
                    }
                    out.add(MdNode.Quote(parseSpans(q.toString().trim())))
                    continue
                }
                line.isBlank() -> flushParagraph()
                else -> paragraph.append(line.trimEnd()).append(' ')
            }
            i++
        }
        flushParagraph()
        return out
    }

    /** inline spans: `code`, **bold**, *italic* (bold before italic) */
    fun parseSpans(text: String): List<MdSpan> {
        val spans = ArrayList<MdSpan>()
        var rest = text
        val token = Regex("`([^`]+)`|\\*\\*(.+?)\\*\\*|\\*([^*]+)\\*")
        while (true) {
            val m = token.find(rest) ?: break
            if (m.range.first > 0) spans.add(MdSpan.Plain(rest.substring(0, m.range.first)))
            val g = m.groupValues
            when {
                g[1].isNotEmpty() -> spans.add(MdSpan.Code(g[1]))
                g[2].isNotEmpty() -> spans.add(MdSpan.Bold(g[2]))
                else -> spans.add(MdSpan.Italic(g[3]))
            }
            rest = rest.substring(m.range.last + 1)
        }
        if (rest.isNotEmpty()) spans.add(MdSpan.Plain(rest))
        return spans
    }
}
