package app.githubble.mobile.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPolicyTest {
    @Test
    fun explicitRememberAndForgetCommandsAreDetected() {
        assertEquals("я предпочитаю компактный интерфейс", MemoryPolicy.explicitRememberText(
            "Запомни: я предпочитаю компактный интерфейс"
        ))
        assertEquals("компактный интерфейс", MemoryPolicy.explicitForgetTarget(
            "Забудь компактный интерфейс"
        ))
        assertTrue(MemoryPolicy.isForgetAll("Забудь всю память"))
        assertFalse(MemoryPolicy.isForgetAll("Забудь старый проект"))
    }

    @Test
    fun parserKeepsDurableFactsAndDropsSecrets() {
        val parsed = MemoryPolicy.parseExtraction(
            """
            {
              "memories": [
                {
                  "kind": "preference",
                  "scope": "global",
                  "key": "ui-density",
                  "content": "Пользователь предпочитает компактный интерфейс.",
                  "keywords": ["интерфейс", "компактный"],
                  "importance": 92,
                  "confidence": 99
                },
                {
                  "kind": "fact",
                  "scope": "global",
                  "key": "secret",
                  "content": "api_key = sk-1234567890abcdefghijklmnop",
                  "keywords": ["api"],
                  "importance": 100,
                  "confidence": 100
                }
              ],
              "forget": ["старый DNS"]
            }
            """.trimIndent()
        )
        assertEquals(1, parsed.memories.size)
        assertEquals(MemoryKind.PREFERENCE, parsed.memories.single().kind)
        assertEquals("старый DNS", parsed.forget.single())
    }

    @Test
    fun projectMemoryWinsForRelevantProjectQuery() {
        val now = System.currentTimeMillis()
        val global = MemoryItemEntity(
            id = 1,
            scopeType = MemoryScope.GLOBAL,
            scopeId = 0,
            kind = MemoryKind.FACT,
            normalizedKey = "fact:apk-build",
            content = "Производственная APK сборка подписывается release key.",
            keywords = "apk сборка release",
            importance = 75,
            confidence = 95,
            updatedAt = now
        )
        val project = MemoryItemEntity(
            id = 2,
            scopeType = MemoryScope.PROJECT,
            scopeId = 42,
            kind = MemoryKind.DECISION,
            normalizedKey = "decision:apk-build",
            content = "В проекте OmniChat production APK собирается после unit tests.",
            keywords = "omnichat apk сборка production tests",
            importance = 75,
            confidence = 95,
            updatedAt = now
        )
        val query = "Как мы собираем APK OmniChat?"
        assertTrue(
            MemoryPolicy.score(project, query, projectId = 42, now = now) >
                MemoryPolicy.score(global, query, projectId = 42, now = now)
        )
    }

    @Test
    fun irrelevantEpisodeDoesNotPolluteRecall() {
        val item = MemoryItemEntity(
            id = 3,
            scopeType = MemoryScope.GLOBAL,
            kind = MemoryKind.EPISODE,
            normalizedKey = "episode:1:1",
            content = "Обсуждали рецепт гранолы и температуру молока.",
            keywords = "гранола молоко рецепт",
            importance = 50,
            confidence = 80
        )
        assertTrue(MemoryPolicy.score(item, "архитектура Android проекта", null) < 0)
    }

    @Test
    fun vagueContinueRequestCanRecallRecentEpisode() {
        val now = System.currentTimeMillis()
        val item = MemoryItemEntity(
            id = 4,
            scopeType = MemoryScope.GLOBAL,
            kind = MemoryKind.EPISODE,
            normalizedKey = "episode:88:900",
            content = "Старая беседа о реализации IPTV Checker и незавершённой проверке сборки.",
            keywords = "iptv checker сборка",
            importance = 46,
            confidence = 85,
            updatedAt = now - 60_000
        )
        assertTrue(MemoryPolicy.score(item, "Продолжи с места, где остановились", null, now) >= 0)
    }

    @Test
    fun secretRedactionRemovesCredentialValue() {
        val redacted = MemoryPolicy.redactSecrets("token = abcdefghijklmnop123456 and text")
        assertTrue(redacted.contains("[секрет скрыт]"))
        assertFalse(redacted.contains("abcdefghijklmnop123456"))
    }
}

