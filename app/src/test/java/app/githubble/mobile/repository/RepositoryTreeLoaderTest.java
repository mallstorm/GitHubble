package app.githubble.mobile.repository;

import static org.junit.Assert.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class RepositoryTreeLoaderTest {
    private static String sha(int n) { return String.format("%040x", n); }

    /** Fake repository: directories by SHA; recursive listing can be forced to truncate. */
    private static final class Fake implements RepositoryTreeLoader.Source {
        final Map<String, JSONArray> dirs = new HashMap<>();
        boolean truncateRecursive;
        int requests;
        void dir(String sha, Object... triples) throws Exception {
            JSONArray a = new JSONArray();
            for (int i = 0; i < triples.length; i += 3) {
                boolean tree = "tree".equals(triples[i + 1]);
                a.put(new JSONObject().put("path", triples[i]).put("type", triples[i + 1])
                        .put("sha", triples[i + 2]).put("mode", tree ? "040000" : "100644")
                        .put("size", tree ? 0 : 10));
            }
            dirs.put(sha, a);
        }
        @Override public JSONObject tree(String treeSha, boolean recursive) throws Exception {
            requests++;
            if (recursive) {
                if (truncateRecursive) return new JSONObject().put("truncated", true).put("tree", new JSONArray());
                JSONArray out = new JSONArray();
                flatten(treeSha, "", out);
                return new JSONObject().put("truncated", false).put("tree", out);
            }
            return new JSONObject().put("truncated", false).put("tree", dirs.get(treeSha));
        }
        void flatten(String s, String prefix, JSONArray out) throws Exception {
            JSONArray a = dirs.get(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = new JSONObject(a.getJSONObject(i).toString());
                String name = e.getString("path");
                e.put("path", prefix + name);
                out.put(e);
                if ("tree".equals(e.getString("type"))) flatten(e.getString("sha"), prefix + name + "/", out);
            }
        }
    }

    private static Fake repo() throws Exception {
        Fake f = new Fake();
        f.dir(sha(1), "README.md", "blob", sha(10), "app", "tree", sha(2), "docs", "tree", sha(3));
        f.dir(sha(2), "src", "tree", sha(4), "build.gradle", "blob", sha(11));
        f.dir(sha(4), "Main.java", "blob", sha(12));
        f.dir(sha(3), "a.md", "blob", sha(13));
        return f;
    }

    private static int blobs(JSONObject tree, String path) {
        int n = 0;
        JSONArray a = tree.optJSONArray("tree");
        for (int i = 0; i < a.length(); i++)
            if (path.equals(a.optJSONObject(i).optString("path"))) n++;
        return n;
    }

    @Test public void scopeReturnsOnlyThatFolderWithFullPaths() throws Exception {
        JSONObject t = RepositoryTreeLoader.load(repo(), sha(1), "/app/src/");
        assertEquals(1, t.getJSONArray("tree").length());
        assertEquals(1, blobs(t, "app/src/Main.java"));
    }

    @Test public void emptyScopeReadsEverythingAndMissingFolderIsEmpty() throws Exception {
        Fake f = repo();
        assertTrue(RepositoryTreeLoader.load(f, sha(1), "").getJSONArray("tree").length() >= 5);
        assertEquals(0, RepositoryTreeLoader.load(f, sha(1), "nope/deeper").getJSONArray("tree").length());
    }

    @Test public void truncatedRecursiveListingFallsBackToLevelWalkWithoutLosingFiles() throws Exception {
        Fake f = repo();
        f.truncateRecursive = true;
        JSONObject t = RepositoryTreeLoader.load(f, sha(1), "");
        assertEquals(1, blobs(t, "README.md"));
        assertEquals(1, blobs(t, "app/src/Main.java"));
        assertEquals(1, blobs(t, "docs/a.md"));
        assertEquals("directories are walked, not returned as entries", 0, blobs(t, "app"));
        // Result is directly usable by the comparison.
        List<RepositoryFileComparison.Delta> d = RepositoryFileComparison.compare(t, t);
        assertTrue(d.isEmpty());
    }

    @Test public void fileInsteadOfFolderAndUnsafeScopesAreRejected() throws Exception {
        try { RepositoryTreeLoader.load(repo(), sha(1), "README.md/x"); fail(); }
        catch (IllegalStateException expected) { }
        for (String bad : new String[] {"../x", "a/../b", "a//b", ".git/config"}) {
            try { RepositoryTreeLoader.normalizeScope(bad); fail(bad); }
            catch (IllegalArgumentException expected) { }
        }
        assertEquals("", RepositoryTreeLoader.normalizeScope("  / "));
        assertEquals("a/b", RepositoryTreeLoader.normalizeScope("\\a\\b\\"));
    }
}
