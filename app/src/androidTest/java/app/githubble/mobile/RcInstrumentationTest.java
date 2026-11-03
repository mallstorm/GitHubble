package app.githubble.mobile;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import app.githubble.mobile.build.BuildAutoDownloadPolicy;
import app.githubble.mobile.build.BuildMonitor;
import app.githubble.mobile.activity.RepositoryActivityFeed;
import app.githubble.mobile.build.BuildRunFilter;
import app.githubble.mobile.ui.BottomNavigationBar;
import app.githubble.mobile.controller.AccountOperationsController;
import app.githubble.mobile.controller.CommitDetailController;
import app.githubble.mobile.controller.ProfileController;
import app.githubble.mobile.auth.DeviceCodeAssist;
import app.githubble.mobile.database.SchemaMigrationRegistry;
import app.githubble.mobile.diff.DiffModel;
import app.githubble.mobile.network.GitHubApiClient;
import app.githubble.mobile.offline.OfflineActionQueue;
import app.githubble.mobile.state.FeatureUiState;
import app.githubble.mobile.state.ScreenStateStore;
import app.githubble.mobile.tracking.TrackingDatabase;
import app.githubble.mobile.util.DeviceCodeFormatter;
import app.githubble.mobile.util.PaginationState;
import app.githubble.mobile.util.RepositoryPathPolicy;
import app.githubble.mobile.search.SmartSearchPlanner;
import app.githubble.mobile.search.RepositorySearchRanker;
import app.githubble.mobile.search.AdvancedSearchPlanner;
import app.githubble.mobile.search.SearchFilters;
import app.githubble.mobile.search.SearchMode;
import org.json.JSONObject;

