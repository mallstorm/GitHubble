package app.githubble.mobile;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import app.githubble.mobile.build.BuildAutoDownloadPolicy;
import app.githubble.mobile.auth.DeviceCodeAssist;
import app.githubble.mobile.database.SchemaMigrationRegistry;
import app.githubble.mobile.controller.CopilotAgentController;
import app.githubble.mobile.controller.CopilotPermissionReport;
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
            testCopilotParsing();
            testSmartRussianSearch();
            testSmartSearchRanking();
            testAdvancedSearchModes();
            testRepositoryImportPathSafety();
            testBuildAutoDownloadPolicy();
            result.putString("result", "PASS: RC instrumentation suite");
            finish(0, result);
        } catch (Throwable error) {
            result.putString("result", "FAIL: " + error.getClass().getSimpleName() + ": " + error.getMessage());
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

    private static void testDeviceCodeNormalization() {
        eq("85059224", DeviceCodeFormatter.normalize("8505-9224"));
        eq("AB12CD34", DeviceCodeFormatter.normalize("ab12 cd34"));
    }

    private static void testDeviceCodeAssist(Context context) {
        DeviceCodeAssist.begin(context, "ab12-CD34", System.currentTimeMillis()+90_000L);
        eq("AB12CD34", DeviceCodeAssist.currentCode(context));
        no(DeviceCodeAssist.isArmed(context));
        yes(DeviceCodeAssist.arm(context));
        yes(DeviceCodeAssist.isArmed(context));
        DeviceCodeAssist.disarm(context);
        no(DeviceCodeAssist.isArmed(context));
        DeviceCodeAssist.clear(context);
        eq("", DeviceCodeAssist.currentCode(context));
        // Expired codes must never be copied or used for autofill.
        DeviceCodeAssist.begin(context, "AB12-CD34", System.currentTimeMillis()-1);
        eq("", DeviceCodeAssist.currentCode(context));
        no(DeviceCodeAssist.arm(context));
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


    private static void testCopilotParsing() throws Exception {
        JSONObject task = new JSONObject()
                .put("id", "task-1")
                .put("state", "waiting_for_user")
                .put("repository_full_name", "owner/repo")
                .put("artifacts", new org.json.JSONArray().put(new JSONObject()
                        .put("provider", "github")
                        .put("type", "pull")
                        .put("data", new JSONObject().put("number", 42))));
        eq("owner/repo", CopilotAgentController.repositoryFromTask(task));
        eq(42, CopilotAgentController.pullNumber(task));
        eq("Нужен ответ", CopilotAgentController.stateLabel("waiting_for_user"));
        yes(CopilotAgentController.isActiveState("in_progress"));
        no(CopilotAgentController.archiveMutationAvailable());
        CopilotPermissionReport report = new CopilotPermissionReport();
        report.add(new CopilotPermissionReport.Check("metadata_read", "Metadata", true, true, "ok"));
        report.add(new CopilotPermissionReport.Check("agent_tasks_read", "Tasks", true, true, "ok"));
        report.add(new CopilotPermissionReport.Check("contents_read", "Contents", true, true, "ok"));
        yes(report.readyForSafeAttempt());
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
