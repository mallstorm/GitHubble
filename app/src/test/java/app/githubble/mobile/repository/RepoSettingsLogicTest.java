package app.githubble.mobile.repository;

import app.githubble.mobile.network.GitHubApiClient;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RepoSettingsLogicTest {
    private static void rejects(Runnable run) { try { run.run(); fail("expected IllegalArgumentException"); } catch (IllegalArgumentException expected) { } }
    private interface Throwing { void run() throws Exception; }
    private static void rejectsChecked(Throwing run) throws Exception { try { run.run(); fail("expected IllegalArgumentException"); } catch (IllegalArgumentException expected) { } }

    @Test public void protectionIsReadAndWritten() throws Exception {
        BranchRules.Protection p = BranchRules.parse(new JSONObject("{\"required_pull_request_reviews\":{\"required_approving_review_count\":2,\"dismiss_stale_reviews\":true,\"require_code_owner_reviews\":false},"
            + "\"required_status_checks\":{\"strict\":true,\"contexts\":[\"ci\",\"build\"]},\"enforce_admins\":{\"enabled\":true},\"allow_force_pushes\":{\"enabled\":false}}"));
        assertTrue(p.exists); assertTrue(p.requirePr); assertEquals(2, p.approvals); assertTrue(p.dismissStale); assertTrue(p.requireChecks); assertTrue(p.strict);
        assertEquals(Arrays.asList("ci", "build"), p.contexts); assertTrue(p.enforceAdmins); assertFalse(p.allowForce);
        BranchRules.Protection next = new BranchRules.Protection();
        next.requirePr = true; next.approvals = 3;
        JSONObject body = BranchRules.body(next);
        assertEquals(3, body.getJSONObject("required_pull_request_reviews").getInt("required_approving_review_count"));
        assertTrue(body.isNull("required_status_checks")); assertTrue(body.isNull("restrictions"));
        next.approvals = 7;
        rejectsChecked(() -> BranchRules.body(next));
        assertEquals(Arrays.asList("ci", "lint"), BranchRules.splitContexts(" ci, lint\n ci "));
    }

    @Test public void rulesetPresetsMatchTheApi() throws Exception {
        JSONObject body = BranchRules.rulesetBody("protect", false, "", true, false, false, 0);
        assertEquals("branch", body.getString("target"));
        assertEquals("~DEFAULT_BRANCH", body.getJSONObject("conditions").getJSONObject("ref_name").getJSONArray("include").getString(0));
        assertEquals(1, body.getJSONArray("rules").length()); assertEquals("deletion", body.getJSONArray("rules").getJSONObject(0).getString("type"));
        JSONObject tags = BranchRules.rulesetBody("tags", true, "v*", true, true, true, 2);
        assertEquals("tag", tags.getString("target")); assertEquals(2, tags.getJSONArray("rules").length());
        rejectsChecked(() -> BranchRules.rulesetBody("none", false, "", false, false, false, 0));
        rejectsChecked(() -> BranchRules.rulesetBody(" ", false, "", true, false, false, 0));
        List<BranchRules.Ruleset> list = BranchRules.parseRulesets(new JSONArray("[{\"id\":5,\"name\":\"r\",\"target\":\"tag\",\"enforcement\":\"evaluate\"}]"));
        assertEquals(5L, list.get(0).id); assertEquals("evaluate", list.get(0).enforcement);
    }

    @Test public void pathsAndErrorsAreSafe() {
        assertEquals("feature/a%20b", Gh.segment("feature/a b"));
        rejects(() -> Gh.segment(" "));
        String text = Gh.explain(new GitHubApiClient.ApiException(403, "Resource not accessible by integration", 0), "Dependabot secrets");
        assertTrue(text.contains("Dependabot secrets")); assertTrue(text.contains("Review request"));
        assertTrue(Gh.explain(new GitHubApiClient.ApiException(403, "Upgrade to GitHub Pro", 0), "Administration").contains("тариф"));
        assertEquals("Dependabot secrets", RepoSecrets.permission(RepoSecrets.Kind.DEPENDABOT_SECRET, null));
        assertEquals("Environments", RepoSecrets.permission(RepoSecrets.Kind.ACTIONS_SECRET, "prod"));
    }

    @Test public void actionsPermissionsAreRead() throws Exception {
        ActionsPermissions.State s = new ActionsPermissions.State();
        ActionsPermissions.read(s, new JSONObject("{\"enabled\":true,\"allowed_actions\":\"selected\"}"), new JSONObject("{\"default_workflow_permissions\":\"write\",\"can_approve_pull_request_reviews\":true}"),
            new JSONObject("{\"approval_policy\":\"all_external_contributors\"}"), new JSONObject("{\"github_owned_allowed\":true,\"verified_allowed\":true,\"patterns_allowed\":[\"a/*\"]}"));
        assertEquals("selected", s.allowed); assertEquals("write", s.token); assertTrue(s.canApprove); assertEquals("all_external_contributors", s.fork); assertTrue(s.verified);
        assertEquals(Arrays.asList("a/*"), s.patterns);
        assertEquals(Arrays.asList("a/*", "b/c@v1"), ActionsPermissions.splitPatterns("a/*, b/c@v1\na/*"));
    }

    @Test public void webhooksAreValidated() throws Exception {
        assertEquals(Arrays.asList("push"), RepoWebhooks.events(""));
        assertEquals(Arrays.asList("push", "pull_request"), RepoWebhooks.events("push, pull_request"));
        assertEquals(Arrays.asList("*"), RepoWebhooks.events("*, push"));
        rejects(() -> RepoWebhooks.events("Push"));
        rejects(() -> RepoWebhooks.checkUrl("ftp://x"));
        assertEquals("https://example.com/hook", RepoWebhooks.checkUrl(" https://example.com/hook "));
        List<RepoWebhooks.Hook> hooks = RepoWebhooks.parse(new JSONArray("[{\"id\":9,\"active\":true,\"events\":[\"push\"],\"config\":{\"url\":\"https://e.x/h\",\"content_type\":\"json\"},\"last_response\":{\"code\":200,\"status\":\"active\"}}]"));
        assertEquals(9L, hooks.get(0).id); assertEquals("200 active", hooks.get(0).lastStatus);
        JSONObject body = RepoWebhooks.body("https://e.x/h", "form", "s3", true, Arrays.asList("push"));
        assertEquals("form", body.getJSONObject("config").getString("content_type")); assertEquals("s3", body.getJSONObject("config").getString("secret"));
    }

    @Test public void collaboratorsAreChecked() throws Exception {
        assertEquals("octo", Collaborators.checkUser("@octo"));
        rejects(() -> Collaborators.checkUser("a b"));
        rejects(() -> Collaborators.checkRole("owner"));
        List<Collaborators.Person> people = Collaborators.parsePeople(new JSONArray("[{\"login\":\"u\",\"permissions\":{\"admin\":false,\"maintain\":false,\"push\":true,\"triage\":true,\"pull\":true}}]"));
        assertEquals("push", people.get(0).role); assertEquals("Write", Collaborators.label("push"));
        List<Collaborators.Invite> invites = Collaborators.parseInvites(new JSONArray("[{\"id\":4,\"permissions\":\"read\",\"invitee\":{\"login\":\"z\"}}]"));
        assertEquals("z", invites.get(0).login);
    }

    @Test public void generalSettingsAreChecked() throws Exception {
        assertEquals(Arrays.asList("java", "android-app"), RepoGeneral.topics("#Java, android-app java"));
        rejects(() -> RepoGeneral.topics("bad_topic"));
        rejects(() -> RepoGeneral.checkHomepage("example.com"));
        assertEquals("", RepoGeneral.checkHomepage(" "));
        RepoGeneral.State s = new RepoGeneral.State();
        rejectsChecked(() -> RepoGeneral.body(s));
        s.squash = true; s.description = "d";
        assertEquals("d", RepoGeneral.body(s).getString("description"));
        RepoGeneral.State read = RepoGeneral.parse(new JSONObject("{\"description\":null,\"default_branch\":\"dev\",\"has_issues\":true,\"allow_squash_merge\":false,\"topics\":[\"x\"]}"), null);
        assertEquals("", read.description); assertEquals("dev", read.defaultBranch); assertTrue(read.issues); assertFalse(read.squash); assertEquals(Arrays.asList("x"), read.topics);
    }

    @Test public void pagesAreChecked() throws Exception {
        assertEquals("docs.example.com", RepoPages.checkDomain("Docs.Example.com"));
        assertEquals("", RepoPages.checkDomain(""));
        rejects(() -> RepoPages.checkDomain("not a domain"));
        rejects(() -> RepoPages.checkPath("/site"));
        RepoPages.State s = RepoPages.parse(new JSONObject("{\"html_url\":\"https://o.github.io/r/\",\"status\":\"built\",\"build_type\":\"legacy\",\"cname\":null,\"source\":{\"branch\":\"gh-pages\",\"path\":\"/docs\"}}"));
        assertTrue(s.exists); assertFalse(s.workflow); assertEquals("gh-pages", s.branch); assertEquals("/docs", s.path); assertEquals("", s.cname);
        assertEquals("/docs", RepoPages.source("main", "/docs").getString("path"));
    }

    @Test public void securityRequestsMatchTheApi() throws Exception {
        JSONObject dependabot = RepoSecurity.dismissBody(RepoSecurity.Kind.DEPENDABOT, "inaccurate", "x");
        assertEquals("dismissed", dependabot.getString("state")); assertEquals("inaccurate", dependabot.getString("dismissed_reason"));
        JSONObject secret = RepoSecurity.dismissBody(RepoSecurity.Kind.SECRET, "revoked", "");
        assertEquals("resolved", secret.getString("state")); assertEquals("revoked", secret.getString("resolution")); assertFalse(secret.has("resolution_comment"));
        rejectsChecked(() -> RepoSecurity.dismissBody(RepoSecurity.Kind.CODE, "bogus", ""));
        assertEquals("enabled", RepoSecurity.analysisBody("secret_scanning", true).getJSONObject("security_and_analysis").getJSONObject("secret_scanning").getString("status"));
        rejectsChecked(() -> RepoSecurity.analysisBody("other", true));
        RepoSecurity.Alert alert = RepoSecurity.parse(RepoSecurity.Kind.DEPENDABOT, new JSONObject("{\"number\":3,\"state\":\"open\",\"html_url\":\"u\",\"security_advisory\":{\"summary\":\"Bad\",\"severity\":\"high\"},\"dependency\":{\"package\":{\"name\":\"lib\"},\"manifest_path\":\"pom.xml\"}}"));
        assertEquals("lib: Bad", alert.title); assertEquals("high", alert.severity); assertEquals("pom.xml", alert.detail);
    }

    @Test public void customPropertiesAreMergedAndValidated() throws Exception {
        List<CustomProperties.Property> list = CustomProperties.merge(new JSONArray("[{\"property_name\":\"tier\",\"value_type\":\"single_select\",\"required\":true,\"allowed_values\":[\"a\",\"b\"]},"
            + "{\"property_name\":\"tags\",\"value_type\":\"multi_select\",\"allowed_values\":[\"x\",\"y\"]}]"), new JSONArray("[{\"property_name\":\"tier\",\"value\":\"a\"},{\"property_name\":\"tags\",\"value\":[\"x\",\"y\"]}]"));
        assertEquals("a", list.get(0).value); assertEquals("x, y", list.get(1).value);
        JSONObject body = CustomProperties.body(list);
        assertEquals(2, body.getJSONArray("properties").getJSONObject(1).getJSONArray("value").length());
        list.get(0).value = "z";
        rejectsChecked(() -> CustomProperties.body(list));
        list.get(0).value = "";
        rejectsChecked(() -> CustomProperties.body(list));
        assertEquals("octo", CustomProperties.owner("octo/repo"));
    }
}
