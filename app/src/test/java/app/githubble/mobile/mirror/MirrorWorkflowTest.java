package app.githubble.mobile.mirror;

import static org.junit.Assert.*;
import org.junit.Test;

public class MirrorWorkflowTest {
    @Test public void usesRepositoryScopedRunnerTokenOnly() {
        String y=MirrorWorkflow.yaml();
        assertTrue(y.contains("permissions:\n  contents: write"));
        assertTrue(y.contains("secrets.GITHUB_TOKEN"));
        assertFalse(y.contains("source_token:"));
        assertFalse(y.contains("destination_token:"));
        assertTrue(y.contains("cancel-in-progress: false"));
    }
    @Test public void copiesCompleteHistoryAndRefTypesInCloud() {
        String y=MirrorWorkflow.yaml();
        assertTrue(y.contains("git clone --bare"));
        assertTrue(y.contains("git fetch --force"));
        assertTrue(y.contains("git lfs fetch --all"));
        assertTrue(y.contains("git lfs push --all"));
        assertTrue(y.contains("git push --force --prune"));
        assertTrue(y.contains("refs/heads/*"));
        assertTrue(y.contains("refs/tags/*"));
        assertTrue(y.contains("source == target"));
    }
}
