import app.githubble.mobile.util.DeviceCodeFormatter;
import app.githubble.mobile.util.PaginationState;
import app.githubble.mobile.diff.DiffModel;
import app.githubble.mobile.search.*;
import app.githubble.mobile.util.RepositoryPathPolicy;

public final class JvmRegression {
  public static void main(String[] args) {
    eq("85059224", DeviceCodeFormatter.normalize("8505-9224"));
    eq("AB12CD34", DeviceCodeFormatter.normalize("ab12 cd34"));
    PaginationState rest = new PaginationState();
    yes(rest.begin()); no(rest.begin()); rest.completeRest(50, 50);
    eq(2, rest.page()); yes(rest.hasMore()); yes(rest.begin()); rest.completeRest(3, 50); no(rest.hasMore());
    PaginationState graph = new PaginationState();
    yes(graph.begin()); graph.completeGraphQl("cursor-1", true); eq("cursor-1", graph.cursor());
    yes(graph.begin()); graph.completeGraphQl("cursor-2", false); no(graph.hasMore());
    DiffModel diff=DiffModel.parse("@@ -1,3 +1,4 @@\n alpha\n-old\n+new\n+extra\n omega");
    eq(3,diff.changedLines());
    eq(DiffModel.Kind.DELETE,diff.lines.get(2).kind);
    eq("LEFT",diff.lines.get(2).side());
    eq(DiffModel.Kind.ADD,diff.lines.get(3).kind);
    eq("RIGHT",diff.lines.get(3).side());
    yes(diff.displayLines(true,1).size()<=diff.lines.size());

    SearchFilters issueFilters=new SearchFilters(); issueFilters.issueKind="pr"; issueFilters.issueState="open";
    AdvancedSearchPlanner.Plan issuePlan=AdvancedSearchPlanner.plan("ошибка авторизации label:bug stars:>100",true,SearchMode.ISSUES_PRS,issueFilters);
    yes(issuePlan.variants.get(0).contains("label:bug")); yes(issuePlan.variants.get(0).contains("is:pr")); yes(issuePlan.variants.get(0).contains("is:open"));
    yes(issuePlan.rejectedQualifiers.contains("stars:>100"));

    SearchFilters codeFilters=new SearchFilters(); codeFilters.language="Java"; codeFilters.ownerOrOrg="org:openai";
    AdvancedSearchPlanner.Plan codePlan=AdvancedSearchPlanner.plan("обработка токена path:src stars:>50",true,SearchMode.CODE,codeFilters);
    yes(codePlan.variants.get(0).contains("handling")); yes(codePlan.variants.get(0).contains("token")); yes(codePlan.variants.get(0).contains("path:src"));
    yes(codePlan.variants.get(0).contains("language:Java")); yes(codePlan.variants.get(0).contains("org:openai"));
    yes(codePlan.rejectedQualifiers.contains("stars:>50"));

    eq("app/src/main/Main.java", RepositoryPathPolicy.normalize("/app//src/./main/Main.java"));
    yes(RepositoryPathPolicy.shouldIgnore(".git/config"));
    yes(RepositoryPathPolicy.shouldIgnore("folder/__MACOSX/file"));
    yes(RepositoryPathPolicy.isWorkflow(".github/workflows/android.yml"));
    try { RepositoryPathPolicy.normalize("../secret.txt"); throw new AssertionError("unsafe path accepted"); } catch (IllegalArgumentException expected) { }

    SearchFilters repoFilters=new SearchFilters(); repoFilters.minStars=250; repoFilters.updatedAfter="2026-01-01"; repoFilters.topic="android";
    AdvancedSearchPlanner.Plan repoPlan=AdvancedSearchPlanner.plan("клиент телеграм",true,SearchMode.REPOSITORIES,repoFilters);
    yes(repoPlan.variants.get(0).contains("telegram client")); yes(repoPlan.variants.get(0).contains("stars:>=250")); yes(repoPlan.variants.get(0).contains("pushed:>=2026-01-01"));

    System.out.println("PASS: core regression + advanced RU search + repository import path safety");
  }
  static void yes(boolean v){if(!v)throw new AssertionError("expected true");}
  static void no(boolean v){if(v)throw new AssertionError("expected false");}
  static void eq(Object e,Object a){if(e==null?a!=null:!e.equals(a))throw new AssertionError(e+" != "+a);}
}
