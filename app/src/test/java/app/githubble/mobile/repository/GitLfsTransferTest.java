package app.githubble.mobile.repository;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;

public final class GitLfsTransferTest {
    private static byte[] pointerFor(byte[] data) throws Exception {
        return ("version https://git-lfs.github.com/spec/v1\noid sha256:" + GitLfsTransfer.sha256(data)
                + "\nsize " + data.length + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** Minimal LFS server for one repo pair. */
    private static final class Server implements GitLfsTransfer.Transport {
        byte[] stored; boolean destinationHasObject; boolean corruptDownload; boolean quotaExceeded;
        final List<String> calls = new ArrayList<>();
        @Override public GitLfsTransfer.Response send(String method, String url, Map<String, String> h, byte[] body)
                throws Exception {
            calls.add(method + " " + url);
            if (url.endsWith("/info/lfs/objects/batch")) {
                assertTrue(h.get("Authorization").startsWith("Basic "));
                JSONObject req = new JSONObject(new String(body, StandardCharsets.UTF_8));
                JSONObject o = req.getJSONArray("objects").getJSONObject(0);
                JSONObject obj = new JSONObject().put("oid", o.getString("oid")).put("size", o.getLong("size"));
                boolean download = "download".equals(req.getString("operation"));
                JSONObject actions = new JSONObject();
                if (download) actions.put("download", new JSONObject().put("href", "https://cdn.example/dl")
                        .put("header", new JSONObject().put("X-Token", "t")));
                else if (!destinationHasObject) actions.put("upload", new JSONObject().put("href", "https://cdn.example/up"))
                        .put("verify", new JSONObject().put("href", "https://cdn.example/verify"));
                if (actions.length() > 0) obj.put("actions", actions);
                return new GitLfsTransfer.Response(200, new JSONObject().put("objects",
                        new org.json.JSONArray().put(obj)).toString().getBytes(StandardCharsets.UTF_8));
            }
            if (url.endsWith("/dl")) {
                assertEquals("t", h.get("X-Token"));
                assertNull("repo credentials must not leak to the storage host", h.get("Authorization"));
                byte[] data = stored.clone();
                if (corruptDownload) data[0] ^= 1;
                return new GitLfsTransfer.Response(200, data);
            }
            if (url.endsWith("/up")) {
                if (quotaExceeded) return new GitLfsTransfer.Response(403, new byte[0]);
                stored = body; return new GitLfsTransfer.Response(200, new byte[0]);
            }
            return new GitLfsTransfer.Response(200, new byte[0]);
        }
    }

    @Test public void parsesOnlyRealPointers() throws Exception {
        byte[] data = "payload".getBytes(StandardCharsets.UTF_8);
        GitLfsTransfer.Pointer p = GitLfsTransfer.parsePointer(pointerFor(data));
        assertNotNull(p);
        assertEquals(7, p.size);
        assertNull(GitLfsTransfer.parsePointer("hello".getBytes(StandardCharsets.UTF_8)));
        assertNull(GitLfsTransfer.parsePointer(("version https://git-lfs.github.com/spec/v1\noid sha256:xyz\nsize 1\n")
                .getBytes(StandardCharsets.UTF_8)));
        assertTrue(GitLfsTransfer.looksLikePointer(pointerFor(data)));
    }

    @Test public void copiesVerifiedObjectThroughDownloadUploadVerify() throws Exception {
        Server s = new Server();
        byte[] data = new byte[5000];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 7);
        s.stored = data;
        GitLfsTransfer t = new GitLfsTransfer(s);
        GitLfsTransfer.Pointer p = GitLfsTransfer.parsePointer(pointerFor(data));
        byte[] got = t.download("a/src", "alice", "tok", p);
        s.stored = null;
        t.upload("b/dst", "bob", "tok2", p, got);
        assertArrayEquals(data, s.stored);
        assertTrue(s.calls.toString(), s.calls.contains("POST https://cdn.example/verify"));
    }

    @Test public void corruptedDownloadIsRejectedAndNothingIsUploaded() throws Exception {
        Server s = new Server();
        byte[] data = "important".getBytes(StandardCharsets.UTF_8);
        s.stored = data; s.corruptDownload = true;
        GitLfsTransfer t = new GitLfsTransfer(s);
        try { t.download("a/src", "alice", "tok", GitLfsTransfer.parsePointer(pointerFor(data))); fail(); }
        catch (SecurityException expected) { }
        for (String c : s.calls) assertFalse(c, c.endsWith("/up"));
    }

    @Test public void existingDestinationObjectIsSkippedAndQuotaErrorIsExplained() throws Exception {
        byte[] data = "x".getBytes(StandardCharsets.UTF_8);
        GitLfsTransfer.Pointer p = GitLfsTransfer.parsePointer(pointerFor(data));
        Server exists = new Server(); exists.destinationHasObject = true;
        new GitLfsTransfer(exists).upload("b/dst", "bob", "t", p, data);
        for (String c : exists.calls) assertFalse(c, c.endsWith("/up"));
        Server quota = new Server(); quota.quotaExceeded = true;
        try { new GitLfsTransfer(quota).upload("b/dst", "bob", "t", p, data); fail(); }
        catch (IllegalStateException e) { assertTrue(e.getMessage(), e.getMessage().contains("квот")); }
    }

    @Test public void oversizeObjectsAreRefusedBeforeAnyNetworkCall() throws Exception {
        Server s = new Server();
        GitLfsTransfer.Pointer big = GitLfsTransfer.parsePointer((
                "version https://git-lfs.github.com/spec/v1\noid sha256:" + new String(new char[64]).replace((char) 0, (char) 97)
                        + "\nsize " + (GitLfsTransfer.MAX_OBJECT_BYTES + 1) + "\n").getBytes(StandardCharsets.UTF_8));
        try { new GitLfsTransfer(s).download("a/src", "alice", "t", big); fail(); }
        catch (IllegalStateException expected) { }
        assertTrue(s.calls.isEmpty());
    }
}
