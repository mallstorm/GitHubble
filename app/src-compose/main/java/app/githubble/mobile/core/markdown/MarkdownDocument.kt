package app.githubble.mobile.core.markdown

data class MarkdownDocument(val blocks: List<MarkdownBlock>)

sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Bullet(val text: String) : MarkdownBlock
    data class Code(val language: String?, val code: String) : MarkdownBlock
    data object Divider : MarkdownBlock
}

class MarkdownParser {
    fun parse(source: String): MarkdownDocument {
        val blocks = mutableListOf<MarkdownBlock>()
        val paragraph = mutableListOf<String>()
        var inCode = false
        var language: String? = null
        val code = StringBuilder()

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks += MarkdownBlock.Paragraph(paragraph.joinToString(" ").trim())
                paragraph.clear()
            }
        }

        source.replace("\r\n", "\n").lineSequence().forEach { line ->
            if (line.startsWith("```")) {
                if (inCode) {
                    blocks += MarkdownBlock.Code(language, code.toString().trimEnd())
                    code.clear(); language = null; inCode = false
                } else {
                    flushParagraph(); language = line.removePrefix("```").trim().ifBlank { null }; inCode = true
                }
                return@forEach
            }
            if (inCode) { code.appendLine(line); return@forEach }
            when {
                line.isBlank() -> flushParagraph()
                line.matches(Regex("^#{1,6}\\s+.*")) -> {
                    flushParagraph(); val level = line.takeWhile { it == '#' }.length
                    blocks += MarkdownBlock.Heading(level, line.drop(level).trim())
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    flushParagraph(); blocks += MarkdownBlock.Bullet(line.drop(2).trim())
                }
                line.trim() == "---" -> { flushParagraph(); blocks += MarkdownBlock.Divider }
                else -> paragraph += line.trim()
            }
        }
        if (inCode) blocks += MarkdownBlock.Code(language, code.toString()) else flushParagraph()
        return MarkdownDocument(blocks)
    }
}
