package app.githubble.mobile.repository;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Arrays;
import java.util.List;

/** Tests that file-granular operations cannot masquerade as full mirroring. */
public final class RepositoryFileComparisonTest {
    private static JSONObject item(String path, String sha, String mode, String type, long size)
            throws Exception {
        return new JSONObject().put("path", path).put("sha", sha)
                .put("mode", mode).put("type", type).put("size", size);
    }
    private static JSONObject tree(JSONObject... entries) throws Exception {
        JSONArray items = new JSONArray();
        for (JSONObject e : entries) items.put(e);
        return new JSONObject().put("tree", items).put("truncated", false);
    }
    private static final String A="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String B="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String C="cccccccccccccccccccccccccccccccccccccccc";

    @Test public void distinguishesOnlySourceChangedAndOnlyTargetWithoutDeleting() throws Exception {
        JSONObject src=tree(item("src/new.java", A, "100644", "blob", 100),
                item("src/changed.java", B, "100644", "blob", 101),
                item("same.md", C, "100644", "blob", 25));
        JSONObject dst=tree(item("src/changed.java", A, "100644", "blob", 100),
                item("same.md", C, "100644", "blob", 25),
                item("target-only.md", B, "100644", "blob", 50));
        List<RepositoryFileComparison.Delta> changes=RepositoryFileComparison.compare(src,dst);
        assertEquals(3, changes.size());
        assertEquals(RepositoryFileComparison.ADD,
                changes.stream().filter(x->x.path.equals("src/new.java")).findFirst().get().status);
        assertEquals(RepositoryFileComparison.UPDATE,
                changes.stream().filter(x->x.path.equals("src/changed.java")).findFirst().get().status);
        RepositoryFileComparison.Delta target=changes.stream()
                .filter(x->x.path.equals("target-only.md")).findFirst().get();
        assertEquals(RepositoryFileComparison.TARGET_ONLY,target.status);
        assertFalse(target.selectable());
    }

    @Test public void protectsSecretsWorkflowsLfsAndSpecialModes() throws Exception {
        JSONObject src=tree(item(".github/workflows/test.yml", A, "100644", "blob", 10),
                item("secret.pem", A, "100644", "blob", 10),
                item("src/link", A, "120000", "blob", 5),
                item("src/submodule", A, "160000", "commit", -1),
                item("big.bin", A, "100644", "blob",
                        RepositoryFileComparison.MAX_FILE_BYTES+1L),
                item("normal.java", A, "100644", "blob", 10));
        List<RepositoryFileComparison.Delta> diff=RepositoryFileComparison.compare(src,tree());
        assertEquals(6,diff.size());
        long selectable=diff.stream().filter(RepositoryFileComparison.Delta::selectable).count();
        assertEquals(1,selectable);
        assertTrue(diff.stream().filter(x->x.path.equals("normal.java"))
                .findFirst().get().selectable());
    }

    @Test public void refusesTruncatedTreesAndUnsafePaths() throws Exception {
        JSONObject truncated=tree(item("normal",A,"100644","blob",10)).put("truncated",true);
        try {RepositoryFileComparison.compare(truncated,tree());fail("Truncated tree accepted");}
        catch(IllegalStateException expected) { }
        for(String path:Arrays.asList("../secret","a/../b","a//b","a/.git/objects",
                "/root","a\\b","a/\nnew","a/")) {
            assertFalse("Unsafe: "+path,RepositoryFileComparison.safePath(path));
        }
    }

    @Test public void validatesSelectedPathsAndMaxBatch() throws Exception {
        JSONObject src=tree(item("one.java",A,"100644","blob",11),
                item("two.java",B,"100644","blob",12));
        List<RepositoryFileComparison.Delta> diff=RepositoryFileComparison.compare(src,tree());
        RepositorySelectiveTransfer.Comparison snapshot =
                new RepositorySelectiveTransfer.Comparison("one/source","two/dest",
                        "source-account","dest-account","main","main",A,B,false,false,"",diff);
        assertEquals(2,RepositorySelectiveTransfer.requireSelection(
                snapshot,Arrays.asList("one.java","two.java")).size());
        for(List<String> paths:Arrays.asList(Arrays.asList("one.java","one.java"),
                Arrays.asList("absent.java"),Arrays.asList("one.java","absent.java"))) {
            try {
                RepositorySelectiveTransfer.requireSelection(snapshot,paths);
                fail("Invalid selection accepted "+paths);
            }catch(IllegalArgumentException expected) { }
        }
    }
}
