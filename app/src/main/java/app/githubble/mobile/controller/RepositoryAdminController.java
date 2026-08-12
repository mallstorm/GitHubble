package app.githubble.mobile.controller;

import app.githubble.mobile.network.GitHubApiClient;
import org.json.JSONObject;

/** Repository administration use-cases that require explicit user confirmation and network access. */
public final class RepositoryAdminController {
    private final GitHubApiClient api;
    public RepositoryAdminController(GitHubApiClient api){this.api=api;}

    public JSONObject createRepository(String name,String description,boolean privateRepo,boolean initializeReadme)throws Exception{
        JSONObject body=new JSONObject();body.put("name",name);body.put("description",description==null?"":description);body.put("private",privateRepo);body.put("auto_init",initializeReadme);
        JSONObject result=api.postJson("https://api.github.com/user/repos",body,true);api.invalidatePrefix("https://api.github.com/user/repos");return result;
    }

    public JSONObject updateRepository(String full,String name,String description)throws Exception{
        JSONObject body=new JSONObject();
        if(name!=null&&!name.trim().isEmpty())body.put("name",name.trim());
        if(description!=null)body.put("description",description.trim());
        JSONObject result=api.patchJson("https://api.github.com/repos/"+full,body);
        api.invalidatePrefix("https://api.github.com/repos/"+full);api.invalidatePrefix("https://api.github.com/user/repos");return result;
    }

    public JSONObject setPrivate(String full,boolean privateRepo)throws Exception{
        JSONObject result=api.patchJson("https://api.github.com/repos/"+full,new JSONObject().put("private",privateRepo));
        api.invalidatePrefix("https://api.github.com/repos/"+full);api.invalidatePrefix("https://api.github.com/user/repos");return result;
    }

    public void deleteRepository(String full)throws Exception{
        api.delete("https://api.github.com/repos/"+full);
        api.invalidatePrefix("https://api.github.com/repos/"+full);api.invalidatePrefix("https://api.github.com/user/repos");
    }

    public JSONObject fork(String full,String organization,String name)throws Exception{
        JSONObject body=new JSONObject();if(organization!=null&&!organization.trim().isEmpty())body.put("organization",organization.trim());if(name!=null&&!name.trim().isEmpty())body.put("name",name.trim());
        JSONObject result=api.postJson("https://api.github.com/repos/"+full+"/forks",body,true);api.invalidatePrefix("https://api.github.com/user/repos");return result;
    }

    public JSONObject createBranch(String full,String branch,String fromRef)throws Exception{
        String sha=resolveSha(full,fromRef);JSONObject body=new JSONObject();body.put("ref","refs/heads/"+normalizeRef(branch));body.put("sha",sha);
        JSONObject result=api.postJson("https://api.github.com/repos/"+full+"/git/refs",body,true);api.invalidatePrefix("https://api.github.com/repos/"+full+"/branches");return result;
    }

    /** Creates a lightweight Git tag that points at the selected branch, tag or SHA. */
    public JSONObject createTag(String full,String tag,String fromRef)throws Exception{
        String sha=resolveSha(full,fromRef);JSONObject body=new JSONObject();body.put("ref","refs/tags/"+normalizeRef(tag));body.put("sha",sha);
        JSONObject result=api.postJson("https://api.github.com/repos/"+full+"/git/refs",body,true);api.invalidatePrefix("https://api.github.com/repos/"+full+"/tags");return result;
    }

    public JSONObject createRelease(String full,String tag,String target,String name,String body,boolean draft,boolean prerelease,boolean generateNotes)throws Exception{
        JSONObject data=new JSONObject();data.put("tag_name",tag);if(target!=null&&!target.isEmpty())data.put("target_commitish",target);data.put("name",name==null?"":name);data.put("body",body==null?"":body);data.put("draft",draft);data.put("prerelease",prerelease);data.put("generate_release_notes",generateNotes);
        JSONObject result=api.postJson("https://api.github.com/repos/"+full+"/releases",data,true);api.invalidatePrefix("https://api.github.com/repos/"+full+"/releases");return result;
    }

    private String resolveSha(String full,String ref)throws Exception{
        String value=ref==null?"":ref.trim();if(value.matches("[0-9a-fA-F]{40}"))return value;
        String normalized=value.startsWith("refs/")?value.substring(5):value;
        if(!normalized.startsWith("heads/")&&!normalized.startsWith("tags/"))normalized="heads/"+normalized;
        JSONObject object=api.getJson("https://api.github.com/repos/"+full+"/git/ref/"+normalized);JSONObject target=object.optJSONObject("object");String sha=target==null?"":target.optString("sha","");if(sha.isEmpty())throw new IllegalStateException("GitHub не вернул SHA исходной ссылки");return sha;
    }

    private static String normalizeRef(String value){String v=value==null?"":value.trim();while(v.startsWith("/"))v=v.substring(1);if(v.startsWith("refs/heads/"))v=v.substring(11);if(v.startsWith("refs/tags/"))v=v.substring(10);if(v.isEmpty()||v.endsWith("/")||v.contains("..")||v.contains(" ")||v.contains("~")||v.contains("^")||v.contains(":"))throw new IllegalArgumentException("Недопустимое имя ветки или тега");return v;}
}
