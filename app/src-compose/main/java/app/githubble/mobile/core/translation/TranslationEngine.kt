package app.githubble.mobile.core.translation

import app.githubble.mobile.core.markdown.MarkdownBlock
import app.githubble.mobile.core.markdown.MarkdownDocument

interface TranslationProvider {
    suspend fun translate(text: String, sourceLanguage: String?, targetLanguage: String): String
}

class PassthroughTranslationProvider : TranslationProvider {
    override suspend fun translate(text: String, sourceLanguage: String?, targetLanguage: String) = text
}

class TranslationEngine(
    private val protector: TechnicalTokenProtector = TechnicalTokenProtector(),
    private val provider: TranslationProvider = PassthroughTranslationProvider()
) {
    suspend fun translateText(text: String, targetLanguage: String = "ru"): String {
        val protected = protector.protect(text)
        val translated = provider.translate(protected.text, null, targetLanguage)
        return protector.restore(translated, protected.tokens)
    }

    suspend fun translate(document: MarkdownDocument, targetLanguage: String = "ru"): MarkdownDocument {
        val translated = document.blocks.map { block ->
            when (block) {
                is MarkdownBlock.Heading -> block.copy(text = translateText(block.text, targetLanguage))
                is MarkdownBlock.Paragraph -> block.copy(text = translateText(block.text, targetLanguage))
                is MarkdownBlock.Bullet -> block.copy(text = translateText(block.text, targetLanguage))
                is MarkdownBlock.Code, MarkdownBlock.Divider -> block
            }
        }
        return MarkdownDocument(translated)
    }
}
