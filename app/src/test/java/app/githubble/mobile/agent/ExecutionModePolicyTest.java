package app.githubble.mobile.agent;

import static org.junit.Assert.*;

import app.githubble.mobile.agent.core.ExecutionMode;
import org.json.JSONObject;
import org.junit.Test;

/** Invariant: the legacy permission levels never allow what the execution-mode policy forbids. */
public class ExecutionModePolicyTest {
    @Test public void legacyPolicyIsNeverWeakerThanModePolicy() throws Exception {
        java.util.Set<String> tools = new java.util.HashSet<>();
        tools.addAll(GitHubAgentOperations.WRITES);
        for (int level = AgentPermissionPolicy.REVIEW; level <= AgentPermissionPolicy.GITHUB_AUTO; level++) {
            ExecutionMode mode = ExecutionMode.forLevel(level);
            for (String tool : tools) {
                ExecutionMode.Action action = GitHubAgentOperations.action(tool, new JSONObject());
                boolean modeNeeds = mode.decide(action, false, false) == ExecutionMode.Decision.NEED_APPROVAL;
                boolean legacyNeeds = AgentPermissionPolicy.needsReview(level, true, GitHubAgentOperations.sensitive(tool));
                assertTrue(tool + " @level " + level + ": mode requires approval but legacy policy does not",
                    legacyNeeds || !modeNeeds);
            }
        }
    }

    @Test public void classification() throws Exception {
        assertEquals(ExecutionMode.Action.READ, GitHubAgentOperations.action("verify_build", null));
        assertEquals(ExecutionMode.Action.MERGE_PROTECTED, GitHubAgentOperations.action("merge_pull_request", null));
        assertEquals(ExecutionMode.Action.BUILD, GitHubAgentOperations.action("dispatch_workflow", null));
        assertEquals(ExecutionMode.Action.WORKING_BRANCH_WRITE, GitHubAgentOperations.action("create_pull_request", null));
        assertEquals(ExecutionMode.Action.PUBLISH,
            GitHubAgentOperations.action("update_repository", new JSONObject().put("private", false)));
        assertEquals(ExecutionMode.Action.WORKING_BRANCH_WRITE,
            GitHubAgentOperations.action("create_repository", new JSONObject().put("private", true)));
    }

    @Test public void levelMappingRoundTrips() {
        for (ExecutionMode m : ExecutionMode.values())
            assertEquals(m, ExecutionMode.forLevel(m.permissionLevel()));
    }
}
