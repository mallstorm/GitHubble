package app.githubble.mobile.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectIndexTest {
    private val identity = "test-project-index"
    private val files = linkedMapOf(
        "src/Main.kt" to """
            class Demo {
                fun loadData(): String = "ok"
                val count = 1
            }
        """.trimIndent(),
        "src/Helper.kt" to """
            fun helper() {
                val value = Demo().loadData()
                println(value)
            }
        """.trimIndent()
    )

    private val stat: (String) -> ProjectIndexFileStat = { path ->
        ProjectIndexFileStat(files[path]?.length?.toLong() ?: -1L, 100L)
    }
    private val read: (String) -> String = { path -> files.getValue(path) }

    @After
    fun cleanup() {
        ProjectIndexRegistry.invalidate(identity)
    }

    @Test
    fun outlineReturnsSymbolsFromOneFile() {
        val result = ProjectIndexRegistry.outline(
            identity, "src/Main.kt", files.keys.toList(), stat, read
        )

        assertTrue(result.symbols.any { it.kind == "class" && it.name == "Demo" })
        assertTrue(result.symbols.any { it.kind == "function" && it.name == "loadData" })
        assertTrue(result.symbols.all { it.path == "src/Main.kt" })
    }

    @Test
    fun usagesFindDefinitionAndCallSite() {
        val result = ProjectIndexRegistry.usages(
            identity, "loadData", files.keys.toList(), stat, read
        )

        assertTrue(result.usages.size >= 2)
        assertTrue(result.usages.any { it.path == "src/Main.kt" && it.isDefinition })
        assertTrue(result.usages.any { it.path == "src/Helper.kt" && !it.isDefinition })
    }

    @Test
    fun usagesIgnoreCommentsAndStrings() {
        val localFiles = linkedMapOf(
            "src/A.kt" to "class Demo",
            "src/B.kt" to """
                fun use() {
                    val item = Demo()
                    val text = "Demo"
                    // Demo must not count
                }
            """.trimIndent()
        )
        val localStat: (String) -> ProjectIndexFileStat = { path ->
            ProjectIndexFileStat(localFiles[path]?.length?.toLong() ?: -1L, 200L)
        }
        val localRead: (String) -> String = { path -> localFiles.getValue(path) }

        val result = ProjectIndexRegistry.usages(
            identity + "-lexical",
            "Demo",
            localFiles.keys.toList(),
            localStat,
            localRead
        )

        assertEquals(2, result.usages.size)
        assertTrue(result.usages.none { it.preview.contains("must not count") })
        ProjectIndexRegistry.invalidate(identity + "-lexical")
    }

    @Test
    fun completionPrefersMatchingProjectSymbol() {
        val result = ProjectIndexRegistry.completions(
            identity, "loa", "src/Helper.kt", files.keys.toList(), stat, read
        )

        assertEquals("loadData", result.items.first().label)
    }

    @Test
    fun diagnosticsCatchMergeMarkersAndUnclosedDelimiter() {
        val diagnostics = ProjectIndexRegistry.diagnostics(
            "src/Broken.kt",
            """
                fun broken() {
                    val value = (1 + 2
                <<<<<<< HEAD
            """.trimIndent()
        )

        assertTrue(diagnostics.any { it.message.contains("merge conflict") })
        assertTrue(diagnostics.any { it.message.contains("Нет закрывающего символа") })
    }
}

