package app.githubble.mobile.agent;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.*;
import java.util.*;

public class CiToolsetTest {
    static final String SHA="a".repeat(40),OTHER="b".repeat(40),BRANCH="hubble/test-123";
    static final class Memory implements CiToolset.Store {JSONObject s=new JSONObject();public JSONObject read(){return UnifiedAgentStore.copy(s);}public void save(JSONObject v){s=UnifiedAgentStore.copy(v);}}
    static final class Api implements GitDataApi {
        JSONArray runs=new JSONArray();String head=SHA;int posts;boolean loseResponse;
        JSONObject run(long id,String sha,String conclusion)throws Exception{return new JSONObject().put("id",id).put("workflow_id",7).put("head_sha",sha).put("head_branch",BRANCH).put("status",conclusion.isEmpty()?"in_progress":"completed").put("conclusion",conclusion);}
        public JSONObject get(String url)throws Exception{
            if(url.contains("/actions/runs?"))return new JSONObject().put("workflow_runs",runs);
            if(url.contains("/actions/runs/")){long id=Long.parseLong(url.substring(url.lastIndexOf('/')+1));for(int i=0;i<runs.length();i++)if(runs.getJSONObject(i).getLong("id")==id)return runs.getJSONObject(i);throw new IllegalArgumentException("404");}
            if(url.contains("/git/ref/"))return new JSONObject().put("object",new JSONObject().put("sha",head));
            if(url.contains("/actions/workflows?"))return new JSONObject().put("workflows",new JSONArray().put(new JSONObject().put("id",7).put("state","active").put("path",".github/workflows/android.yml")));
            if(url.contains("/contents/"))return new JSONObject().put("size",30).put("content",Base64.getEncoder().encodeToString("on: [workflow_dispatch]".getBytes()));
            throw new AssertionError(url);
        }
        public JSONObject post(String url,JSONObject body)throws Exception{posts++;if(loseResponse)throw new java.io.IOException("unknown");return new JSONObject();}
        public JSONObject patch(String url,JSONObject body){throw new AssertionError();}
    }
    static CiToolset ci(Api api,Memory store,String sha,CiToolset.Consent consent,CiToolset.Diagnostics diagnostics){return new CiToolset(api,store,diagnostics,consent,id->true,()->{},()->1000000L,"owner/repo",BRANCH,sha);}
    static CiToolset ci(Api api,Memory store){return ci(api,store,SHA,id->true,(id,logs)->new JSONObject().put("failure_class","KOTLIN_JAVA_ERROR").put("failure_signature","error"));}
    @Test public void reuseExactShaNeverForeignGreen()throws Exception {
        Api a=new Api();Memory s=new Memory();a.runs.put(a.run(1,OTHER,"success")).put(a.run(2,SHA,""));
        assertEquals(2,ci(a,s).build("").getLong("run_id"));assertEquals(0,a.posts);assertEquals(1,s.s.getJSONObject("retry").getInt("buildRuns"));
        ci(a,s).build("");assertEquals(1,s.s.getJSONObject("retry").getInt("buildRuns"));
    }
    @Test public void lostDispatchIsNeverRepeatedAcrossRestart()throws Exception {
        Api a=new Api();Memory s=new Memory();a.loseResponse=true;
        try{ci(a,s).build("7");fail();}catch(java.io.IOException expected){}
        assertEquals("UNKNOWN",s.s.getString("state"));ci(a,s).build("7");assertEquals(1,a.posts);
        a.runs.put(a.run(3,SHA,""));assertEquals(3,ci(a,s).build("7").getLong("run_id"));assertEquals(1,a.posts);
    }
    @Test public void failedRunIsChargedOnceAndFixRequiresNewSha()throws Exception {
        Api a=new Api();Memory s=new Memory();a.runs.put(a.run(1,SHA,"failure"));ci(a,s).build("");
        assertEquals("FIX_SOURCE",ci(a,s).failure(1,false).getJSONObject("decision").getString("action"));
        ci(a,s).failure(1,false);ci(a,s).failure(1,true);
        assertEquals(1,s.s.getJSONObject("retry").getInt("autoFixes"));
        try{ci(a,s).build("");fail();}catch(IllegalStateException expected){}
        a.head=OTHER;ci(a,s,OTHER,id->true,(id,logs)->new JSONObject()).build("7");assertEquals(1,a.posts);
    }
    @Test public void logsRequireConsentBeforeFetchAndExternalFailuresBlockEdits()throws Exception {
        Api a=new Api();Memory s=new Memory();a.runs.put(a.run(1,SHA,"failure"));int[] fetched={0};
        CiToolset c=ci(a,s,SHA,id->false,(id,logs)->{if(logs)fetched[0]++;return new JSONObject().put("failure_class","BILLING_OR_QUOTA_BLOCK");});
        c.failure(1,true);assertEquals(0,fetched[0]);c.failure(1,false);
        assertEquals("WAIT_EXTERNAL",s.s.getJSONObject("decision").getString("action"));
        try{CiToolset.assertSourceAllowed(s.s);fail();}catch(IllegalStateException expected){}
        try{c.build("");fail();}catch(IllegalStateException expected){}assertEquals(0,a.posts);
    }
    @Test public void buildBudgetSurvivesNewToolsets()throws Exception {
        Api a=new Api();Memory s=new Memory();
        for(int i=1;i<=8;i++){a.runs=new JSONArray().put(a.run(i,SHA,"success"));ci(a,s).build("");}
        a.runs=new JSONArray().put(a.run(9,SHA,"success"));try{ci(a,s).build("");fail();}catch(IllegalStateException expected){}
        assertEquals(8,s.s.getJSONObject("retry").getInt("buildRuns"));
    }
    @Test public void externalLogReasonOverridesSourceGuess()throws Exception {
        Api a=new Api();Memory s=new Memory();a.runs.put(a.run(1,SHA,"failure"));
        CiToolset c=ci(a,s,SHA,id->true,(id,logs)->new JSONObject().put("failure_class",logs?"BILLING_OR_QUOTA_BLOCK":"KOTLIN_JAVA_ERROR"));
        c.failure(1,false);assertEquals("FIX_SOURCE",s.s.getJSONObject("decision").getString("action"));
        c.failure(1,true);assertEquals("WAIT_EXTERNAL",s.s.getJSONObject("decision").getString("action"));
    }
    @Test public void ambiguousWorkflowCannotUseUnrelatedGreenCheck()throws Exception {
        Api a=new Api();Memory s=new Memory();a.runs.put(a.run(1,SHA,"success")).put(a.run(2,SHA,"success").put("workflow_id",8));
        try{ci(a,s).build("");fail();}catch(IllegalArgumentException expected){}
        assertEquals(2,ci(a,s).build("8").getLong("run_id"));assertEquals(0,a.posts);
    }
    @Test public void transientRetryDelaySurvivesRestart()throws Exception {
        Api a=new Api();Memory s=new Memory();a.runs.put(a.run(1,SHA,"failure"));
        CiToolset c=ci(a,s,SHA,id->true,(id,logs)->new JSONObject().put("failure_class","INFRASTRUCTURE_FAILURE"));
        c.failure(1,false);assertEquals("RETRY_AFTER",s.s.getJSONObject("decision").getString("action"));
        try{ci(a,s).build("7");fail();}catch(IllegalStateException expected){}assertEquals(0,a.posts);
    }
    @Test public void foreignRunAndMovedHeadFailBeforeDispatch()throws Exception {
        Api a=new Api();Memory s=new Memory();a.runs.put(a.run(1,OTHER,"success"));
        try{ci(a,s).await(1);fail();}catch(SecurityException expected){}
        a.head=OTHER;try{ci(a,s).build("7");fail();}catch(ChangeStore.Conflict expected){}assertEquals(0,a.posts);
    }
}
