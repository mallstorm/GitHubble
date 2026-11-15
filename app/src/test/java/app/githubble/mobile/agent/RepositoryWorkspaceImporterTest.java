package app.githubble.mobile.agent;

import org.junit.Test;
import static org.junit.Assert.*;

public final class RepositoryWorkspaceImporterTest {
    @Test public void onlyStandaloneSlashCommandsAreNormalized(){
        assertTrue(RepositoryWorkspaceImporter.isImportIntent("/workspace import mallstorm/AI-Endpoint-Checker"));
        assertTrue(RepositoryWorkspaceImporter.isImportIntent(" /workspace import "));
        assertFalse(RepositoryWorkspaceImporter.isImportIntent("Склонируй репозиторий GitHub в локальный Workspace"));
        assertFalse(RepositoryWorkspaceImporter.isImportIntent("Ai Endpoin берем создавай временный воркспейс и сливай туда репозиторий для локальной работы"));
        assertFalse(RepositoryWorkspaceImporter.isImportIntent("Покажи мои репозитории"));
        assertFalse(RepositoryWorkspaceImporter.isImportIntent("Объясни команду /workspace import"));
        assertFalse(RepositoryWorkspaceImporter.isImportIntent("Не выполняй /workspace import mallstorm/private"));
    }
    @Test public void createRepositoryWithWorkspaceProhibitionNeverMeansImport(){
        String prompt="Создай в моём активном GitHub-аккаунте приватный репозиторий hubble-acceptance-80.\n"
            +"Используй create_repository и привяжи результат к текущему проекту.\n"
            +"Пока не создавай дополнительные файлы, Workspace или workflow. Не запускай сборку.";
        assertFalse(RepositoryWorkspaceImporter.isImportIntent(prompt));
        assertEquals(prompt,AgentController.intent(prompt));
        String noImport="Проверь репозиторий, ничего не импортируй в Workspace";
        assertFalse(RepositoryWorkspaceImporter.isImportIntent(noImport));assertEquals(noImport,AgentController.intent(noImport));
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
