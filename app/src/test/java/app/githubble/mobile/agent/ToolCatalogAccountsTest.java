package app.githubble.mobile.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class ToolCatalogAccountsTest {
    private static ToolBroker broker(List<String> seen) throws Exception {
        ToolBroker b = new ToolBroker(new TaskSession("a", "p", "t", () -> {}), d -> true, (d, a) -> true,
                (n, a) -> { seen.add(n + a.toString()); return "ok"; }, UnifiedAgentTestSupport.journal());
        ToolCatalog.install(b);
        return b;
    }

    @Test public void accountsToolIsAReadOnlyGitHubTool() throws Exception {
        List<String> seen = new ArrayList<>();
        assertEquals("ok", broker(seen).execute("github_accounts", new JSONObject()));
        assertEquals(1, seen.size());
        assertTrue(ProjectRuntime.allowed("github_accounts", true, false));
        assertTrue(!ProjectRuntime.allowed("github_accounts", false, true));
    }

    @Test public void readToolsAcceptAnotherConnectedAccount() throws Exception {
        List<String> seen = new ArrayList<>();
        ToolBroker b = broker(seen);
        b.execute("list_repositories", new JSONObject().put("account", "all"));
        b.execute("repository_info", new JSONObject().put("repository", "a/b").put("account", "other-login"));
        b.execute("repository_files", new JSONObject().put("repository", "a/b").put("branch", "main").put("account", "other-login"));
        assertEquals(3, seen.size());
    }

    @Test public void writesNeverTakeAnAccountArgument() throws Exception {
        ToolBroker b = broker(new ArrayList<>());
        try {
            b.execute("create_issue", new JSONObject().put("repository", "a/b").put("title", "t").put("account", "other"));
            fail("a write tool must not accept a foreign account");
        } catch (Exception expected) { /* schema rejects unknown arguments */ }
    }
}
