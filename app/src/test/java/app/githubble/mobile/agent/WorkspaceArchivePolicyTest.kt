package app.githubble.mobile.agent

import app.githubble.mobile.network.ApiFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceArchivePolicyTest {
    @Test
    fun projectArchiveExcludesCachesPreviousExportsAndSecrets() {
        assertTrue(WorkspaceArchivePolicy.isExcludedFromProjectArchive(".git/config"))
        assertTrue(WorkspaceArchivePolicy.isExcludedFromProjectArchive("build/outputs/app.apk"))
        assertTrue(WorkspaceArchivePolicy.isExcludedFromProjectArchive("node_modules/pkg/index.js"))
        assertTrue(WorkspaceArchivePolicy.isExcludedFromProjectArchive("exports/project.zip"))
        assertTrue(WorkspaceArchivePolicy.isExcludedFromProjectArchive(".env"))
        assertTrue(WorkspaceArchivePolicy.isExcludedFromProjectArchive("config/.env.production"))
        assertTrue(WorkspaceArchivePolicy.isExcludedFromProjectArchive("release/signing.jks"))
        assertTrue(WorkspaceArchivePolicy.isExcludedFromProjectArchive("credentials.json"))
        assertFalse(WorkspaceArchivePolicy.isExcludedFromProjectArchive(".env.example"))
        assertFalse(WorkspaceArchivePolicy.isExcludedFromProjectArchive("src/main.js"))
    }

    @Test
    fun archiveNameIsSafeAndZipSuffixIsNotDuplicated() {
        assertEquals("IPTV Checker", WorkspaceArchivePolicy.sanitizeArchiveBaseName(" IPTV Checker.zip "))
        assertEquals("IPTV_Checker", WorkspaceArchivePolicy.sanitizeArchiveBaseName("IPTV/Checker"))
        assertEquals("project", WorkspaceArchivePolicy.sanitizeArchiveBaseName("../"))
    }

    @Test
    fun zipEntryValidationBlocksTraversalAndAbsolutePaths() {
        assertEquals("src/main.kt", WorkspaceArchivePolicy.normalizeArchiveEntry("src\\main.kt"))
        assertRejected("../secret.txt")
        assertRejected("src/../../secret.txt")
        assertRejected("/etc/passwd")
        assertRejected("C:/Windows/file.txt")
    }

    @Test
    fun detectsExplicitArchiveAndBuildPackageRequests() {
        assertTrue(WorkspaceArchivePolicy.requestsArchive("Создай IPTV Checker и запакуй в zip"))
        assertTrue(WorkspaceArchivePolicy.requestsArchive("Сделай архив проекта"))
        assertTrue(WorkspaceArchivePolicy.requestsArchive("pack the project as an archive"))
        assertFalse(WorkspaceArchivePolicy.requestsArchive("Создай полноценный IPTV Checker"))

        assertTrue(WorkspaceArchivePolicy.requestsProjectArchive("Собери проект IPTV Checker и запакуй в ZIP"))
        assertTrue(WorkspaceArchivePolicy.requestsProjectArchive("Create the website and pack it as an archive"))
        assertFalse(WorkspaceArchivePolicy.requestsProjectArchive("Упакуй только src и README в zip"))
    }

    private fun assertRejected(path: String) {
        var rejected = false
        try {
            WorkspaceArchivePolicy.normalizeArchiveEntry(path)
        } catch (_: ApiFailure) {
            rejected = true
        }
        assertTrue("Expected path to be rejected: $path", rejected)
    }
}