/** Dependency-free RC instrumentation suite for API 28/33/36 device matrix. */
public final class RcInstrumentationTest extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        super.onStart();
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            testDeviceCodeNormalization();
            testDeviceCodeAssist(context);
            testPagination();
            testRotationAndProcessDeathState(context);
            testOfflineQueue(context);
            testDatabaseSchemas(context);
            testGitHubErrorStates();
            testDiffParsing();
            testGitAgentProjectRuntime();
            testSmartRussianSearch();
            testSmartSearchRanking();
            testAdvancedSearchModes();
            testRepositoryImportPathSafety();
            testBuildAutoDownloadPolicy();
            testRc41StabilizationPolicies();
            testPrOnlyChecksNotRelease();
            testRc42UiNavigation();
            testRc425ProfileEditing();
            testRepositoryActivityRanking(context);
            testPrivateCommitCard();
            testNativeCommitDetail();
            GitAgentUiChecks.run(this);
            result.putString("result", "PASS: RC instrumentation suite");
            finish(0, result);
        } catch (Throwable error) {
            // Preserve the causal chain on the test device: ExceptionInInitializerError
            // frequently hides the root exception if only its top-level message is sent.
            android.util.Log.e("AndroidRuntime", "GitHubble instrumentation failed", error);
            Throwable root = error;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            StackTraceElement[] frames = root.getStackTrace();
            String location = frames.length == 0 ? "" : " at " + frames[0];
            result.putString("result", "FAIL: " + root.getClass().getSimpleName() +
                ": " + root.getMessage() + location);
            finish(-1, result);
        }
    }

    private static JSONObject run(long id,String status,String conclusion,
                                  String name,long artifactId) throws Exception {
        org.json.JSONArray artifacts=new org.json.JSONArray();
        if(name!=null)artifacts.put(new JSONObject().put("id",artifactId)
                .put("name",name).put("size_in_bytes",6L*1024*1024)
                .put("expired",false));
        return new JSONObject().put("id",id).put("status",status)
                .put("conclusion",conclusion).put("artifacts",artifacts);
    }

    private static void testBuildAutoDownloadPolicy() throws Exception {
        org.json.JSONArray history=new org.json.JSONArray()
                .put(run(104,"completed","failure","app-apk",1040))
                .put(run(103,"completed","success","app-apk",1030))
                .put(run(102,"completed","success","app-apk",1020))
                .put(run(101,"completed","success","app-apk",1010));
        eq(103L,BuildAutoDownloadPolicy.latestSuccessfulRunId(history));
        // Opt-in baseline, already queued and failed workflow runs do not trigger old APKs.
        yes(BuildAutoDownloadPolicy.selectArtifact(history,103L,0L)==null);
        yes(BuildAutoDownloadPolicy.selectArtifact(history,101L,103L)==null);
        eq(1030L,BuildAutoDownloadPolicy.selectArtifact(history,101L,0L).optLong("id"));
        // A newer successful run whose artifacts are still being published blocks
        // downloading the older successful run.
        org.json.JSONArray unpublished=new org.json.JSONArray()
                .put(run(105,"completed","success",null,0))
                .put(run(103,"completed","success","app-apk",1030));
        yes(BuildAutoDownloadPolicy.selectArtifact(unpublished,101L,0L)==null);
        // The same run becomes eligible as soon as GitHub publishes the artifact.
        unpublished=new org.json.JSONArray()
                .put(run(105,"completed","success","app-android-apk",1050))
                .put(run(103,"completed","success","app-apk",1030));
        eq(1050L,BuildAutoDownloadPolicy.selectArtifact(unpublished,101L,0L).optLong("id"));
        // A successful non-APK workflow does not accidentally download source-only ZIP.
        org.json.JSONArray sources=new org.json.JSONArray()
                .put(run(106,"completed","success","GitHubble-main-sources",1060))
                .put(run(105,"completed","success","app-apk",1050));
        yes(BuildAutoDownloadPolicy.selectArtifact(sources,101L,0L)==null);
        // APK release artifact wins over a mixed GitHubble archive from the SAME run.
        JSONObject mixed=run(107,"completed","success","githubble-release",1070);
        mixed.getJSONArray("artifacts").put(new JSONObject().put("id",1071L)
                .put("name","android-apk").put("size_in_bytes",7000000L));
        eq(1071L,BuildAutoDownloadPolicy.bestApkArtifact(
                mixed.getJSONArray("artifacts")).optLong("id"));
        JSONObject expired=run(108,"completed","success","app-apk",1080);
        expired.getJSONArray("artifacts").getJSONObject(0).put("expired",true);
        yes(BuildAutoDownloadPolicy.bestApkArtifact(expired.getJSONArray("artifacts"))==null);
    }

    /** RC4.1: aggregated notification slots and explicit partial inventory states. */
    private static void testRc41StabilizationPolicies() {
        String repo="mallstorm/GitHubble";
        eq(BuildMonitor.notificationTag("Mallstorm",repo),
                BuildMonitor.notificationTag("mallstorm",repo));
        no(BuildMonitor.notificationTag("mallstorm",repo).equals(
                BuildMonitor.notificationTag("mallstorm","mallstorm/Other")));
        yes(AccountOperationsController.inventoryIsPartial(3,6,0,false,false));
        yes(AccountOperationsController.inventoryIsPartial(6,6,1,false,false));
        yes(AccountOperationsController.inventoryIsPartial(6,6,0,true,false));
        yes(AccountOperationsController.inventoryIsPartial(6,6,0,false,true));
        no(AccountOperationsController.inventoryIsPartial(6,6,0,false,false));
    }

    /** RC4.2: root tabs, status filters and pagination boundary checks. */
    private static void testRc42UiNavigation() {
        eq(5,BottomNavigationBar.DESTINATIONS.length);
        yes(BottomNavigationBar.isRoot("Репозитории"));
        yes(BottomNavigationBar.isRoot("Настройки"));
        no(BottomNavigationBar.isRoot("Профиль"));
        eq("Настройки",BottomNavigationBar.DESTINATIONS[4]);
        no(BottomNavigationBar.isRoot("Центр сборок"));
        yes(BuildRunFilter.accepts(BuildRunFilter.ALL,"queued",""));
        yes(BuildRunFilter.accepts(BuildRunFilter.RUNNING,"in_progress",""));
        no(BuildRunFilter.accepts(BuildRunFilter.RUNNING,"completed","success"));
        yes(BuildRunFilter.accepts(BuildRunFilter.SUCCESS,"completed","success"));
        no(BuildRunFilter.accepts(BuildRunFilter.SUCCESS,"completed","failure"));
        yes(BuildRunFilter.accepts(BuildRunFilter.OTHER,"completed","cancelled"));
        no(BuildRunFilter.accepts(BuildRunFilter.OTHER,"in_progress",""));
    }

    /** RC4.2.5: editable fields are correctly filtered; immutable account metadata is never PATCHed. */
    private static void testRc425ProfileEditing() throws Exception {
        JSONObject before=new JSONObject()
                .put("login","developer")
                .put("id",100)
                .put("name","Old")
                .put("bio","")
                .put("hireable",false)
                .put("email",JSONObject.NULL)
                .put("avatar_url","https://avatars.githubusercontent.com/u/100");
        JSONObject draft=new JSONObject()
                .put("login","renamed")
                .put("name","New")
                .put("bio","New text")
                .put("hireable",true)
                .put("email","")
                .put("avatar_url","https://example.invalid/avatar.png");
        JSONObject patch=ProfileController.editablePatch(before,draft);
        eq("New",patch.optString("name"));
        eq("New text",patch.optString("bio"));
        yes(patch.optBoolean("hireable",false));
        no(patch.has("login"));
        no(patch.has("id"));
        no(patch.has("avatar_url"));
        no(patch.has("email"));
        eq(0,ProfileController.editablePatch(before,new JSONObject()
                .put("name","Old").put("bio","").put("hireable",false)).length());
    }

    /** Inactive new projects must fall behind repositories with newer real work. */
    private static void testRepositoryActivityRanking(Context context) throws Exception {
        org.json.JSONArray repositories=new org.json.JSONArray()
            .put(new JSONObject().put("full_name","owner/new-idle")
                .put("created_at","2026-09-17T10:00:00Z")
                .put("pushed_at","2026-09-17T10:00:00Z"))
            .put(new JSONObject().put("full_name","owner/active")
                .put("created_at","2026-01-01T10:00:00Z")
                .put("pushed_at","2026-09-20T22:00:00Z"))
            .put(new JSONObject().put("full_name","owner/older")
                .put("created_at","2026-01-01T10:00:00Z")
                .put("pushed_at","2026-09-15T10:00:00Z"));
        android.content.SharedPreferences testPrefs=context.getSharedPreferences(
                "rc42_activity_ranking_test",Context.MODE_PRIVATE);
        testPrefs.edit().clear().commit();
        java.util.List<JSONObject> ranked=RepositoryActivityFeed.rank(
                repositories,new java.util.ArrayList<>(),testPrefs,"test-account");
        eq("owner/active",ranked.get(0).optString("repo"));
        eq("owner/new-idle",ranked.get(1).optString("repo"));
        RepositoryActivityFeed.noteVisit(testPrefs,"test-account","owner/older",
                RepositoryActivityFeed.epoch("2026-09-21T00:15:00Z"));
        ranked=RepositoryActivityFeed.rank(repositories,new java.util.ArrayList<>(),
                testPrefs,"test-account");
        eq("owner/older",ranked.get(0).optString("repo"));
        java.util.List<JSONObject> events=new java.util.ArrayList<>();
        events.add(new JSONObject().put("repo","owner/active")
                .put("updated_at","2026-09-21T00:30:00Z")
                .put("title","fix: permissions"));
        ranked=RepositoryActivityFeed.rank(repositories,events,testPrefs,"test-account");
        eq("owner/active",ranked.get(0).optString("repo"));
        RepositoryActivityFeed.clearVisits(testPrefs,"test-account");
        no(testPrefs.contains(RepositoryActivityFeed.visitKey("test-account","owner/older")));
        testPrefs.edit().clear().commit();
    }

    /** Actual message, SHA, author and timestamp must survive private commit parsing. */
    private static void testPrivateCommitCard() throws Exception {
        String sha="0123456789abcdef0123456789abcdef01234567";
        JSONObject commit=new JSONObject()
            .put("sha",sha)
            .put("author",new JSONObject().put("login","developer"))
            .put("commit",new JSONObject()
                .put("message","feat(rc4.2): repository activity\n\nAdds live history")
                .put("committer",new JSONObject().put("date","2026-09-20T21:47:00Z")));
        JSONObject event=RepositoryActivityFeed.parseCommit("owner/private-repo",commit);
        yes(event!=null);
        eq("Commit",event.optString("kind"));
        eq("feat(rc4.2): repository activity",event.optString("title"));
        eq("Adds live history",event.optString("summary"));
        eq(sha,event.optString("sha"));
        eq("developer",event.optString("author"));
        eq("2026-09-20T21:47:00Z",event.optString("updated_at"));
        eq("https://github.com/owner/private-repo/commit/"+sha,event.optString("commit_url"));
        yes(RepositoryActivityFeed.parseCommit("owner/private-repo",
                new JSONObject().put("sha","bad"))==null);
    }

    /** Commit metadata and per-file patch are rendered without a browser. */
    private static void testNativeCommitDetail() throws Exception {
        String sha="0123456789abcdef0123456789abcdef01234567";
        yes(CommitDetailController.validRepository("mallstorm/GitHubble"));
        yes(CommitDetailController.validSha(sha));
        no(CommitDetailController.validSha("not-a-commit"));
        no(CommitDetailController.validRepository("../private"));
        no(CommitDetailController.validRepository("owner/repo?tab=actions"));
        org.json.JSONArray files=new org.json.JSONArray();
        java.util.Set<String> seen=new java.util.LinkedHashSet<>();
        JSONObject file=new JSONObject()
                .put("filename",".github/workflows/android.yml")
                .put("status","modified").put("additions",9).put("deletions",4)
                .put("patch","@@ -1,2 +1,2 @@\n-old\n+new\n kept");
        org.json.JSONArray page=new org.json.JSONArray().put(file)
                .put(new JSONObject().put("filename","app/src/main/java/Example.java")
                        .put("status","added").put("patch","@@ -0,0 +1 @@\n+code"));
        CommitDetailController.appendUnique(files,page,seen);
        CommitDetailController.appendUnique(files,page,seen);
        eq(2,files.length());
        eq(9,files.optJSONObject(0).optInt("additions"));
        eq(4,files.optJSONObject(0).optInt("deletions"));
        eq("modified",files.optJSONObject(0).optString("status"));
        DiffModel diff=DiffModel.parse(files.optJSONObject(0).optString("patch"));
        eq(2,diff.changedLines());
        eq(DiffModel.Kind.DELETE,diff.lines.get(1).kind);
        eq(DiffModel.Kind.ADD,diff.lines.get(2).kind);
    }

    /** A review CI run must not produce an installable-build notification. */
    private static void testPrOnlyChecksNotRelease() {
        yes(BuildMonitor.isReviewCheck("pull_request"));
        yes(BuildMonitor.isReviewCheck("pull_request_target"));
        no(BuildMonitor.isReviewCheck("push"));
        no(BuildMonitor.isReviewCheck("workflow_dispatch"));
    }

    private static void testDeviceCodeNormalization() {
        eq("85059224", DeviceCodeFormatter.normalize("8505-9224"));
        eq("AB12CD34", DeviceCodeFormatter.normalize("ab12 cd34"));
    }

    private static void testDeviceCodeAssist(Context context) {
        DeviceCodeAssist.begin(context, "ab12-CD34", System.currentTimeMillis()+90_000L);
        eq("AB12CD34", DeviceCodeAssist.currentCode(context));
        DeviceCodeAssist.clear(context);
        eq("", DeviceCodeAssist.currentCode(context));
        // Expired codes must never be copied or used for autofill.
        DeviceCodeAssist.begin(context, "AB12-CD34", System.currentTimeMillis()-1);
        eq("", DeviceCodeAssist.currentCode(context));
        no(DeviceCodeAssist.copy(context));
        DeviceCodeAssist.begin(context, "invalid", System.currentTimeMillis()+90_000L);
        eq("", DeviceCodeAssist.currentCode(context));
    }

    private static void testPagination() {
        PaginationState rest = new PaginationState();
        yes(rest.begin()); no(rest.begin()); rest.completeRest(50, 50); eq(2, rest.page());
        PaginationState gql = new PaginationState();
        yes(gql.begin()); gql.completeGraphQl("cursor-1", true); eq("cursor-1", gql.cursor());
    }

    private static void testRotationAndProcessDeathState(Context context) throws Exception {
        FeatureUiState before = new FeatureUiState();
        before.diffMode = "split"; before.collapseDiffContext = false; before.lastAdminRepository = "owner/repo";
        Bundle rotation = new Bundle(); before.save(rotation);
        FeatureUiState after = new FeatureUiState(); after.restore(rotation);
        eq("split", after.diffMode); no(after.collapseDiffContext); eq("owner/repo", after.lastAdminRepository);

        ScreenStateStore first = new ScreenStateStore(context);
        first.clear(); first.remember("repository", new JSONObject().put("full_name", "owner/repo")); first.setScroll(420);
        ScreenStateStore recreated = new ScreenStateStore(context); recreated.restore(null);
        eq("repository", recreated.route()); eq("owner/repo", recreated.args().optString("full_name")); eq(420, recreated.scroll());
        recreated.clear();
    }

    private static void testOfflineQueue(Context context) {
        OfflineActionQueue queue = new OfflineActionQueue(context);
        queue.clearAll();
        OfflineActionQueue.Action action = new OfflineActionQueue.Action();
        action.method = "PATCH"; action.url = "/repos/owner/repo/issues/1"; action.entityKey = "issue:owner/repo#1";
        action.summary = "RC queue check"; action.body = "{\"title\":\"offline\"}";
        long id = queue.enqueue(action); yes(id > 0); eq(1, queue.count(OfflineActionQueue.PENDING));
        queue.retry(id, true); eq(1, queue.listAll().size()); queue.clearAll(); eq(0, queue.listAll().size());
    }

    private static void testDatabaseSchemas(Context context) {
        eq(2, SchemaMigrationRegistry.TRACKING_SCHEMA); eq(3, SchemaMigrationRegistry.OFFLINE_QUEUE_SCHEMA);
        TrackingDatabase tracking = new TrackingDatabase(context); tracking.getWritableDatabase(); tracking.clearAll();
        OfflineActionQueue queue = new OfflineActionQueue(context); queue.getWritableDatabase(); queue.clearAll();
    }

    private static void testGitHubErrorStates() {
        GitHubApiClient.ApiException unauthorized = new GitHubApiClient.ApiException(401, "Unauthorized", 0);
        GitHubApiClient.ApiException forbidden = new GitHubApiClient.ApiException(403, "Forbidden", 123);
        GitHubApiClient.ApiException validation = new GitHubApiClient.ApiException(422, "Validation failed", 0);
        eq(401, unauthorized.statusCode); eq(403, forbidden.statusCode); eq(422, validation.statusCode);
        eq(123L, forbidden.rateLimitResetEpochSeconds);
    }

    private static void testDiffParsing() {
        DiffModel model = DiffModel.parse("@@ -1,2 +1,2 @@\n-old\n+new\n context");
        yes(model.changedLines() >= 2);
    }


    private static void testGitAgentProjectRuntime() {
        yes(app.githubble.mobile.agent.ProjectRuntime.allowed("list_files",false,true));
        yes(app.githubble.mobile.agent.ProjectRuntime.allowed("repository_info",true,false));
        no(app.githubble.mobile.agent.ProjectRuntime.allowed("write_file",true,true));
        no(app.githubble.mobile.agent.ProjectRuntime.allowed("delete_repository",true,true));
    }

    private static void testSmartRussianSearch() {
        SmartSearchPlanner.Plan telegram = SmartSearchPlanner.plan("клиент телеграм для android на kotlin", true);
        yes(telegram.expanded());
        yes(telegram.variants.get(0).contains("telegram client"));
        yes(telegram.variants.get(0).contains("android"));
        SmartSearchPlanner.Plan qualified = SmartSearchPlanner.plan("ошибка авторизации language:Kotlin stars:>100", true);
        eq("language:Kotlin stars:>100", qualified.qualifiers);
        yes(qualified.variants.get(0).contains("authentication"));
        yes(qualified.variants.get(0).contains("language:Kotlin"));
        SmartSearchPlanner.Plan direct = SmartSearchPlanner.plan("kotlin android", true);
        no(direct.expanded()); eq("kotlin android", direct.primary());
    }

    private static void testSmartSearchRanking() throws Exception {
        SmartSearchPlanner.Plan plan = SmartSearchPlanner.plan("клиент телеграм android", true);
        org.json.JSONArray first = new org.json.JSONArray()
                .put(new JSONObject().put("full_name", "owner/a").put("description", "Telegram client for Android").put("stargazers_count", 10))
                .put(new JSONObject().put("full_name", "owner/b").put("description", "Other project").put("stargazers_count", 5000));
        org.json.JSONArray second = new org.json.JSONArray()
                .put(new JSONObject().put("full_name", "owner/a").put("description", "Telegram app Android").put("stargazers_count", 10))
                .put(new JSONObject().put("full_name", "owner/c").put("description", "Messaging").put("stargazers_count", 20));
        java.util.List<org.json.JSONArray> batches = new java.util.ArrayList<>(); batches.add(first); batches.add(second);
        java.util.List<RepositorySearchRanker.Hit> hits = RepositorySearchRanker.merge(batches, plan);
        eq(3, hits.size()); eq("owner/a", hits.get(0).repository.optString("full_name")); eq(2, hits.get(0).matchedVariants);
    }

    private static void testRepositoryImportPathSafety() {
        eq("src/main/App.java", RepositoryPathPolicy.normalize("/src//main/./App.java"));
        yes(RepositoryPathPolicy.shouldIgnore("project/.git/config"));
        yes(RepositoryPathPolicy.isWorkflow(".github/workflows/android.yml"));
        try { RepositoryPathPolicy.normalize("../../escape"); throw new AssertionError("Unsafe import path accepted"); } catch (IllegalArgumentException expected) { }
    }

    private static void testAdvancedSearchModes() {
        SearchFilters issues=new SearchFilters(); issues.issueKind="pr"; issues.issueState="open";
        AdvancedSearchPlanner.Plan issuePlan=AdvancedSearchPlanner.plan("ошибка авторизации label:bug stars:>10",true,SearchMode.ISSUES_PRS,issues);
        yes(issuePlan.variants.get(0).contains("is:pr")); yes(issuePlan.variants.get(0).contains("is:open"));
        yes(issuePlan.rejectedQualifiers.contains("stars:>10"));

        SearchFilters code=new SearchFilters(); code.language="Java"; code.ownerOrOrg="org:openai";
        AdvancedSearchPlanner.Plan codePlan=AdvancedSearchPlanner.plan("обработка токена path:src stars:>10",true,SearchMode.CODE,code);
        yes(codePlan.variants.get(0).contains("handling")); yes(codePlan.variants.get(0).contains("token"));
        yes(codePlan.variants.get(0).contains("path:src")); yes(codePlan.variants.get(0).contains("language:Java"));
        yes(codePlan.rejectedQualifiers.contains("stars:>10"));

        SearchFilters people=new SearchFilters(); people.peopleKind="org";
        AdvancedSearchPlanner.Plan peoplePlan=AdvancedSearchPlanner.plan("андроид",true,SearchMode.PEOPLE,people);
        yes(peoplePlan.variants.get(0).contains("android")); yes(peoplePlan.variants.get(0).contains("type:org"));
    }

    private static void yes(boolean value){if(!value)throw new AssertionError("Expected true");}
    private static void no(boolean value){if(value)throw new AssertionError("Expected false");}
    private static void eq(Object expected,Object actual){if(expected==null?actual!=null:!expected.equals(actual))throw new AssertionError("Expected "+expected+", got "+actual);}
}

