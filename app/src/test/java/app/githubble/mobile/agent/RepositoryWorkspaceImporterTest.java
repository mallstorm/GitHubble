package app.githubble.mobile.agent;

import org.junit.Test;
import static org.junit.Assert.*;

public final class RepositoryWorkspaceImporterTest {
    @Test public void naturalLanguageCloneRequestIsDispatchedLocally(){
        assertTrue(RepositoryWorkspaceImporter.isImportIntent(
            "Ai Endpoin берем создавай временный воркспейс и сливай туда репозиторий для локальной работы"));
        assertTrue(RepositoryWorkspaceImporter.isImportIntent(
            "/workspace import mallstorm/AI-Endpoint-Checker"));
        assertTrue(RepositoryWorkspaceImporter.isImportIntent(
            "Склонируй репозиторий GitHub в локальный Workspace"));
        assertFalse(RepositoryWorkspaceImporter.isImportIntent("Покажи мои репозитории"));
        assertFalse(RepositoryWorkspaceImporter.isImportIntent("Привет Git Agent"));
    }

    @Test public void importerRejectsDangerousPaths(){
        assertTrue(RepositoryWorkspaceImporter.allowedPath("src/main/java/App.java"));
        assertTrue(RepositoryWorkspaceImporter.allowedPath("README.md"));
        assertTrue(RepositoryWorkspaceImporter.allowedPath(".github/workflows/android.yml"));
        assertFalse(RepositoryWorkspaceImporter.allowedPath(".env"));
        assertFalse(RepositoryWorkspaceImporter.allowedPath(".env.local"));
        assertFalse(RepositoryWorkspaceImporter.allowedPath("credentials.json"));
        assertFalse(RepositoryWorkspaceImporter.allowedPath("secrets/cloud.txt"));
        assertFalse(RepositoryWorkspaceImporter.allowedPath("keys/private.pem"));
        assertFalse(RepositoryWorkspaceImporter.allowedPath("src/../../escape.txt"));
        assertFalse(RepositoryWorkspaceImporter.allowedPath(".git/config"));
    }
}
