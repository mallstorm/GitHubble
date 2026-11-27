package app.githubble.mobile.repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RepoRevertTest {
    private static JSONObject listEntry(String sha, String parent, String message, String tree) throws Exception {
        JSONObject o = new JSONObject().put("sha", sha).put("commit", new JSONObject().put("message", message).put("tree", new JSONObject().put("sha", tree))
            .put("author", new JSONObject().put("name", "A").put("date", "2026-01-03T00:00:00Z")));
        o.put("parents", new org.json.JSONArray());
        if (parent != null) o.getJSONArray("parents").put(new JSONObject().put("sha", parent));
        return o;
    }

    @Test public void chainFollowsFirstParentsAndStopsAtTheRoot() throws Exception {
        Map<String, JSONObject> known = new HashMap<>();
        known.put("c3", listEntry("c3", "c2", "m3\n\nbody", "t3"));
        known.put("c2", listEntry("c2", "c1", "m2", "t2"));
        known.put("c1", listEntry("c1", null, "m1", "t1"));
        List<RepoRevert.Commit> chain = RepoRevert.firstParentChain("c3", 3, known, sha -> { throw new AssertionError("not needed"); });
        assertEquals(3, chain.size()); assertEquals("c3", chain.get(0).sha); assertEquals("m3", chain.get(0).title); assertEquals("t1", chain.get(2).tree);
        assertEquals(3, RepoRevert.firstParentChain("c3", 9, known, sha -> null).size());
        assertEquals(1, RepoRevert.firstParentChain("c3", 1, known, sha -> null).size());
    }

    @Test public void missingCommitsAreFetchedOneByOne() throws Exception {
        Map<String, JSONObject> known = new HashMap<>();
        known.put("c3", listEntry("c3", "c2", "m3", "t3"));
        JSONObject gitData = new JSONObject("{\"sha\":\"c2\",\"message\":\"m2\",\"tree\":{\"sha\":\"t2\"},\"parents\":[],\"author\":{\"name\":\"B\",\"date\":\"2026-01-02T00:00:00Z\"}}");
        List<RepoRevert.Commit> chain = RepoRevert.firstParentChain("c3", 2, known, sha -> "c2".equals(sha) ? gitData : null);
        assertEquals(2, chain.size()); assertEquals("t2", chain.get(1).tree); assertEquals("m2", chain.get(1).title); assertEquals("B", chain.get(1).author);
    }

    @Test public void stepsAndNamesAreChecked() {
        assertEquals(3, RepoRevert.checkSteps(" 3 "));
        for (String bad : new String[]{"", "0", "-1", "abc", String.valueOf(RepoRevert.MAX_STEPS + 1)}) {
            try { RepoRevert.checkSteps(bad); fail(bad); } catch (IllegalArgumentException expected) { }
        }
        assertEquals("revert/main-2-steps", RepoRevert.checkBranchName("revert/main-2-steps"));
        for (String bad : new String[]{"", "a b", "a..b", "/a", "a/", "a.lock", "a~1", "a:b", "-x", "a//b", "a@{b"}) {
            try { RepoRevert.checkBranchName(bad); fail(bad); } catch (IllegalArgumentException expected) { }
        }
        assertEquals("revert/feature-x-4-steps", RepoRevert.defaultBranchName("feature/x", 4));
        assertEquals("revert/branch-1-steps", RepoRevert.defaultBranchName("///", 1));
    }

    @Test public void defaultMessageNamesTheRestoredCommit() throws Exception {
        RepoRevert.Commit c = RepoRevert.parse(listEntry("0123456789abcdef", null, "Fix login", "t"));
        assertEquals("0123456", c.shortSha());
        assertEquals("Revert last 1 commit: restore 0123456 (Fix login)", RepoRevert.defaultMessage(1, c));
        assertEquals("Revert last 3 commits: restore 0123456 (Fix login)", RepoRevert.defaultMessage(3, c));
    }
}
