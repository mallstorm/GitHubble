package app.githubble.mobile.core.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {
    @Test fun parsesHeadingParagraphAndCode() {
        val source = """
            # Install
            Run this command:

            ```bash
            ./gradlew assembleDebug
            ```
        """.trimIndent()
        val blocks = MarkdownParser().parse(source).blocks
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Heading)
        assertTrue(blocks[2] is MarkdownBlock.Code)
        assertEquals("./gradlew assembleDebug", (blocks[2] as MarkdownBlock.Code).code)
    }
}
