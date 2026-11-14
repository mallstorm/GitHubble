package app.githubble.mobile.agent;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Host-owned schemas. Optional fields are still typed; unknown arguments fail closed. */
public final class ToolCatalog {
    private ToolCatalog() {}
    private static JSONObject type(String name) throws org.json.JSONException { return new JSONObject().put("type",name); }
    private static JSONObject schema(String fields, String required) throws org.json.JSONException {
        JSONObject props=new JSONObject();
        for (String field : fields.split(" ")) {
            if (field.isEmpty()) continue;
            String[] pair=field.split(":"); String name=pair[0], kind=pair.length>1?pair[1]:"string";
            JSONObject p=type(kind);
            if ("string".equals(kind)) p.put("maxLength", "body".equals(name)?65000:"content".equals(name)?80000:12000);
            if ("integer".equals(kind)) p.put("minimum",1).put("maximum",Long.MAX_VALUE);
            props.put(name,p);
        }
        if (props.has("timeout_s")) props.put("timeout_s",type("integer").put("minimum",0).put("maximum",60));
        if (props.has("files")) props.put("files", type("array").put("minItems",1).put("maxItems",12)
            .put("items",schema("path content", "path content")));
        if (props.has("expect")) props.put("expect",schema("package version_code:integer version_name", ""));
        if (props.has("mode")) props.getJSONObject("mode").put("enum",new JSONArray().put("local").put("full"));
        JSONArray req=new JSONArray(); for (String f:required.split(" ")) if (!f.isEmpty()) req.put(f);
        return type("object").put("properties",props).put("required",req).put("additionalProperties",false);
    }
    private static void add(ToolBroker broker, String name, String fields, String required,
            ToolBroker.Permission permission, boolean write, boolean sensitive, boolean project) throws org.json.JSONException {
        broker.register(new ToolBroker.Definition(name, name+" (host-authorized "+(write?"operation":"read")+")",
            schema(fields,required),write?ToolBroker.Operation.WRITE:ToolBroker.Operation.READ,permission,sensitive,project));
    }
    public static void install(ToolBroker b) throws org.json.JSONException {
        ToolBroker.Permission gh=ToolBroker.Permission.GITHUB_READ, fs=ToolBroker.Permission.FILE_READ,
            local=ToolBroker.Permission.LOCAL_WRITE, remote=ToolBroker.Permission.GITHUB_WRITE;
        for (String n:Arrays.asList("github_account","list_repositories","github_profile","github_usage","github_rate_limit","github_capabilities"))
            add(b,n,"","",gh,false,false,false);
        for (String n:Arrays.asList("repository_info","repository_workflows","repository_branches","repository_issues","repository_pulls","list_releases"))
            add(b,n,"repository","",gh,false,false,true);
        add(b,"repository_files","repository branch","",gh,false,false,true);
        add(b,"repository_read_file","repository branch path","path",gh,false,false,true);
        for (String n:Arrays.asList("issue_details","pull_request_details")) add(b,n,"repository number:integer","number",gh,false,false,true);
        add(b,"recent_build_runs","repository","",gh,false,false,true);
        for (String n:Arrays.asList("build_run_diagnostics","build_run_status","build_artifacts")) add(b,n,"repository run_id:integer","run_id",gh,false,false,true);
        add(b,"verify_build","repository run_id:integer sha","run_id sha",gh,false,false,true);
        add(b,"list_files","","",fs,false,false,true);
        add(b,"read_file","path","path",fs,false,false,true);
        add(b,"search_files","query","query",fs,false,false,true);
        add(b,"symbol_index","query","",fs,false,false,true);
        add(b,"create_workspace","name instructions","name",local,true,false,false);
        add(b,"write_files","summary commit_message files:array","files",local,true,false,true);
        add(b,"detach_workspace","","",local,true,true,true);
        add(b,"delete_workspace","mode","mode",local,true,true,true);
        for (String n:Arrays.asList("attach_repository","import_repository")) add(b,n,"repository branch","repository",local,true,false,false);
        add(b,"publish_workspace","commit_message","",remote,true,false,true);
        add(b,"create_repository","name description private:boolean","name",remote,true,false,false);
        add(b,"create_branch","repository branch from_ref","branch",remote,true,false,true);
        add(b,"update_repository","repository description homepage has_issues:boolean has_wiki:boolean has_projects:boolean allow_squash_merge:boolean allow_merge_commit:boolean allow_rebase_merge:boolean delete_branch_on_merge:boolean","",remote,true,true,true);
        add(b,"update_account_profile","name bio company location blog hireable:boolean","",remote,true,true,false);
        add(b,"create_issue","repository title body","title",remote,true,false,true);
        add(b,"update_issue","repository number:integer title body state","number",remote,true,false,true);
        add(b,"comment_issue","repository number:integer body","number body",remote,true,false,true);
        add(b,"create_pull_request","repository title body head base draft:boolean","title head",remote,true,false,true);
        add(b,"update_pull_request","repository number:integer title body state base","number",remote,true,false,true);
        add(b,"merge_pull_request","repository number:integer sha merge_method","number sha",remote,true,true,true);
        add(b,"create_release","repository tag name body target draft:boolean prerelease:boolean","tag",remote,true,true,true);
        add(b,"dispatch_workflow","repository workflow_id ref","workflow_id",remote,true,false,true);
        add(b,"await_build","run_id:integer timeout_s:integer","run_id",gh,false,false,true);
        add(b,"download_artifact","artifact_id:integer expect:object","artifact_id",remote,true,false,true);
        add(b,"artifact_verdict","artifact_id:integer","artifact_id",gh,false,false,true);
        add(b,"web_search","query","query",ToolBroker.Permission.PUBLIC_WEB,false,false,false);
    }
}
