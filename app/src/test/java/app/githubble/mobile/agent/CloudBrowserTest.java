package app.githubble.mobile.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONObject;
import org.junit.Test;

public class CloudBrowserTest {
    private static String chunks(String id, String json, int size) {
        String b64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        int total = Math.max(1, (b64.length() + size - 1) / size);
        StringBuilder log = new StringBuilder("2026-11-25T10:00:00.0000000Z ##[group]Run node browse.mjs\n");
        for (int i = 0; i < total; i++)
            log.append("2026-11-25T10:00:0").append(i % 10).append(".0000000Z HUBBLE_RESULT:").append(id).append(':').append(i + 1)
                .append('/').append(total).append(':').append(b64, i * size, Math.min(b64.length(), (i + 1) * size)).append("\r\n");
        return log.append("2026-11-25T10:00:09.0000000Z ##[endgroup]\n").toString();
    }

    @Test public void reassemblesChunkedResultFromTimestampedLog() throws Exception {
        String payload = new JSONObject().put("ok", true).put("page", new JSONObject().put("title", "Привет, мир").put("text", "x".repeat(9000))).toString();
        JSONObject parsed = CloudBrowser.parseResult(chunks("abc", payload, 3000), "abc");
        assertTrue(parsed.getBoolean("ok"));
        assertEquals("Привет, мир", parsed.getJSONObject("page").getString("title"));
        assertEquals(9000, parsed.getJSONObject("page").getString("text").length());
    }

    @Test public void ignoresOtherRequestsAndIncompleteLogs() throws Exception {
        String log = chunks("other", "{\"ok\":true}", 3000);
        assertNull(CloudBrowser.parseResult(log, "mine"));
        String partial = chunks("mine", "{\"ok\":true,\"pad\":\"" + "y".repeat(7000) + "\"}", 3000);
        String firstLineOnly = partial.substring(0, partial.indexOf("\r\n") + 2);
        assertNull(CloudBrowser.parseResult(firstLineOnly, "mine"));
        assertNull(CloudBrowser.parseResult("", "mine"));
        assertNull(CloudBrowser.parseResult(null, "mine"));
    }

    @Test public void workflowIsLeastPrivilegeAndMatchesLookup() {
        String yaml = CloudBrowser.WORKFLOW_YAML;
        assertTrue(yaml.contains("run-name: browse ${{ inputs.id }}"));
        assertTrue(yaml.contains("contents: read"));
        assertTrue(yaml.contains("persist-credentials: false"));
        assertFalse(yaml.contains("write"));
        assertTrue(yaml.contains("workflow_dispatch"));
    }

    @Test public void driverBlocksPrivateHostsAndCapsRuntime() {
        String js = CloudBrowser.SCRIPT_JS;
        assertTrue(js.contains("169\\.254"));
        assertTrue(js.contains("metadata.google.internal"));
        assertTrue(js.contains("150000"));
        assertTrue(js.contains("HUBBLE_RESULT:"));
    }

    @Test public void requestIdsAreDistinctAndSafe() {
        String a = CloudBrowser.newId(), b = CloudBrowser.newId();
        assertTrue(a.matches("[a-z0-9]{4,20}"));
        assertFalse(a.equals(b) && a.equals(CloudBrowser.newId()));
    }
}
