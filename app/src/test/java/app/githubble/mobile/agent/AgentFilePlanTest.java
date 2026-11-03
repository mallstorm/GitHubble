package app.githubble.mobile.agent;

import org.junit.Test;
import org.json.JSONObject;
import org.json.JSONArray;
import static org.junit.Assert.*;

/** Security checks for AI proposals. Does not use a token, network or Android runtime. */
public final class AgentFilePlanTest {
    private static String proposal(String path, String content) throws Exception {
        return new JSONObject().put("summary", "Create Android source")
                .put("commit_message", "feat: add sample")
                .put("files", new JSONArray().put(new JSONObject()
                        .put("path", path).put("content", content))).toString();
    }

    @Test public void acceptsValidSourcePlan() throws Exception {
        AgentFilePlan plan = AgentFilePlan.parse(proposal("app/src/main/java/a/Main.java",
                "package a; public final class Main {}"));
        assertEquals(1, plan.files.length());
        assertEquals("feat: add sample", plan.commitMessage);
        assertTrue(plan.preview().contains("app/src/main/java/a/Main.java"));
    }

    @Test public void rejectsPathTraversalAndGitMetadata() throws Exception {
        String[] bad = {"../secret", "/etc/passwd", "src/../../private", "a/.git/config",
                "C:/Windows/System32", "a\\b", "x//y", ".github/workflows/evil.txt"};
        for (String path : bad) {
            try { AgentFilePlan.parse(proposal(path, "bad")); fail("Unsafe path: " + path); }
            catch (IllegalArgumentException expected) {}
        }
    }

    @Test public void disallowCredentialAndWorkspaceMetadataPaths()throws Exception{
        for(String bad:new String[]{".env","config/secrets.properties","credentials.json",
            "id_rsa","keys/private.pem",".workspace.json"}){
            try{AgentFilePlan.parse(proposal(bad,"do not write"));fail("Allowed: "+bad);}
            catch(IllegalArgumentException expected){}
        }
    }
    @Test public void rejectsDuplicateCaseInsensitivePaths() throws Exception {
        JSONArray files = new JSONArray()
                .put(new JSONObject().put("path", "README.md").put("content", "one"))
                .put(new JSONObject().put("path", "readme.md").put("content", "two"));
        try { AgentFilePlan.parse(new JSONObject().put("files", files).toString()); fail(); }
        catch (IllegalArgumentException expected) {}
    }

    @Test public void rejectsOversizedAndEmptyFile() throws Exception {
        try { AgentFilePlan.parse(proposal("empty.txt", "")); fail(); }
        catch (IllegalArgumentException expected) {}
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < AgentFilePlan.MAX_FILE_BYTES + 1; i++) value.append('x');
        try { AgentFilePlan.parse(proposal("huge.txt", value.toString())); fail(); }
        catch (IllegalArgumentException expected) {}
    }

    @Test public void acceptsMarkdownFencedJson() throws Exception {
        AgentFilePlan plan = AgentFilePlan.parse(proposal("src/a.txt", "ok"));
        assertNotNull(plan);
    }
}
