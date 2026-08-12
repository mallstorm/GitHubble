package app.githubble.mobile.controller;

import app.githubble.mobile.network.GitHubApiClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Advanced pull-request comments, suggestions and status/check aggregation. */
public final class PullRequestAdvancedController {
    private final GitHubApiClient api;
    public PullRequestAdvancedController(GitHubApiClient api){this.api=api;}

    public JSONObject createLineComment(String full,int number,String body,String commitId,String path,int line,String side,Integer startLine,String startSide)throws Exception{
        JSONObject data=new JSONObject();data.put("body",body);data.put("commit_id",commitId);data.put("path",path);data.put("line",line);data.put("side",normalizeSide(side));
        if(startLine!=null&&startLine>0){data.put("start_line",startLine);data.put("start_side",normalizeSide(startSide));}
        JSONObject result=api.postJson("https://api.github.com/repos/"+full+"/pulls/"+number+"/comments",data,true);api.invalidatePrefix("https://api.github.com/repos/"+full+"/pulls/"+number);return result;
    }

    public JSONObject createSuggestedChange(String full,int number,String replacement,String note,String commitId,String path,int line,String side,Integer startLine,String startSide)throws Exception{
        StringBuilder body=new StringBuilder();if(note!=null&&!note.trim().isEmpty())body.append(note.trim()).append("\n\n");body.append("```suggestion\n").append(replacement==null?"":replacement).append("\n```");
        return createLineComment(full,number,body.toString(),commitId,path,line,side,startLine,startSide);
    }

    public JSONObject loadChecks(String full,String headSha,String baseBranch)throws Exception{
        JSONObject out=new JSONObject();
        JSONObject combined;
        try{combined=api.getJson("https://api.github.com/repos/"+full+"/commits/"+headSha+"/status");}catch(Exception e){combined=new JSONObject().put("state","unknown").put("statuses",new JSONArray()).put("error",e.getMessage());}
        out.put("combined_status",combined);
        JSONObject checks;
        try{checks=api.getJson("https://api.github.com/repos/"+full+"/commits/"+headSha+"/check-runs?per_page=100");}catch(Exception e){checks=new JSONObject().put("total_count",0).put("check_runs",new JSONArray()).put("error",e.getMessage());}
        out.put("checks",checks);
        JSONObject required=new JSONObject();
        if(baseBranch!=null&&!baseBranch.isEmpty()){
            try{required=api.getJson("https://api.github.com/repos/"+full+"/branches/"+URLEncoder.encode(baseBranch, StandardCharsets.UTF_8.name())+"/protection/required_status_checks");}
            catch(Exception e){required.put("contexts",new JSONArray());required.put("checks",new JSONArray());required.put("unavailable",true);required.put("error",e.getMessage());}
        }
        out.put("required",required);return out;
    }

    private static String normalizeSide(String side){return "LEFT".equalsIgnoreCase(side)?"LEFT":"RIGHT";}
}
