package app.githubble.mobile.repository;

import static org.junit.Assert.*;
import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class RepositoryFileRestoreTest {
    private static final String A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private static RepositoryFileComparison.Entry blob(String path, String sha,
                                                      String mode, long size) {
        return new RepositoryFileComparison.Entry(path, sha, mode, "blob", size);
    }

    private static JSONObject tree(String name, String mode, String type, String sha, long size)
            throws Exception {
        JSONObject entry = new JSONObject()
                .put("path", name).put("mode", mode)
                .put("type", type).put("sha", sha);
        if (size >= 0) entry.put("size", size);
        return new JSONObject().put("tree", new JSONArray().put(entry))
                .put("truncated", false);
    }

    @Test public void safeTreeTraversalAndMissingEntry() throws Exception {
        JSONObject root = tree("src", "040000", "tree", A, -1);
        RepositoryFileComparison.Entry dir = RepositoryFileRestore.childEntry(
                root, "src", "src/Hello.java");
        assertNotNull(dir);
        assertEquals("tree", dir.type);
        assertEquals(A, dir.sha);
        assertNull(RepositoryFileRestore.childEntry(root, "missing", "missing"));
        JSONObject missing = new JSONObject().put("truncated", true)
                .put("tree", new JSONArray());
        try {
            RepositoryFileRestore.childEntry(missing, "a", "a");
            fail("Truncated Git tree incorrectly accepted");
        } catch (IllegalStateException expected) { }
    }

    @Test public void onlyRegularFilesMayBeRestored() {
        RepositoryFileComparison.Entry old = blob("src/A.java", A, "100644", 25);
        RepositoryFileRestore.requireRestorable(old, null);
        try {
            RepositoryFileRestore.requireRestorable(old, old);
            fail("Equal blob must not produce an empty PR");
        } catch (IllegalStateException expected) { }
        for (String name : new String[] {".github/workflows/ci.yml", ".env", "server.pem"}) {
            try {
                RepositoryFileRestore.requireRestorable(blob(name, A, "100644", 25), null);
                fail("Sensitive file accepted: " + name);
            } catch (IllegalStateException expected) { }
        }
        try {
            RepositoryFileRestore.requireRestorable(blob("link", A, "120000", 3), null);
            fail("Symlink should not be restored blindly");
        } catch (IllegalStateException expected) { }
        try {
            RepositoryFileRestore.requireRestorable(null, null);
            fail("Missing old blob cannot be recovered from that commit");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void historicalPathHasStrictLimits() {
        assertEquals("app/src/main/java/A.java",
                RepositoryFileRestore.safeHistoricalPath("app/src/main/java/A.java"));
        for (String path : new String[] {"../secret", ".git/config", "/etc/passwd",
                "src//Main.java", "a\\b.txt", "a\nb", "", "a/../b"}) {
            try {
                RepositoryFileRestore.safeHistoricalPath(path);
                fail("Path accepted: " + path);
            } catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void verifiesGitObjectContentAndBlocksLfsPointer() throws Exception {
        byte[] contents = "hello from git\n".getBytes(StandardCharsets.UTF_8);
        String sha = RepositorySelectiveTransfer.sha1GitBlob(contents);
        RepositoryFileComparison.Entry old = blob("README.txt", sha, "100644", contents.length);
        JSONObject response = new JSONObject()
                .put("sha", sha)
                .put("encoding", "base64")
                .put("content", Base64.getEncoder().encodeToString(contents));
        RepositoryFileRestore.validateBlob(response, old);

        try {
            RepositoryFileRestore.validateBlob(response, blob("README.txt", A, "100644",
                    contents.length));
            fail("SHA mismatch accepted");
        } catch (SecurityException expected) { }

        String lfsText = "version https://git-lfs.github.com/spec/v1\r\n"
                + "oid sha256:0123456789abcdef\r\nsize 10\r\n";
        byte[] pointer = lfsText.getBytes(StandardCharsets.UTF_8);
        JSONObject lfs = new JSONObject()
                .put("sha", RepositorySelectiveTransfer.sha1GitBlob(pointer))
                .put("encoding", "base64")
                .put("content", Base64.getEncoder().encodeToString(pointer));
        try {
            RepositoryFileRestore.validateBlob(lfs, blob("asset.bin",
                    RepositorySelectiveTransfer.sha1GitBlob(pointer), "100644", pointer.length));
            fail("LFS pointer should never be restored without LFS content");
        } catch (IllegalStateException expected) { }
    }
}
