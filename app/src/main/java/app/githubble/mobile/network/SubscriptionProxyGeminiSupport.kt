package app.githubble.mobile.network

import java.security.MessageDigest
import java.util.UUID

object SubscriptionProxyGeminiSupport {
    val VERTEX_MUSIC_MODELS = listOf(
        "lyria-3.5",
        "lyria-3-clip-preview"
    )

    fun stableSessionId(seed: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(seed.toByteArray(Charsets.UTF_8))
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (digest[i].toLong() and 0xffL)
        }
        value = value and Long.MAX_VALUE
        if (value == 0L) value = 1L
        return "-$value"
    }

    /** Capability check: the model can generate or edit images. */
    fun isImageModel(model: String): Boolean =
        model.contains("image", ignoreCase = true)

    fun supportsImageGeneration(model: String): Boolean = isImageModel(model)

    /**
     * Gemini image models that can also participate in an ordinary text conversation.
     * Selecting one of these models must not itself trigger image generation.
     */
    fun isConversationalImageModel(model: String): Boolean =
        model.startsWith("gemini-3.1-flash-image", ignoreCase = true) ||
            model.startsWith("gemini-3.1-flash-lite-image", ignoreCase = true) ||
            model.startsWith("gemini-3-pro-image", ignoreCase = true) ||
            model.startsWith("gemini-2.5-flash-image", ignoreCase = true)

    /** Only legacy image-only models may force image mode just because they are selected. */
    fun forcesImageGeneration(model: String): Boolean =
        isImageModel(model) && !isConversationalImageModel(model)

    fun isMusicModel(model: String): Boolean =
        VERTEX_MUSIC_MODELS.any { it.equals(model, ignoreCase = true) } ||
            model.equals("lyria-3-pro-preview", ignoreCase = true)

    fun canonicalMusicModel(model: String): String =
        if (model.equals("lyria-3-pro-preview", ignoreCase = true)) "lyria-3.5" else model

    fun wantsMusicGeneration(text: String): Boolean {
        val q = text.trim().lowercase()
        if (q.isBlank()) return false
        val music = Regex(
            """(музык|песн|трек|композиц|мелоди|саундтрек|вокал|инструментал|бит\b|lyrics|лирик|music|song|track|melody|soundtrack|instrumental|beat\b)"""
        ).containsMatchIn(q)
        val create = Regex(
            """(сгенер|созда|сделай|сделать|напиши|сочини|скомпоз|генерир|generate|create|make|write|compose|produce)"""
        ).containsMatchIn(q)
        return music && create
    }

    fun wantsVideoGeneration(text: String): Boolean {
        val q = text.trim().lowercase()
        if (q.isBlank()) return false
        val video = Regex("""(видео|ролик|клип|анимац|video|movie|clip|animate|animation)""")
            .containsMatchIn(q)
        val create = Regex("""(сгенер|созда|сделай|сделать|генерир|оживи|анимиру|generate|create|make|animate)""")
            .containsMatchIn(q)
        return video && create
    }

    fun isVeoModel(model: String): Boolean =
        model.contains("veo", ignoreCase = true) ||
            model.contains("video", ignoreCase = true)

    fun videoPromptInstruction(originalRequest: String, referenceAttached: Boolean): String = buildString {
        append(
            "The user asked to generate a video, but this client cannot currently invoke Veo through the " +
                "connected Google OAuth transport. Do NOT refuse and do NOT recommend external services. " +
                "Instead, transform the user's request into one polished, production-ready VIDEO GENERATION PROMPT. "
        )
        if (referenceAttached) {
            append(
                "A visual reference is attached. Analyze it and preserve the important subject identity, " +
                    "composition, colors and scene details unless the user asked to change them. "
            )
        }
        append(
            "Include: subject and action, environment, camera movement and lens/shot, composition, lighting, " +
                "motion, atmosphere, visual style, temporal progression, sound/ambience if appropriate, " +
                "and negative constraints for common artifacts. Prefer concise natural language suitable for " +
                "Veo-class video models. If the user did not specify duration/aspect ratio/resolution, choose " +
                "reasonable defaults but state them. Return only the final prompt, without apologies, caveats, " +
                "tutorials, lists of other services, or statements about being unable to generate video.\n\n" +
                "USER VIDEO REQUEST:\n" + originalRequest.trim()
        )
    }

    fun wantsImageGeneration(text: String): Boolean {
        val q = text.trim().lowercase()
        if (q.isBlank()) return false

        val visual = Regex(
            """(изображ|картин|фото|рисунк|постер|обложк|иллюстрац|портрет|логотип|иконк|аватар|макет|image|picture|photo|poster|illustration|portrait|logo|icon|avatar|mockup)"""
        ).containsMatchIn(q)
        val create = Regex(
            """(сгенер|нарис|созда|сделай|сделать|перерис|измени|отредакт|генерир|generate|draw|create|make|edit|redraw|render|paint)"""
        ).containsMatchIn(q)
        if (visual && create) return true

        // Short edit follow-ups stay in the same conversational image session.
        val visualProperty = Regex(
            """(фон|цвет|освещ|поз|лиц|волос|одежд|кадр|обрез|композиц|соотношен|разрешен|портретн|ландшафт|альбом|квадрат|9\s*[:xх]\s*16|16\s*[:xх]\s*9|1\s*[:xх]\s*1|background|color|lighting|pose|face|hair|clothes|crop|composition|aspect\s*ratio|resolution|portrait|landscape)"""
        ).containsMatchIn(q)
        val editAction = Regex(
            """(измени|сделай|замени|убери|удали|добав|остав|увелич|уменьш|поверн|обреж|edit|change|replace|remove|add|keep|resize|rotate|crop)"""
        ).containsMatchIn(q)
        if (visualProperty && editAction) return true

        return Regex("""(нарис|перерис|отрис|render\b|draw\b|paint\b|sketch\b)""")
            .containsMatchIn(q)
    }


    /**
     * If an image-capable Gemini model is explicitly selected, short commands such as
     * "сгенерируй бриллиант" should enter image generation even without the word "картинка".
     * Keep explicit text/code/project/music/audio/video creation in conversational/media routing.
     */
    fun wantsImageGenerationForSelectedImageModel(text: String): Boolean {
        val q = text.trim().lowercase()
        if (q.isBlank()) return false
        val create = Regex(
            """(сгенер|генерир|нарис|отрис|изобраз|созда|сделай|сделать|render\b|generate\b|draw\b|paint\b|create\b|make\b)"""
        ).containsMatchIn(q)
        if (!create) return false
        val clearlyNotImage = Regex(
            """(код|программ|функц|класс|скрипт|json|sql|regex|текст|письм|стать|резюме|таблиц|промпт|парол|сказк|рассказ|истори|стих|поэм|план\b|проект|репозитор|файл|папк|приложен|сайт|бот\b|api\b|баз[ау]\s+данн|музык|песн|трек|аудио|голос|видео|ролик|анимац|code\b|program|function|class|script|text\b|email|article|story|poem|table|prompt|password|plan\b|project|repository|file\b|folder|app\b|website|bot\b|database|music|song|track|audio|voice|video|movie|animation)"""
        ).containsMatchIn(q)
        return !clearlyNotImage
    }

    fun preferredImageModel(models: List<String>): String? {
        val available = models.filter(::isImageModel)
        val preferred = listOf(
            "gemini-3.1-flash-image",
            "gemini-3.1-flash-image-preview",
            "gemini-3.1-flash-lite-image",
            "gemini-3.1-flash-lite-image-preview",
            "gemini-3-pro-image",
            "gemini-2.5-flash-image"
        )
        return preferred.firstNotNullOfOrNull { wanted ->
            available.firstOrNull { it.equals(wanted, ignoreCase = true) }
        } ?: available.firstOrNull()
    }

    fun preferredMusicModel(models: List<String>, request: String = ""): String {
        val available = models.filter(::isMusicModel)
        val q = request.lowercase()
        val wantsClip = Regex(
            """(30\s*(сек|секунд|seconds?)|коротк|превью|preview|loop|луп|clip|клип)"""
        ).containsMatchIn(q)
        val preferred = if (wantsClip) {
            listOf("lyria-3-clip-preview", "lyria-3.5")
        } else {
            listOf("lyria-3.5", "lyria-3-clip-preview")
        }
        return preferred.firstNotNullOfOrNull { wanted ->
            available.firstOrNull { it.equals(wanted, ignoreCase = true) }
        } ?: available.firstOrNull()?.let(::canonicalMusicModel)
            ?: if (wantsClip) "lyria-3-clip-preview" else "lyria-3.5"
    }

    fun preferredChatModel(models: List<String>, fallback: String): String {
        val candidates = models.filterNot(::isImageModel)
            .filterNot(::isVeoModel)
            .filterNot(::isMusicModel)
        val preferred = listOf(
            "gemini-3.1-flash",
            "gemini-3-flash",
            "gemini-2.5-flash",
            "gemini-2.5-pro"
        )
        return preferred.firstNotNullOfOrNull { wanted ->
            candidates.firstOrNull { it.equals(wanted, ignoreCase = true) }
        } ?: candidates.firstOrNull() ?: fallback
    }

    fun preferredSceneAnalyzerModel(models: List<String>, fallback: String): String {
        val candidates = models.filterNot(::isVeoModel)
        val preferred = listOf(
            "gemini-3.1-flash",
            "gemini-3-flash",
            "gemini-2.5-flash",
            "gemini-2.5-pro"
        )
        return preferred.firstNotNullOfOrNull { wanted ->
            candidates.firstOrNull { it.equals(wanted, ignoreCase = true) }
        } ?: candidates.firstOrNull { !isImageModel(it) }
            ?: candidates.firstOrNull()
            ?: fallback
    }

    fun imageRequestId(nowMs: Long = System.currentTimeMillis(), uuid: UUID = UUID.randomUUID()): String =
        "image_gen/$nowMs/$uuid/12"
}

