package app.githubble.mobile.repository;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RepoMoreLogicTest {
    @Test public void workflowInputsAreReadFromYaml() {
        String yaml = "name: Release\non:\n  push:\n    branches: [main]\n  workflow_dispatch:\n    inputs:\n      version:\n        description: 'Версия релиза'\n        required: true\n        default: \"1.0.0\"\n        type: string\n"
            + "      dry_run:\n        type: boolean\n        default: false\njobs:\n  build:\n    runs-on: ubuntu-latest\n";
        List<ActionsOps.Input> inputs = ActionsOps.parseInputs(yaml);
        assertEquals(2, inputs.size());
        assertEquals("version", inputs.get(0).name); assertEquals("1.0.0", inputs.get(0).value); assertEquals("Версия релиза", inputs.get(0).description); assertEquals("string", inputs.get(0).type);
        assertEquals("dry_run", inputs.get(1).name); assertEquals("false", inputs.get(1).value); assertEquals("boolean", inputs.get(1).type);
        assertTrue(ActionsOps.parseInputs("on: [workflow_dispatch]\njobs: {}").isEmpty());
        assertTrue(ActionsOps.parseInputs("on:\n  workflow_dispatch:\njobs:\n  a: {}").isEmpty());
        assertTrue(ActionsOps.parseInputs(null).isEmpty());
    }

    @Test public void dispatchInputsAreValidated() {
        Map<String, String> m = ActionsOps.inputLines("a=1\nb = two \n\n");
        assertEquals("1", m.get("a")); assertEquals("two", m.get("b")); assertEquals(2, m.size());
        for (String bad : new String[]{"nothing", "1x=2", "=v"}) { try { ActionsOps.inputLines(bad); fail(bad); } catch (IllegalArgumentException expected) { } }
    }

    @Test public void runsAreParsed() throws Exception {
        List<ActionsOps.Run> runs = ActionsOps.parseRuns(new JSONObject("{\"workflow_runs\":[{\"id\":1,\"display_title\":\"T\",\"name\":\"CI\",\"head_branch\":\"main\",\"status\":\"completed\",\"conclusion\":\"failure\",\"event\":\"push\",\"created_at\":\"2026-01-01T00:00:00Z\",\"html_url\":\"u\",\"run_attempt\":2},"
            + "{\"id\":2,\"name\":\"CI\",\"status\":\"in_progress\",\"conclusion\":null}]}"));
        assertTrue(runs.get(0).failed()); assertFalse(runs.get(0).running()); assertEquals(2, runs.get(0).attempt); assertEquals("T", runs.get(0).title);
        assertTrue(runs.get(1).running()); assertEquals("CI", runs.get(1).title); assertEquals("", runs.get(1).conclusion);
        List<ActionsOps.Cache> caches = ActionsOps.parseCaches(new JSONObject("{\"actions_caches\":[{\"id\":4,\"key\":\"gradle\",\"ref\":\"refs/heads/main\",\"size_in_bytes\":1024}]}"));
        assertEquals("gradle", caches.get(0).key); assertEquals(1024L, caches.get(0).size);
        assertEquals(1, ActionsOps.parseWorkflows(new JSONObject("{\"workflows\":[{\"id\":9,\"name\":\"CI\",\"path\":\".github/workflows/ci.yml\",\"state\":\"active\"}]}")).size());
    }

    @Test public void dependabotConfigIsGenerated() {
        String yaml = DependabotConfig.generate(Arrays.asList(new DependabotConfig.Entry("gradle", "/", 5), new DependabotConfig.Entry("github-actions", "/", 3)), "weekly");
        assertTrue(yaml.startsWith("version: 2\nupdates:\n")); assertTrue(yaml.contains("package-ecosystem: \"gradle\"")); assertTrue(yaml.contains("interval: \"weekly\"")); assertTrue(yaml.contains("open-pull-requests-limit: 3"));
        try { DependabotConfig.generate(Arrays.asList(new DependabotConfig.Entry("gradle", "/", 5)), "hourly"); fail(); } catch (IllegalArgumentException expected) { }
        try { DependabotConfig.generate(Arrays.<DependabotConfig.Entry>asList(), "daily"); fail(); } catch (IllegalArgumentException expected) { }
        try { DependabotConfig.generate(Arrays.asList(new DependabotConfig.Entry("gradle", "/", 51)), "daily"); fail(); } catch (IllegalArgumentException expected) { }
        try { DependabotConfig.generate(Arrays.asList(new DependabotConfig.Entry("cobol", "/", 5)), "daily"); fail(); } catch (IllegalArgumentException expected) { }
        assertEquals("/", DependabotConfig.checkDirectory(""));
        try { DependabotConfig.checkDirectory("app"); fail(); } catch (IllegalArgumentException expected) { }
        assertEquals(new HashSet<>(Arrays.asList("gradle", "docker")), DependabotConfig.detect(new HashSet<>(Arrays.asList("build.gradle.kts", "Dockerfile", "README.md"))));
    }

    @Test public void filesAreEncodedAndChecked() throws Exception {
        assertEquals(".github/CODEOWNERS", RepoFiles.checkPath(" .github/CODEOWNERS "));
        try { RepoFiles.checkPath("../etc/passwd"); fail(); } catch (IllegalArgumentException expected) { }
        JSONObject body = RepoFiles.putBody("msg", "Привет", "main", "abc");
        assertEquals("Привет", RepoFiles.decode(body.getString("content"))); assertEquals("abc", body.getString("sha")); assertEquals("main", body.getString("branch"));
        assertFalse(RepoFiles.putBody("msg", "x", "", "").has("sha"));
        try { RepoFiles.putBody(" ", "x", "main", ""); fail(); } catch (IllegalArgumentException expected) { }
        RepoFiles.File f = RepoFiles.parse("a.md", new JSONObject("{\"type\":\"file\",\"sha\":\"s\",\"content\":\"aGk=\\n\"}"));
        assertEquals("hi", f.text); assertEquals("s", f.sha); assertTrue(f.exists);
        try { RepoFiles.parse("dir", new JSONObject("{\"type\":\"dir\"}")); fail(); } catch (IllegalArgumentException expected) { }
    }

    @Test public void labelsAndMilestonesAreChecked() throws Exception {
        assertEquals("d73a4a", RepoIssuesMeta.checkColor("#D73A4A"));
        try { RepoIssuesMeta.checkColor("xyz"); fail(); } catch (IllegalArgumentException expected) { }
        assertEquals("2026-02-03T00:00:00Z", RepoIssuesMeta.due("2026-02-03")); assertEquals(JSONObject.NULL, RepoIssuesMeta.due(" "));
        try { RepoIssuesMeta.due("03.02.2026"); fail(); } catch (IllegalArgumentException expected) { }
        try { RepoIssuesMeta.labelBody("", "ededed", ""); fail(); } catch (IllegalArgumentException expected) { }
        try { RepoIssuesMeta.milestoneBody(" ", "", "", true); fail(); } catch (IllegalArgumentException expected) { }
        assertEquals("closed", RepoIssuesMeta.milestoneBody("v1", "", "", false).getString("state"));
        assertEquals("type%2Fbug%20fix", Gh.component("type/bug fix"));
        List<RepoIssuesMeta.Milestone> ms = RepoIssuesMeta.parseMilestones(new JSONArray("[{\"number\":3,\"title\":\"v1\",\"description\":null,\"state\":\"open\",\"due_on\":null,\"open_issues\":2,\"closed_issues\":5}]"));
        assertEquals(3, ms.get(0).number); assertEquals("", ms.get(0).due); assertEquals(5, ms.get(0).closed);
        List<RepoIssuesMeta.Label> ls = RepoIssuesMeta.parseLabels(new JSONArray("[{\"name\":\"bug\",\"color\":\"d73a4a\",\"description\":null}]"));
        assertEquals("bug", ls.get(0).name); assertEquals("", ls.get(0).description);
    }

    @Test public void trafficIsSummarised() throws Exception {
        assertEquals("▁▂▅█", RepoTraffic.spark(Arrays.asList(0, 1, 4, 8)));
        assertEquals("▁▁", RepoTraffic.spark(Arrays.asList(0, 0)));
        assertEquals("", RepoTraffic.spark(Arrays.<Integer>asList()));
        RepoTraffic.Series s = RepoTraffic.parse(new JSONObject("{\"count\":10,\"uniques\":4,\"views\":[{\"count\":3},{\"count\":7}]}"), "views");
        assertEquals(10, s.count); assertEquals(4, s.uniques); assertEquals(Arrays.asList(3, 7), s.perDay);
        List<RepoTraffic.Item> items = RepoTraffic.items(new JSONArray("[{\"path\":\"/a\",\"count\":5,\"uniques\":2}]"), "path");
        assertEquals("/a", items.get(0).label); assertEquals(5, items.get(0).count);
    }
}
