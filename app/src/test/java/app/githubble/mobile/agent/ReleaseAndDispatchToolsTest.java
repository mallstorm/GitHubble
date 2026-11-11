package app.githubble.mobile.agent;

import static org.junit.Assert.*;

import app.githubble.mobile.agent.core.ExecutionMode;
import org.json.JSONObject;
import org.junit.Test;

public class ReleaseAndDispatchToolsTest {
  @Test public void releaseToolsAreInTheCatalog() {
    assertTrue(GitHubAgentOperations.READS.contains("list_releases"));
    assertTrue(GitHubAgentOperations.WRITES.contains("create_release"));
    assertTrue(GitHubAgentOperations.READ_CATALOG.contains("list_releases"));
    assertTrue(GitHubAgentOperations.WRITE_CATALOG.contains("create_release"));
    assertTrue(GitHubAgentOperations.sensitive("create_release"));
  }

  @Test public void finalReleaseIsClassifiedAsProtectedMerge() throws Exception {
    assertEquals(ExecutionMode.Action.WORKING_BRANCH_WRITE,
        GitHubAgentOperations.action("create_release", new JSONObject()));
    assertEquals(ExecutionMode.Action.WORKING_BRANCH_WRITE,
        GitHubAgentOperations.action("create_release", new JSONObject().put("draft", true)));
    assertEquals(ExecutionMode.Action.MERGE_PROTECTED,
        GitHubAgentOperations.action("create_release", new JSONObject().put("draft", false)));
  }

  @Test public void dispatchNeedsNoDialogOnlyAtAutoLevel() {
    assertFalse(GitHubAgentOperations.sensitive("dispatch_workflow"));
    assertFalse(AgentPermissionPolicy.needsReview(AgentPermissionPolicy.GITHUB_AUTO, true, GitHubAgentOperations.sensitive("dispatch_workflow")));
    assertTrue(AgentPermissionPolicy.needsReview(AgentPermissionPolicy.LOCAL_AUTO, true, GitHubAgentOperations.sensitive("dispatch_workflow")));
    assertTrue(AgentPermissionPolicy.needsReview(AgentPermissionPolicy.REVIEW, true, GitHubAgentOperations.sensitive("dispatch_workflow")));
    assertTrue(AgentPermissionPolicy.needsReview(AgentPermissionPolicy.GITHUB_AUTO, true, GitHubAgentOperations.sensitive("merge_pull_request")));
  }

  @Test public void permissionLabelsNameTheModes() {
    assertEquals(4, AgentPermissionPolicy.LABELS.length);
    assertTrue(AgentPermissionPolicy.LABELS[1].startsWith("Ручной"));
    assertTrue(AgentPermissionPolicy.LABELS[2].startsWith("Полуавто"));
    assertTrue(AgentPermissionPolicy.LABELS[3].startsWith("Авто"));
  }

  @Test public void dispatchCatalogDescribesTracking() {
    assertTrue(GitHubAgentOperations.WRITE_CATALOG.contains("run_id"));
  }
}
