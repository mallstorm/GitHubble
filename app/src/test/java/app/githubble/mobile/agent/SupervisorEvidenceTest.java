package app.githubble.mobile.agent;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.*;
import app.githubble.mobile.agent.core.*;
import java.util.*;

public class SupervisorEvidenceTest {
    final String sha="a".repeat(40),branch="hubble/task-123";
    class Facts implements Evidence {
        String head=sha,runSha=sha;boolean unknown;
        public String branchHead(String r,String b){return unknown?null:head;}
        public Boolean commitExists(String r,String s){return true;}
        public PullInfo pullRequest(String r,int n){return new PullInfo(true,"open",sha,"main");}
        public RunInfo workflowRun(String r,long id){return new RunInfo("completed","success",runSha,branch);}
        public List<ArtifactInfo> artifacts(String r,long id){return Collections.emptyList();}
    }
    GitDataApi api(){return new GitDataApi(){public JSONObject get(String url)throws Exception{return new JSONObject().put("size_in_bytes",20).put("workflow_run",new JSONObject().put("id",1).put("head_sha",sha));}public JSONObject post(String u,JSONObject v){throw new AssertionError();}public JSONObject patch(String u,JSONObject v){throw new AssertionError();}};}
    JSONObject apk()throws Exception{return new JSONObject().put("status","PASS").put("artifact_id",2).put("run_id",1).put("repository","o/r").put("apks",new JSONArray().put(new JSONObject().put("status","PASS").put("signature_verified",true).put("certificate_sha256","f".repeat(64)).put("sha256","e".repeat(64)).put("package","app.test").put("version_code",1)));}
    @Test public void exactCommitRunAndCryptographicApkEvidenceRequired()throws Exception {
        Facts f=new Facts();assertTrue(TaskEvidence.verify(f,api(),"o/r",branch,sha,1,0,2,apk(),true,true).getBoolean("accepted"));
        JSONObject bad=apk();bad.getJSONArray("apks").getJSONObject(0).put("signature_verified",false);
        assertFalse(TaskEvidence.verify(f,api(),"o/r",branch,sha,1,0,2,bad,true,true).getBoolean("accepted"));
        f.runSha="b".repeat(40);assertFalse(TaskEvidence.verify(f,api(),"o/r",branch,sha,1,0,2,apk(),true,true).getBoolean("accepted"));
    }
    @Test public void unreachableOrOldBranchNeverCountsAsSuccessEvenWithoutApk()throws Exception {
        Facts f=new Facts();f.unknown=true;assertFalse(TaskEvidence.verify(f,api(),"o/r",branch,sha,1,0,0,null,true,false).getBoolean("accepted"));
        f.unknown=false;f.head="b".repeat(40);assertFalse(TaskEvidence.verify(f,api(),"o/r",branch,sha,1,0,0,null,true,false).getBoolean("accepted"));
    }
    @Test public void emptyClaimsAndMissingBuildAreNotCompletion()throws Exception {
        assertFalse(new Supervisor(new Facts()).verifyDelivery(new Supervisor.Claims()).accepted());
        assertFalse(TaskEvidence.verify(new Facts(),api(),"o/r",branch,sha,0,0,0,null,true,false).getBoolean("accepted"));
    }
    @Test public void artifactFromDifferentRunCannotDeliver()throws Exception {
        JSONObject wrong=apk().put("run_id",99);
        assertFalse(TaskEvidence.verify(new Facts(),api(),"o/r",branch,sha,1,0,2,wrong,true,true).getBoolean("accepted"));
    }
}
