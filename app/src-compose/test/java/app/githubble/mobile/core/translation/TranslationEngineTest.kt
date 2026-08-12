package app.githubble.mobile.core.translation

import app.githubble.mobile.core.markdown.MarkdownBlock
import app.githubble.mobile.core.markdown.MarkdownDocument
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationEngineTest {
    @Test fun codeBlockIsNeverSentToTranslator() = runBlocking {
        val provider = object : TranslationProvider {
            override suspend fun translate(text: String, sourceLanguage: String?, targetLanguage: String) = "RU:$text"
        }
        val source = MarkdownDocument(listOf(MarkdownBlock.Paragraph("Install package"), MarkdownBlock.Code("bash", "npm install pkg")))
        val translated = TranslationEngine(provider = provider).translate(source)
        assertEquals("RU:Install package", (translated.blocks[0] as MarkdownBlock.Paragraph).text)
        assertEquals("npm install pkg", (translated.blocks[1] as MarkdownBlock.Code).code)
    }
}
