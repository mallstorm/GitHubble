package app.githubble.mobile.agent;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class AgentBuildClassificationTest {
    private static final String SHA = "a".repeat(40);

    private static JSONObject report(String job, String step, String excerpt) throws Exception {
        return new JSONObject().put("run_id", 7L).put("repository", "owner/repo").put("branch", "main")
            .put("sha", SHA).put("status", "completed").put("conclusion", "failure")
            .put("failures", new JSONArray().put(new JSONObject().put("job", job).put("steps", new JSONArray().put(step))))
            .put("error_excerpt", excerpt);
    }

    @Test public void diagnosticsCarryFailureClass() throws Exception {
        JSONObject r = AgentBuildDiagnostics.classified(report("Android checks", "Fast unit tests and Debug/Release compile",
            "e: file:///x/A.kt:(1, 1): Unresolved reference: foo"), "e: file:///x/A.kt:(1, 1): Unresolved reference: foo");
        assertEquals("KOTLIN_JAVA_ERROR", r.getString("failure_class"));
        assertTrue(r.getBoolean("source_fixable"));
        assertFalse(r.getBoolean("transient"));
        assertFalse(r.getString("failure_signature").isEmpty());
    }

    @Test public void infrastructureFaultIsNotSourceFixable() throws Exception {
        JSONObject r = AgentBuildDiagnostics.classified(report("build", "Build", "Could not GET https://repo: Read timed out"),
            "Could not GET https://repo: Read timed out");
        assertEquals("INFRASTRUCTURE_FAILURE", r.getString("failure_class"));
        assertFalse(r.getBoolean("source_fixable"));
        assertTrue(r.getBoolean("transient"));
    }

    @Test public void repairTicketRefusesEnvironmentFaults() throws Exception {
        String[] excerpts = {"Resource not accessible by integration", "API rate limit exceeded",
            "The runner has received a shutdown signal", "keystore was tampered with"};
        for (String e : excerpts) {
            try {
                AgentBuildRepairTicket.fromReport(report("build", "Build", e), "owner/repo", "main", SHA);
                fail("must refuse: " + e);
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("не связан с исходным кодом"));
            }
        }
    }

    @Test public void repairTicketStillAcceptsRealCodeFailures() throws Exception {
        AgentBuildRepairTicket t = AgentBuildRepairTicket.fromReport(
            report("build", "Build", "e: file:///x/A.kt:(1, 1): Unresolved reference: foo"), "owner/repo", "main", SHA);
        assertEquals(7L, t.runId);
    }

    @Test public void unclassifiableFailureStillAllowsUserRequestedRepair() throws Exception {
        AgentBuildRepairTicket.fromReport(report("build", "Build", "something odd happened"), "owner/repo", "main", SHA);
    }

    @Test public void newToolsAreCatalogued() {
        assertTrue(GitHubAgentOperations.READS.contains("verify_build"));
        assertTrue(GitHubAgentOperations.READS.contains("build_artifacts"));
        assertTrue(GitHubAgentOperations.WRITES.contains("comment_issue"));
        assertTrue(GitHubAgentOperations.WRITES.contains("update_pull_request"));
        assertTrue(GitHubAgentOperations.READ_CATALOG.contains("verify_build"));
        assertTrue(GitHubAgentOperations.WRITE_CATALOG.contains("comment_issue"));
        assertFalse(GitHubAgentOperations.sensitive("comment_issue"));
    }
}
