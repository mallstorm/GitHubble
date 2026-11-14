package app.githubble.mobile.agent;

import app.githubble.mobile.agent.core.*;
import org.json.*;
import java.util.function.LongSupplier;

/** Account/task-bound CI orchestration. Intent and budget are durable before any remote dispatch. */
public final class CiToolset {
    public interface Store { JSONObject read(); void save(JSONObject value); }
    public interface Diagnostics { JSONObject read(long runId, boolean logs)throws Exception; }
    public interface Consent { boolean allow(long runId)throws Exception; }
    private final GitDataApi api; private final Store store; private final Diagnostics diagnostics;
    private final Consent consent,dispatch; private final Runnable guard; private final LongSupplier clock;
    private final String repo,branch,sha,base;
    public CiToolset(GitDataApi api,Store store,Diagnostics diagnostics,Consent consent,Consent dispatch,Runnable guard,
            LongSupplier clock,String repository,String branch,String sha) {
        AgentGitWorkspace.validateRepository(repository,branch);
        GitObjectId.requireCommit(sha);
        this.api=api;this.store=store;this.diagnostics=diagnostics;this.consent=consent;
        this.dispatch=dispatch;this.guard=guard;this.clock=clock;repo=repository;this.branch=branch;this.sha=sha; base=GitDataApi.base(repo);
    }
    private JSONObject state(){JSONObject s=store.read();String old=s.optString("repository");
        if(!old.isEmpty()&&!old.equals(repo))throw new SecurityException("CI принадлежит другому репозиторию");return s;}
    private void save(JSONObject s)throws Exception{guard.run();s.put("repository",repo);store.save(UnifiedAgentStore.copy(s));}
    public JSONObject status(){return state();}
    private RetryPolicy policy(JSONObject s){return RetryPolicy.restore(s.optJSONObject("retry"));}
    public static void assertSourceAllowed(JSONObject s){String a=s.optJSONObject("decision")==null?"":s.optJSONObject("decision").optString("action");
        if(a.equals("WAIT_EXTERNAL")||a.equals("FAIL"))throw new IllegalStateException("CI остановлен: "+s.optJSONObject("decision").optString("reason")+". Продолжение разрешается пользователем в панели задачи.");}
    /** Reuses only an exact SHA/workflow run; a lost dispatch response is reconciled by reads only. */
    public JSONObject build(String workflow)throws Exception {
        guard.run();JSONObject s=state();assertSourceAllowed(s);
        JSONObject decision=s.optJSONObject("decision");
        if(decision!=null&&"FIX_SOURCE".equals(decision.optString("action"))&&(s.optString("decidedRun").isEmpty()?sha.equals(s.optString("sha")):s.optString("decidedRun").startsWith(sha+":")))
            throw new IllegalStateException("Сначала исправьте и опубликуйте исходники: повтор старого SHA запрещён");
        if(clock.getAsLong()<s.optLong("retryAt"))throw new IllegalStateException("Повтор разрешён после "+s.optLong("retryAt"));
        String requested=workflow==null?"":workflow;
        if(!requested.isEmpty()&&!requested.matches("[A-Za-z0-9_.-]{1,150}"))throw new IllegalArgumentException("Некорректный workflow_id");
        boolean pending=java.util.Arrays.asList("DISPATCH_PENDING","UNKNOWN").contains(s.optString("state"));
        if(pending&&(!sha.equals(s.optString("sha"))||!branch.equals(s.optString("branch"))))
            throw new IllegalStateException("Сначала сверьте неопределённый запуск для предыдущего SHA");
        if(pending)requested=s.getString("workflowId");
        JSONArray runs=api.get(base+"/actions/runs?head_sha="+sha+"&per_page=100").optJSONArray("workflow_runs");
        if(requested.isEmpty()){
            java.util.Set<Long> candidates=new java.util.HashSet<>();
            for(int i=0;runs!=null&&i<runs.length();i++){JSONObject r=runs.getJSONObject(i);
                if(sha.equalsIgnoreCase(r.optString("head_sha"))&&branch.equals(r.optString("head_branch")))candidates.add(r.optLong("workflow_id"));}
            if(candidates.size()>1)throw new IllegalArgumentException("Для SHA найдены разные workflow; укажите workflow_id нужной сборки");
        }
        for(int i=0;runs!=null&&i<runs.length();i++){
            JSONObject run=runs.getJSONObject(i);
            if(!sha.equalsIgnoreCase(run.optString("head_sha"))||!branch.equals(run.optString("head_branch")))continue;
            if(!requested.isEmpty()&&!requested.equals(String.valueOf(run.optLong("workflow_id")))
                &&!run.optString("path").equals(".github/workflows/"+requested))continue;
            if(pending&&run.optLong("id")<=s.optLong("priorRunId"))continue;
            if(s.optLong("retryRunId")==run.optLong("id"))continue;
            if(!pending&&run.optLong("id")!=s.optLong("runId")){
                RetryPolicy p=policy(s);if(!p.tryStartBuild())throw new IllegalStateException("Исчерпан бюджет: 8 сборок");s.put("retry",p.snapshot());
            }
            s.put("state","TRACKING").put("sha",sha).put("branch",branch).put("runId",run.getLong("id"));save(s);
            return compact(run).put("reused",true);
        }
        if(pending)return new JSONObject().put("state",s.optString("state")).put("dispatched",false)
            .put("instruction","Результат отправки неизвестен. Только повторная сверка; dispatch не повторяется автоматически.");
        // Dispatch must target a task branch and still point at the pinned SHA.
        if(!branch.startsWith("hubble/"))throw new SecurityException("Новый workflow запускается только на ветке задачи hubble/");
        String head=api.get(base+"/git/ref/heads/"+GitDataApi.encode(branch)).getJSONObject("object").getString("sha");
        if(!sha.equalsIgnoreCase(head))throw new ChangeStore.Conflict("HEAD изменился до запуска CI");
        JSONObject wf=selectWorkflow(requested);
        if(!dispatch.allow(wf.getLong("id")))throw new java.util.concurrent.CancellationException("Запуск сборки отклонён");
        guard.run();
        if(!sha.equalsIgnoreCase(api.get(base+"/git/ref/heads/"+GitDataApi.encode(branch)).getJSONObject("object").getString("sha")))throw new ChangeStore.Conflict("HEAD изменился после подтверждения CI");
        long prior=0;for(int i=0;runs!=null&&i<runs.length();i++)prior=Math.max(prior,runs.getJSONObject(i).optLong("id"));
        RetryPolicy p=policy(s);if(!p.tryStartBuild())throw new IllegalStateException("Исчерпан бюджет: 8 сборок");
        s.put("retry",p.snapshot()).put("state","DISPATCH_PENDING").put("sha",sha).put("branch",branch)
            .put("workflowId",String.valueOf(wf.getLong("id"))).put("runId",0).put("priorRunId",prior).put("dispatchAt",clock.getAsLong());save(s);
        try{guard.run();api.post(base+"/actions/workflows/"+wf.getLong("id")+"/dispatches",new JSONObject().put("ref",branch));}
        catch(Exception error){s.put("state","UNKNOWN");save(s);throw error;}
        // GitHub commonly returns 204 without a run ID. Remain pending until independently found.
        return new JSONObject().put("state","DISPATCH_PENDING").put("sha",sha).put("branch",branch)
            .put("instruction","Вызовите build ещё раз для read-only сверки нового запуска; не вызывайте dispatch_workflow.");
    }
    private JSONObject selectWorkflow(String requested)throws Exception {
        JSONArray workflows=api.get(base+"/actions/workflows?per_page=100").optJSONArray("workflows");JSONObject chosen=null;
        for(int i=0;workflows!=null&&i<workflows.length();i++){
            JSONObject wf=workflows.getJSONObject(i);if(!"active".equals(wf.optString("state")))continue;
            String id=String.valueOf(wf.getLong("id")),path=wf.optString("path");
            if(!requested.isEmpty()&&!requested.equals(id)&&!path.equals(".github/workflows/"+requested))continue;
            if(!path.startsWith(".github/workflows/"))continue;
            JSONObject file=api.get(base+"/contents/"+path+"?ref="+sha);
            if(file.optInt("size")>256000)continue;
            String yaml=new String(java.util.Base64.getMimeDecoder().decode(file.getString("content")),java.nio.charset.StandardCharsets.UTF_8);
            if(!yaml.matches("(?s).*\\bworkflow_dispatch\\b.*"))continue;
            if(chosen!=null)throw new IllegalArgumentException("Несколько dispatch workflow: укажите workflow_id");chosen=wf;
        }
        if(chosen==null)throw new IllegalArgumentException("Нет подходящего workflow_dispatch; проверьте workflow на default branch и обязательные inputs");return chosen;
    }
    public JSONObject await(long id)throws Exception {
        guard.run();if(id<=0)id=state().optLong("runId");if(id<=0)throw new IllegalArgumentException("Сначала найдите запуск через build");
        JSONObject run=api.get(base+"/actions/runs/"+id);validateRun(run);JSONObject s=state();
        // An unrelated run must not resolve an unknown dispatch and allow another mutation.
        if(java.util.Arrays.asList("DISPATCH_PENDING","UNKNOWN").contains(s.optString("state"))){
            if(!sha.equals(s.optString("sha"))||!branch.equals(s.optString("branch"))
                    ||!String.valueOf(run.optLong("workflow_id")).equals(s.optString("workflowId"))
                    ||id<=s.optLong("priorRunId"))throw new SecurityException("Запуск не подтверждает ожидаемый dispatch workflow/SHA");
        }
        if(s.optLong("runId")!=id){RetryPolicy p=policy(s);if(!p.tryStartBuild())throw new IllegalStateException("Исчерпан бюджет сборок");s.put("retry",p.snapshot());}
        s.put("runId",id).put("sha",sha).put("branch",branch).put("state",run.optString("status").equals("completed")?"COMPLETED":"TRACKING");save(s);
        return compact(run).put("done","completed".equals(run.optString("status")));
    }
    private void validateRun(JSONObject run){if(!sha.equalsIgnoreCase(run.optString("head_sha"))||!branch.equals(run.optString("head_branch")))
        throw new SecurityException("Workflow относится к другой ветке или commit SHA");}
    private static JSONObject compact(JSONObject run)throws Exception{return new JSONObject().put("run_id",run.getLong("id"))
        .put("head_sha",run.optString("head_sha")).put("branch",run.optString("head_branch")).put("status",run.optString("status"))
        .put("conclusion",run.optString("conclusion")).put("url",run.optString("html_url"));}
    public JSONObject failure(long id,boolean includeLogs)throws Exception {
        JSONObject run=await(id);if(!run.optBoolean("done")||"success".equals(run.optString("conclusion")))throw new IllegalStateException("Нужна завершённая неуспешная сборка");
        id=run.getLong("run_id");JSONObject s=state();String key=sha+":"+id+":"+includeLogs;
        if(key.equals(s.optString("failureKey")))return s.getJSONObject("failure");
        // Explicit, per-run permission BEFORE fetching or exposing a log to the external model.
        if(includeLogs&&!consent.allow(id))return new JSONObject().put("logs_shared",false).put("decision","NEED_LOG_CONSENT");
        guard.run();JSONObject report=diagnostics.read(id,includeLogs);guard.run();
        FailureClass kind;try{kind=FailureClass.valueOf(report.optString("failure_class","UNKNOWN"));}catch(Exception unknown){kind=FailureClass.UNKNOWN;}
        JSONObject result=new JSONObject().put("run_id",id).put("failure_class",kind.name()).put("logs_shared",includeLogs);
        if(includeLogs)result.put("error_excerpt",AgentLogRedactor.extract(report.optString("error_excerpt")));
        if(kind==FailureClass.UNKNOWN){result.put("decision",new JSONObject().put("action","NEED_LOG_EXCERPT").put("reason","Причина не установлена; запросите отдельное согласие на ограниченный журнал"));}
        else{
            // A metadata decision is never charged again just because logs were subsequently requested.
            JSONObject previous=s.optJSONObject("decision");
            boolean external=kind==FailureClass.PERMISSION_ERROR||kind==FailureClass.SIGNING_FAILURE||kind==FailureClass.ANDROID_SDK_ERROR||kind==FailureClass.BILLING_OR_QUOTA_BLOCK;
            if((sha+":"+id).equals(s.optString("decidedRun"))&&previous!=null&&(!external||previous.optString("action").equals("WAIT_EXTERNAL")))result.put("decision",previous);
            else{RetryPolicy p=policy(s);RetryPolicy.Decision d=p.onFailure(BuildFailureClassifier.Result.of(kind,report.optString("failure_signature")),0);
                JSONObject decision=new JSONObject().put("action",d.action.name()).put("reason",d.reason).put("delay_ms",d.delayMs).put("next_state",d.nextState.name());
                result.put("decision",decision);s.put("retry",p.snapshot()).put("decision",decision).put("decidedRun",sha+":"+id);
                if(d.action==RetryPolicy.Action.RETRY_AFTER)s.put("retryAt",clock.getAsLong()+d.delayMs).put("retryRunId",id);
            }
        }
        s.put("failureKey",key).put("failure",result);save(s);return result;
    }
}
