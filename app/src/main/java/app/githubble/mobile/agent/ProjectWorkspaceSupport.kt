package app.githubble.mobile.agent

object ProjectWorkspaceSupport {
    const val STORAGE_INTERNAL = "internal"
    const val STORAGE_SAF = "saf"
    const val STATE_CREATED = "CREATED"
    const val STATE_PLANNING = "PLANNING"
    const val STATE_ARCHITECTURE_SELECTED = "ARCHITECTURE_SELECTED"
    const val STATE_DEVELOPMENT = "DEVELOPMENT"

    fun stateLabel(state: String): String = when (state) {
        STATE_CREATED -> "Создан"
        STATE_PLANNING -> "Планирование проекта"
        STATE_ARCHITECTURE_SELECTED -> "Архитектура выбрана"
        STATE_DEVELOPMENT -> "Разработка"
        STATE_READY -> "Готов"
        else -> state
    }

    const val STATE_READY = "READY"

    fun workspaceKey(uuid: String, id: Long): String =
        uuid.trim().takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,96}")) } ?: "legacy-$id"

    /**
     * Detect a request that should become a persistent Project Workspace, not just a one-off answer.
     * The returned title is provisional; after the user selects an architecture the model creates
     * the canonical name and full project instructions.
     */
    fun projectCreationName(text: String): String? {
        val source = text.trim()
        if (source.length !in 5..4_000) return null
        val lower = source.lowercase()
        val create = Regex(
            """(?iu)(?<![\p{L}\p{N}_])(?:создай|создать|создаём|создаем|сделай|разработай|разработать|реализуй|реализовать|собери|собрать)(?![\p{L}\p{N}_])"""
        ).find(source) ?: return null

        val productLike = Regex(
            """(?iu)(?<![\p{L}\p{N}_])(?:проект|приложени[ея]|app|application|сайт|web[- ]?app|бот|плеер|проигрывател[ья]|клиент|сервис|программ[ау]|игр[ау]|ide|cms|crm|api|сервер)(?![\p{L}\p{N}_])"""
        ).containsMatchIn(source)
        if (!productLike) return null

        // Keep ordinary image/icon generation out of Project mode (for example "создай иконку приложения").
        val visualAssetOnly = Regex(
            """(?iu)(?<![\p{L}\p{N}_])(?:иконк[ауи]|картинк[ауи]|изображени[ея]|логотип|баннер)(?![\p{L}\p{N}_])"""
        ).containsMatchIn(source) && !Regex("""(?iu)(?<![\p{L}\p{N}_])проект(?![\p{L}\p{N}_])""").containsMatchIn(source)
        if (visualAssetOnly && !Regex(
                """(?iu)(?<![\p{L}\p{N}_])(?:сайт|плеер|бот|сервис|программ[ау]|игр[ау]|ide|cms|crm|api)(?![\p{L}\p{N}_])"""
            ).containsMatchIn(source)
        ) return null

        var tail = source.substring(create.range.last + 1).trim().trimStart(':', '-', '—', ' ')
        tail = tail.replace(
            Regex("""(?iu)^(?:мне\s+|новый\s+)?(?:проект\s+)?(?:под\s+названием|с\s+названием|назови(?:\s+его)?|условно)\s+"""),
            ""
        )
        tail = tail.replace(Regex("""(?iu)^(?:новый\s+)?проект\s*[:\-—]?\s*"""), "")
        val candidate = tail.substringBefore('\n').substringBefore('.').substringBefore(',')
            .trim('«', '»', '"', '\'', ' ', ':', '-', '—')
            .replace(Regex("\\s+"), " ")
            .take(80)
            .trim()
        return candidate.ifBlank {
            when {
                "android" in lower || "андроид" in lower -> "Новый Android проект"
                "сайт" in lower || "web" in lower -> "Новый Web проект"
                else -> "Новый проект"
            }
        }
    }

    fun projectTypeHint(text: String): String {
        val q = text.lowercase()
        return when {
            "android" in q || "андроид" in q -> "android"
            "windows" in q || "виндов" in q -> "windows"
            "сайт" in q || "web" in q || "frontend" in q -> "web"
            "python" in q || "питон" in q -> "python"
            "ios" in q || "iphone" in q -> "ios"
            "backend" in q || "сервер" in q || "api" in q -> "backend"
            else -> ""
        }
    }

    fun architectureSelectionIntent(text: String): Boolean {
        val q = text.trim().lowercase()
        return Regex(
            """(выбираю|бер[её]м|утверждаю|делаем|подходит|останавливаюсь\s+на|вариант\s*[abcабв123]|перв(ый|ую)|втор(ой|ую)|трет(ий|ью))"""
        ).containsMatchIn(q)
    }
}

