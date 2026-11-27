package app.githubble.mobile.repository;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RepoOpsLogicTest {
    @Test public void releasesAreParsedAndValidated() throws Exception {
        List<RepoReleases.Release> list = RepoReleases.parse(new JSONArray("[{\"id\":5,\"tag_name\":\"v1\",\"name\":null,\"body\":null,\"draft\":true,\"prerelease\":false,\"html_url\":\"u\",\"published_at\":null,\"created_at\":\"2026-02-03T00:00:00Z\","
            + "\"assets\":[{\"id\":9,\"name\":\"app.apk\",\"size\":2048,\"download_count\":4}]}]"));
        RepoReleases.Release r = list.get(0);
        assertEquals("v1", r.title()); assertTrue(r.draft); assertEquals("2026-02-03T00:00:00Z", r.date); assertEquals(1, r.assets.size()); assertEquals(2048L, r.assets.get(0).size);
        JSONObject body = RepoReleases.createBody("v1", "main", "Name", "Body", false, true, false);
        assertEquals("v1", body.getString("tag_name")); assertEquals("main", body.getString("target_commitish")); assertTrue(body.getBoolean("prerelease")); assertEquals("Name", body.getString("name"));
        JSONObject plain = RepoReleases.createBody("v2", null, "", "", false, false, true);
        assertFalse(plain.has("target_commitish")); assertFalse(plain.has("name")); assertFalse(plain.has("body")); assertTrue(plain.getBoolean("generate_release_notes"));
        try { RepoReleases.checkTag("bad tag"); fail(); } catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("тега") || expected.getMessage().contains("ветк") == false); }
        assertEquals("2.0 КБ", RepoReleases.size(2048)); assertEquals("512 Б", RepoReleases.size(512)); assertEquals("1.5 МБ", RepoReleases.size(1572864));
        assertTrue(RepoReleases.isSha("0123456789abcdef0123456789abcdef01234567")); assertFalse(RepoReleases.isSha("main"));
        List<RepoReleases.Tag> tags = RepoReleases.parseTags(new JSONArray("[{\"name\":\"v1\",\"commit\":{\"sha\":\"abc\"}}]"));
        assertEquals("v1", tags.get(0).name); assertEquals("abc", tags.get(0).sha);
    }

    @Test public void pendingDeploymentsAreParsedAndReviewed() throws Exception {
        List<PendingDeployments.Item> items = PendingDeployments.parse(new JSONObject("{\"id\":7,\"display_title\":\"Deploy\",\"head_branch\":\"main\",\"html_url\":\"u\"}"),
            new JSONArray("[{\"environment\":{\"id\":3,\"name\":\"prod\"},\"wait_timer\":5,\"current_user_can_approve\":true},{\"foo\":1}]"));
        assertEquals(1, items.size());
        PendingDeployments.Item i = items.get(0);
        assertEquals(7L, i.runId); assertEquals(3L, i.envId); assertEquals("prod", i.env); assertEquals("Deploy", i.run); assertEquals("main", i.branch); assertTrue(i.canApprove); assertEquals(5, i.waitMinutes);
        JSONObject approve = PendingDeployments.body(3, true, "");
        assertEquals("approved", approve.getString("state")); assertEquals(3L, approve.getJSONArray("environment_ids").getLong(0)); assertTrue(approve.getString("comment").contains("GitHubble"));
        assertEquals("rejected", PendingDeployments.body(3, false, " no ").getString("state"));
        assertEquals("no", PendingDeployments.body(3, false, " no ").getString("comment"));
    }

    @Test public void branchStatusAndAgeAreComputed() throws Exception {
        BranchCleanup.Info merged = new BranchCleanup.Info("old", "s1", false);
        BranchCleanup.fill(merged, new JSONObject("{\"ahead_by\":0,\"behind_by\":3,\"merge_base_commit\":{\"commit\":{\"committer\":{\"date\":\"2026-01-01T00:00:00Z\"}}}}"));
        assertTrue(merged.merged()); assertEquals(3, merged.behind); assertEquals("2026-01-01T00:00:00Z", merged.date);
        BranchCleanup.Info ahead = new BranchCleanup.Info("feat", "s2", false);
        BranchCleanup.fill(ahead, new JSONObject("{\"ahead_by\":2,\"behind_by\":0,\"total_commits\":2,\"commits\":[{\"commit\":{\"committer\":{\"date\":\"2026-03-01T00:00:00Z\"}}},{\"commit\":{\"committer\":{\"date\":\"2026-03-05T00:00:00Z\"}}}]}"));
        assertFalse(ahead.merged()); assertEquals(2, ahead.ahead); assertEquals("2026-03-05T00:00:00Z", ahead.date);
        BranchCleanup.Info unknown = new BranchCleanup.Info("big", "s3", false);
        BranchCleanup.fill(unknown, new JSONObject("{\"ahead_by\":300,\"total_commits\":300,\"commits\":[{\"commit\":{\"committer\":{\"date\":\"2026-03-01T00:00:00Z\"}}}]}"));
        assertEquals("", unknown.date);
        long now = java.time.Instant.parse("2026-01-11T12:00:00Z").toEpochMilli();
        assertEquals(10L, BranchCleanup.ageDays("2026-01-01T00:00:00Z", now));
        assertEquals(-1L, BranchCleanup.ageDays("", now)); assertEquals(-1L, BranchCleanup.ageDays("garbage", now));
        assertTrue(BranchCleanup.stale(merged, 10, now)); assertFalse(BranchCleanup.stale(merged, 11, now)); assertFalse(BranchCleanup.stale(unknown, 1, now));
    }
}
