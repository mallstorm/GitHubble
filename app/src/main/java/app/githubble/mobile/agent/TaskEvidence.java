package app.githubble.mobile.agent;

import app.githubble.mobile.agent.core.*;
import org.json.*;

/** Delivery gate uses independently fetched facts and on-device verdicts, never a model's boolean. */
public final class TaskEvidence {
    private TaskEvidence(){}
    public static JSONObject verify(Evidence evidence,GitDataApi api,String repo,String branch,String sha,
            long runId,int pull,long artifactId,JSONObject deviceVerdict,boolean requireBuild,boolean requireApk)throws Exception {
        GitObjectId.requireCommit(sha);Supervisor.Claims c=new Supervisor.Claims();
        c.repo=repo;c.branch=branch;c.commitSha=sha;c.pullNumber=pull;c.runId=runId;
        Supervisor.Verdict verdict=new Supervisor(evidence).verifyDelivery(c);
        JSONArray issues=new JSONArray();for(Supervisor.Finding f:verdict.findings)issues.put(f.message);
        if(requireBuild&&runId<=0)issues.put("Сборка не подтверждена: нет run ID");
        if(requireApk){
            if(runId<=0||artifactId<=0)issues.put("Нет связанного APK-артефакта");
            else{
                JSONObject artifact=api.get(GitDataApi.base(repo)+"/actions/artifacts/"+artifactId);
                JSONObject workflow=artifact.optJSONObject("workflow_run");
                if(artifact.optBoolean("expired")||artifact.optLong("size_in_bytes")<=0||workflow==null
                        ||workflow.optLong("id")!=runId||!sha.equalsIgnoreCase(workflow.optString("head_sha")))issues.put("Артефакт не принадлежит проверяемой сборке/SHA или истёк");
                if(deviceVerdict==null||!"PASS".equals(deviceVerdict.optString("status"))
                        ||deviceVerdict.optLong("artifact_id")!=artifactId||deviceVerdict.optLong("run_id")!=runId
                        ||!repo.equals(deviceVerdict.optString("repository")))issues.put("Нет успешной проверки APK на устройстве");
                else{JSONArray apks=deviceVerdict.optJSONArray("apks");if(apks==null||apks.length()==0)issues.put("В артефакте нет APK");
                    for(int i=0;apks!=null&&i<apks.length();i++){
                        JSONObject apk=apks.getJSONObject(i);
                        if(!"PASS".equals(apk.optString("status"))||!apk.optBoolean("signature_verified")
                            ||!apk.optString("sha256").matches("[a-f0-9]{64}")||apk.optString("package").isEmpty()
                            ||apk.optLong("version_code",-1)<0||!apk.optString("certificate_sha256").matches("[a-f0-9]{64}"))
                            issues.put("APK: подпись, идентичность или SHA256 не подтверждены");
                    }
                }
            }
        }
        return new JSONObject().put("accepted",issues.length()==0).put("repository",repo).put("branch",branch).put("sha",sha)
            .put("run_id",runId).put("pull_number",pull).put("artifact_id",artifactId).put("apk_verified",requireApk&&issues.length()==0)
            .put("checked_at",System.currentTimeMillis()).put("issues",issues);
    }
}
