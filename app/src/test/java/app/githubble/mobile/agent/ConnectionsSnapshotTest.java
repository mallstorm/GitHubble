package app.githubble.mobile.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.githubble.mobile.agent.ConnectionsSnapshot.Action;
import java.util.Arrays;
import org.junit.Test;

public class ConnectionsSnapshotTest {
    private static ConnectionsSnapshot sample() {
        ConnectionsSnapshot s = new ConnectionsSnapshot();
        s.updatedAt = 100;
        ConnectionsSnapshot.Service svc = new ConnectionsSnapshot.Service();
        svc.id = "custom-keydealer"; svc.name = "Keydealer"; svc.base = "https://keydealer.ru/v1"; svc.protocol = "openai-chat";
        s.services.put(svc.id, svc);
        ConnectionsSnapshot.Profile p = new ConnectionsSnapshot.Profile();
        p.selected = "openai";
        ConnectionsSnapshot.Provider v = new ConnectionsSnapshot.Provider();
        v.model = "gpt-5"; v.base = "https://api.openai.com/v1"; v.protocol = "openai-responses"; v.strategy = 1;
        v.keyLabels = Arrays.asList("Основной", "Работа, запасной");
        p.providers.put("openai", v);
        p.favorites = Arrays.asList("openai|gpt-5");
        s.profiles.put("owner", p);
        return s;
    }

    @Test public void jsonRoundTripKeepsContent() throws Exception {
        ConnectionsSnapshot a = sample();
        ConnectionsSnapshot b = ConnectionsSnapshot.parse(a.toJson());
        assertTrue(a.sameContent(b));
        assertEquals(100, b.updatedAt);
        assertEquals(Arrays.asList("Основной", "Работа, запасной"), b.profiles.get("owner").providers.get("openai").keyLabels);
    }

    @Test public void fileNeverContainsSecretsOrCounters() {
        String json = sample().toJson();
        assertFalse(json.contains("secret"));
        assertFalse(json.contains("api_key"));
        assertFalse(json.contains("tokens"));
    }

    @Test public void blankMeansNoFileAndForeignFormatIsRejected() throws Exception {
        assertNull(ConnectionsSnapshot.parse("  "));
        try { ConnectionsSnapshot.parse("{\"format\":\"githubble-mirror-links\",\"schema\":1}"); throw new AssertionError(); }
        catch (IllegalArgumentException expected) { /* ok */ }
    }

    @Test public void damagedEntriesAreSkippedNotTrusted() throws Exception {
        String json = "{\"format\":\"githubble-settings\",\"schema\":1,\"updatedAt\":5,"
                + "\"services\":[{\"id\":\"openai\",\"name\":\"x\",\"base_url\":\"https://x\"},"
                + "{\"id\":\"custom-ok\",\"name\":\"Ok\",\"base_url\":\"https://ok/v1\",\"protocol\":\"openai-chat\"}],"
                + "\"profiles\":{\"o\":{\"selected\":\"Bad Id!\",\"providers\":{\"BAD ID\":{\"model\":\"m\"},\"deepseek\":{\"model\":\"a\\nb\"}}}}}";
        ConnectionsSnapshot s = ConnectionsSnapshot.parse(json);
        assertEquals(1, s.services.size());
        assertTrue(s.services.containsKey("custom-ok"));
        assertTrue(s.profiles.isEmpty());
    }

    @Test public void decisionTable() {
        assertEquals(Action.UPLOAD, ConnectionsSnapshot.decide(false, 0, 0, false));
        assertEquals(Action.NONE, ConnectionsSnapshot.decide(true, 5, 5, false));
        assertEquals(Action.UPLOAD, ConnectionsSnapshot.decide(true, 5, 5, true));
        assertEquals(Action.DOWNLOAD, ConnectionsSnapshot.decide(true, 9, 5, false));
        assertEquals(Action.MERGE, ConnectionsSnapshot.decide(true, 9, 5, true));
    }

    @Test public void localWinsOnConflictAndFillsGaps() {
        ConnectionsSnapshot local = sample();
        ConnectionsSnapshot remote = sample();
        remote.profiles.get("owner").providers.get("openai").model = "gpt-4o";
        remote.profiles.get("owner").providers.get("openai").scope = "scope-x";
        remote.profiles.get("owner").favorites = Arrays.asList("a|m", "openai|gpt-5");
        ConnectionsSnapshot merged = ConnectionsSnapshot.merge(local, remote, false);
        ConnectionsSnapshot.Provider v = merged.profiles.get("owner").providers.get("openai");
        assertEquals("gpt-5", v.model);
        assertEquals("scope-x", v.scope);
        assertEquals(Arrays.asList("openai|gpt-5", "a|m"), merged.profiles.get("owner").favorites);
    }

    @Test public void remotePreferredReplacesValuesAndLists() {
        ConnectionsSnapshot local = sample();
        ConnectionsSnapshot remote = sample();
        remote.updatedAt = 300;
        remote.profiles.get("owner").providers.get("openai").model = "gpt-4o";
        remote.profiles.get("owner").favorites = Arrays.asList("a|m");
        ConnectionsSnapshot merged = ConnectionsSnapshot.merge(local, remote, true);
        assertEquals("gpt-4o", merged.profiles.get("owner").providers.get("openai").model);
        assertEquals(Arrays.asList("a|m"), merged.profiles.get("owner").favorites);
        assertEquals(300, merged.updatedAt);
    }

    @Test public void otherOwnersAndServicesAreKeptNotDeleted() {
        ConnectionsSnapshot local = new ConnectionsSnapshot();
        ConnectionsSnapshot remote = sample();
        ConnectionsSnapshot merged = ConnectionsSnapshot.merge(local, remote, false);
        assertTrue(merged.profiles.containsKey("owner"));
        assertTrue(merged.services.containsKey("custom-keydealer"));
    }

    @Test public void hashChangesWithContentOnly() {
        ConnectionsSnapshot a = sample();
        ConnectionsSnapshot b = sample();
        b.updatedAt = 999;
        assertEquals(a.contentHash(), b.contentHash());
        b.profiles.get("owner").providers.get("openai").model = "other";
        assertFalse(a.contentHash().equals(b.contentHash()));
    }
}
