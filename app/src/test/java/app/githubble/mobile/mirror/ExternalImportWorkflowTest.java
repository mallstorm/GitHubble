package app.githubble.mobile.mirror;

import static org.junit.Assert.*;
import org.junit.Test;

public final class ExternalImportWorkflowTest {
    @Test public void acceptsPublicRepositoryIdentifiersAndSafeDestinationNames() {
        ExternalRepositoryImport.validateSource("morluto/rea");
        ExternalRepositoryImport.validateSource("some-owner/Sample.Project");
        ExternalRepositoryImport.validateName("REA-independent");
        for (String candidate : new String[]{"x", "../anything", "github.com/a/b",
                "a/b && echo token", "a/repo;curl", "a/..", "a/"}) {
            try {
                ExternalRepositoryImport.validateSource(candidate);
                fail("Unsafe source accepted: "+candidate);
            } catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void workflowIsDestinationScopedAndCopiesGitHistory() {
        String yaml = ExternalImportWorkflow.yaml();
        assertTrue(yaml.contains("workflow_dispatch:"));
        assertTrue(yaml.contains("permissions:\n  contents: write"));
        assertTrue(yaml.contains("git clone --bare"));
        assertTrue(yaml.contains("git lfs fetch --all origin"));
        assertTrue(yaml.contains("git lfs push --all destination"));
        assertTrue(yaml.contains("git push --atomic --force destination"));
        assertTrue(yaml.contains("git ls-remote --heads"));
        assertTrue(yaml.contains("SOURCE_SHA"));
        assertTrue(yaml.contains("BOOTSTRAP_SHA"));
        assertTrue(yaml.contains("Custom .lfsconfig requires manual review"));
        assertFalse(yaml.contains("git push --mirror"));
        assertFalse(yaml.contains("secrets.DESTINATION_TOKEN"));
        assertFalse(yaml.contains("/forks"));
        assertTrue(ExternalImportWorkflow.PATH.startsWith(".github/workflows/"));
    }
}
