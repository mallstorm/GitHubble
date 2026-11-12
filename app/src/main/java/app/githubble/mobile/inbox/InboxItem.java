package app.githubble.mobile.inbox;

import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.Set;

/** One line of the in-app Inbox: either a real GitHub notification thread or a search-derived item. */
public final class InboxItem {
    public final String key;
    public final String threadId;
    public final String repo;
    public final String title;
    public final String type;
    public final int number;
    public final String updatedAt;
    public final Set<String> reasons;
    public boolean unread;

    public InboxItem(String key,String threadId,String repo,String title,String type,int number,
                     String updatedAt,Set<String> reasons,boolean unread){
        this.key=key;this.threadId=threadId;this.repo=repo;this.title=title;this.type=type;
        this.number=number;this.updatedAt=updatedAt==null?"":updatedAt;this.reasons=reasons;this.unread=unread;
    }

    /** Item from GET /notifications (needs a classic token with the notifications scope). */
    public static InboxItem fromThread(JSONObject thread){
        if(thread==null)return null;
        JSONObject subject=thread.optJSONObject("subject");
        JSONObject repository=thread.optJSONObject("repository");
        String id=thread.optString("id","");
        String repo=repository==null?"":repository.optString("full_name","");
        if(id.isEmpty()||repo.isEmpty()||subject==null)return null;
        String type=subject.optString("type","");
        Set<String> reasons=new LinkedHashSet<>();
        reasons.add(reasonLabel(thread.optString("reason","")));
        return new InboxItem("t"+id,id,repo,subject.optString("title","Без названия"),type,
                numberFromUrl(subject.optString("url","")),thread.optString("updated_at",""),reasons,
                thread.optBoolean("unread",false));
    }

    /** Item from GET /search/issues; works with GitHub App tokens. */
    public static InboxItem fromSearch(JSONObject issue,String reason){
        if(issue==null)return null;
        String repositoryUrl=issue.optString("repository_url","");
        int marker=repositoryUrl.indexOf("/repos/");
        String repo=marker<0?"":repositoryUrl.substring(marker+7);
        int number=issue.optInt("number",0);
        if(repo.isEmpty()||number<=0)return null;
        Set<String> reasons=new LinkedHashSet<>();
        reasons.add(reason);
        return new InboxItem(repo+"#"+number,"",repo,issue.optString("title","Без названия"),
                issue.has("pull_request")?"PullRequest":"Issue",number,issue.optString("updated_at",""),reasons,true);
    }

    public static int numberFromUrl(String url){
        if(url==null)return 0;
        int slash=url.lastIndexOf('/');
        if(slash<0||slash==url.length()-1)return 0;
        try{return Integer.parseInt(url.substring(slash+1));}catch(NumberFormatException e){return 0;}
    }

    public static String reasonLabel(String reason){
        switch(reason==null?"":reason){
            case "assign":return "Назначено вам";
            case "author":return "Ваша тема";
            case "comment":return "Комментарий";
            case "mention":return "Упоминание";
            case "team_mention":return "Упоминание команды";
            case "review_requested":return "Просят ревью";
            case "state_change":return "Смена статуса";
            case "ci_activity":return "Сборка";
            case "security_alert":return "Безопасность";
            case "approval_requested":return "Нужно одобрение";
            case "subscribed":return "Подписка";
            case "manual":return "Вы подписались";
            case "invitation":return "Приглашение";
            default:return reason==null||reason.isEmpty()?"Уведомление":reason;
        }
    }

    public String reasonText(){
        StringBuilder out=new StringBuilder();
        for(String reason:reasons){if(out.length()>0)out.append(", ");out.append(reason);}
        return out.toString();
    }

    public String readKey(){return key+"@"+updatedAt;}
}
