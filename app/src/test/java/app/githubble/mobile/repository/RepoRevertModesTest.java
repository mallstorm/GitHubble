package app.githubble.mobile.repository;

import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RepoRevertModesTest {
    private static RepoRevert.Entry e(String sha) { return new RepoRevert.Entry("100644", "blob", sha); }

    private static JSONObject find(RepoRevert.Merge m, String path) throws Exception {
        for (JSONObject x : m.entries) if (path.equals(x.getString("path"))) return x;
        return null;
    }

    @Test public void singleCommitIsUndoneFileByFile() throws Exception {
        Map<String, RepoRevert.Entry> c = new HashMap<>(), p = new HashMap<>(), h = new HashMap<>();
        c.put("a", e("C1")); c.put("b", e("B")); c.put("added", e("D"));
        p.put("a", e("P1")); p.put("b", e("B")); p.put("removed", e("P3"));
        h.put("a", e("C1")); h.put("b", e("B2")); h.put("added", e("D"));
        RepoRevert.Merge m = RepoRevert.threeWay(c, p, h);
        assertTrue(m.conflicts.isEmpty());
        assertEquals("P1", find(m, "a").getString("sha"));
        assertTrue(find(m, "added").isNull("sha"));
        assertEquals("P3", find(m, "removed").getString("sha"));
        assertNull(find(m, "b"));
    }

    @Test public void laterChangesAreConflictsAndAlreadyRevertedFilesAreSkipped() throws Exception {
        Map<String, RepoRevert.Entry> c = new HashMap<>(), p = new HashMap<>(), h = new HashMap<>();
        c.put("a", e("C1")); c.put("x", e("C2"));
        p.put("a", e("P1")); p.put("x", e("P2"));
        h.put("a", e("LATER")); h.put("x", e("P2"));
        RepoRevert.Merge m = RepoRevert.threeWay(c, p, h);
        assertEquals(1, m.conflicts.size()); assertEquals("a", m.conflicts.get(0));
        assertTrue(m.entries.isEmpty());
    }

    @Test public void singleCommitMessageNamesTheCommit() throws Exception {
        RepoRevert.Commit c = RepoRevert.parse(new JSONObject("{\"sha\":\"0123456789abcdef\",\"message\":\"Fix login\",\"tree\":{\"sha\":\"t\"},\"author\":{\"name\":\"A\",\"date\":\"d\"}}"));
        assertEquals("Revert \"Fix login\"\n\nThis reverts commit 0123456789abcdef.", RepoRevert.defaultSingleMessage(c));
    }

    @Test public void rulesetCopiesKeepOnlyWritableFields() throws Exception {
        JSONObject out = SettingsCopy.rulesetBody(new JSONObject("{\"id\":1,\"node_id\":\"n\",\"name\":\"r\",\"target\":\"branch\",\"enforcement\":\"active\",\"source\":\"o/r\",\"source_type\":\"Repository\","
            + "\"bypass_actors\":[{\"actor_id\":5}],\"conditions\":{\"ref_name\":{\"include\":[\"~DEFAULT_BRANCH\"],\"exclude\":[]}},\"rules\":[{\"type\":\"deletion\"}],\"_links\":{},\"created_at\":\"x\"}"));
        assertEquals("r", out.getString("name")); assertEquals(1, out.getJSONArray("rules").length());
        for (String gone : new String[]{"id", "node_id", "source", "source_type", "bypass_actors", "_links", "created_at"}) assertFalse(gone, out.has(gone));
        assertEquals("o/r2", SettingsCopy.checkSource(" o/r2 ", "o/r"));
        try { SettingsCopy.checkSource("o/r", "O/R"); fail(); } catch (IllegalArgumentException expected) { }
        try { SettingsCopy.checkSource("not a repo", "o/r"); fail(); } catch (IllegalArgumentException expected) { }
    }
}
