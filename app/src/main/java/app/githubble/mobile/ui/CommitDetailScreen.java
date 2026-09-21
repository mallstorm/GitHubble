package app.githubble.mobile.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.githubble.mobile.controller.CommitDetailController;
import app.githubble.mobile.diff.DiffModel;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/** Native read-only commit details. Diff text is never interpreted as HTML or WebView code. */
public final class CommitDetailScreen {
    public interface Actions {
        void openRepository(String fullName);
        void openParent(String parentSha);
        void openExternalCommit(String fullName,String sha);
        void openRemoteFile(JSONObject file);
    }

    private final Activity activity;
    private final LinearLayout host;
    private final Actions actions;
    private final int card,foreground,muted,accent,border;
    private final int positive=0xFF31934B;
    private final int negative=0xFFCD5961;
    private final int blue=0xFF3880C7;
    private LinearLayout filesHost;
    private LinearLayout expandedPatch;
    private int expandedIndex=-1;

    public CommitDetailScreen(Activity activity,LinearLayout host,Actions actions,
                              int card,int foreground,int muted,int accent,int border) {
        this.activity=activity;
        this.host=host;
        this.actions=actions;
        this.card=card;
        this.foreground=foreground;
        this.muted=muted;
        this.accent=accent;
        this.border=border;
    }

    public void render(String repo,String sha,CommitDetailController.Detail detail) {
        JSONObject result=detail.commit;
        JSONObject gitCommit=result.optJSONObject("commit");
        if(gitCommit==null)gitCommit=new JSONObject();
        String message=gitCommit.optString("message","");
        String first=message.isEmpty()?"Коммит "+shortSha(sha):
                firstLine(message);
        TextView subject=label(first,18,true,foreground);
        subject.setPadding(dp(12),dp(13),dp(12),dp(10));
        subject.setBackground(shape(card,15));
        add(subject,0,7);

        JSONObject apiUser=result.optJSONObject("author");
        JSONObject author=gitCommit.optJSONObject("author");
        JSONObject committer=gitCommit.optJSONObject("committer");
        String display=apiUser==null?"":apiUser.optString("login","");
        if(display.isEmpty()&&author!=null)display=author.optString("name","");
        if(display.isEmpty())display="Автор не указан";
        String when=author==null?"":author.optString("date","");
        if(when.isEmpty()&&committer!=null)when=committer.optString("date","");
        TextView by=label(display+(when.isEmpty()?"":" · "+formatDate(when)),12,false,muted);
        add(by,0,9);
        String body=message.indexOf('\n')<0?"":message.substring(message.indexOf('\n')+1).trim();
        if(!body.isEmpty()){
            TextView description=label(body,12,false,foreground);
            description.setTextIsSelectable(true);
            description.setPadding(dp(12),dp(10),dp(12),dp(10));
            description.setBackground(shape(card,12));
            add(description,0,8);
        }
        TextView id=label("SHA  "+sha,11,false,muted);
        id.setTextIsSelectable(true);
        id.setTypeface(Typeface.MONOSPACE);
        add(id,0,9);
        JSONArray parents=result.optJSONArray("parents");
        if(parents!=null&&parents.length()>0) {
            StringBuilder values=new StringBuilder("Родительские коммиты: ");
            for(int i=0;i<Math.min(4,parents.length());i++){
                JSONObject parent=parents.optJSONObject(i);
                if(parent!=null)values.append(i==0?"":", ").append(shortSha(parent.optString("sha","")));
            }
            TextView parentsLabel=label(values.toString()+"  ›",11,true,accent);
            parentsLabel.setContentDescription("Открыть родительский коммит");
            JSONObject firstParent=parents.optJSONObject(0);
            String firstParentSha=firstParent==null?"":firstParent.optString("sha","");
            if(CommitDetailController.validSha(firstParentSha))
                parentsLabel.setOnClickListener(v->actions.openParent(firstParentSha));
            add(parentsLabel,0,8);
        }
        LinearLayout buttons=new LinearLayout(activity);
        TextView repository=action("Репозиторий",()->actions.openRepository(repo));
        buttons.addView(repository,new LinearLayout.LayoutParams(0,dp(44),1));
        TextView external=action("Открыть GitHub",()->actions.openExternalCommit(repo,sha));
        LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(0,dp(44),1);
        right.setMargins(dp(7),0,0,0);
        buttons.addView(external,right);
        add(buttons,0,14);

        JSONObject stats=result.optJSONObject("stats");
        String totals=stats==null?"":" · +"+stats.optInt("additions",0)
                +" / −"+stats.optInt("deletions",0);
        TextView heading=label("Изменённые файлы: "+detail.files.length()+totals,16,true,foreground);
        add(heading,0,8);
        if(detail.partial){
            TextView warning=label("Список файлов неполный. "+detail.warning,12,false,negative);
            add(warning,0,7);
        }
        if(detail.files.length()==0){
            add(label("GitHub не предоставил список изменённых файлов.",12,false,muted),0,0);
            return;
        }
        filesHost=new LinearLayout(activity);
        filesHost.setOrientation(LinearLayout.VERTICAL);
        add(filesHost,0,0);
        appendFileBatch(detail.files,0);
    }

    private void appendFileBatch(JSONArray files,int from){
        final int end=Math.min(files.length(),from+24);
        for(int i=from;i<end;i++){
            JSONObject file=files.optJSONObject(i);
            if(file==null)continue;
            final int index=i;
            LinearLayout item=new LinearLayout(activity);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setBackground(shape(card,13));
            item.setPadding(dp(9),dp(9),dp(9),dp(9));
            LinearLayout head=new LinearLayout(activity);
            head.setGravity(Gravity.CENTER_VERTICAL);
            String filename=file.optString("filename","Файл");
            TextView path=label(filename,12,true,foreground);
            path.setMaxLines(3);
            path.setEllipsize(TextUtils.TruncateAt.END);
            head.addView(path,new LinearLayout.LayoutParams(0,-2,1));
            TextView more=label("⌄",19,true,accent);
            head.addView(more,new LinearLayout.LayoutParams(dp(26),dp(32)));
            head.setMinimumHeight(dp(45));
            item.addView(head);
            String fileStatus=file.optString("status","");
            String previous=file.optString("previous_filename","");
            if(!previous.isEmpty())fileStatus+=" · раньше: "+previous;
            TextView changed=label(fileStatus+"    +"+file.optInt("additions",0)
                    +"  −"+file.optInt("deletions",0),11,false,muted);
            item.addView(changed);
            LinearLayout diffHost=new LinearLayout(activity);
            diffHost.setOrientation(LinearLayout.VERTICAL);
            item.addView(diffHost,new LinearLayout.LayoutParams(-1,-2));
            head.setContentDescription("Показать изменения файла "+filename);
            head.setOnClickListener(v->{
                if(expandedIndex==index){
                    diffHost.removeAllViews();
                    expandedIndex=-1;
                    expandedPatch=null;
                    more.setText("⌄");
                    return;
                }
                if(expandedPatch!=null)expandedPatch.removeAllViews();
                expandedPatch=diffHost;
                expandedIndex=index;
                more.setText("⌃");
                renderPatch(file,diffHost);
            });
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
            params.setMargins(0,0,0,dp(7));
            filesHost.addView(item,params);
            if(i==0&&from==0)head.performClick();
        }
        if(end<files.length()){
            TextView next=action("Показать ещё файлы · "+(files.length()-end),()->{});
            next.setOnClickListener(v->{
                filesHost.removeView(next);
                appendFileBatch(files,end);
            });
            addTo(filesHost,next,0,10);
        }
    }

    /** Show every GitHub-provided patch line in bounded pages instead of truncating at 550. */
    private void renderPatch(JSONObject file,LinearLayout target){
        target.removeAllViews();
        String patch=file.optString("patch","");
        if(patch.isEmpty()){
            addTo(target,label("GitHub не предоставил текстовый patch: файл бинарный или изменение слишком большое.",12,false,muted),0,7);
            String raw=file.optString("raw_url","");
            if(raw.startsWith("https://"))
                addTo(target,action("Предпросмотр файла",()->actions.openRemoteFile(file)),0,5);
            return;
        }
        List<DiffModel.Line> lines=DiffModel.parse(patch).displayLines(true,4);
        if(lines.isEmpty()){addTo(target,label("Нет текстовых изменений.",12,false,muted),0,5);return;}
        appendPatchPage(target,lines,0);
    }

    private void appendPatchPage(LinearLayout target,List<DiffModel.Line> lines,int from){
        final int end=Math.min(lines.size(),from+220);
        SpannableStringBuilder result=new SpannableStringBuilder();
        for(int i=from;i<end;i++){
            DiffModel.Line line=lines.get(i);
            String lineText;
            int color=foreground;
            int background=Color.TRANSPARENT;
            switch(line.kind){
                case ADD:
                    lineText=String.format(Locale.ROOT,"+ %4d  %s",line.newLine,line.text);
                    color=positive;background=mix(card,positive,0.11f);break;
                case DELETE:
                    lineText=String.format(Locale.ROOT,"− %4d  %s",line.oldLine,line.text);
                    color=negative;background=mix(card,negative,0.11f);break;
                case CONTEXT:
                    lineText=String.format(Locale.ROOT,"  %4d  %s",line.newLine,line.text);break;
                case HUNK:lineText=line.text;color=blue;break;
                default:lineText=line.text;color=muted;break;
            }
            int start=result.length();result.append(lineText).append("\n");
            result.setSpan(new ForegroundColorSpan(color),start,result.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if(background!=Color.TRANSPARENT)
                result.setSpan(new BackgroundColorSpan(background),start,result.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        HorizontalScrollView horizontal=new HorizontalScrollView(activity);
        horizontal.setFillViewport(true);
        TextView code=label("",11,false,foreground);
        code.setTypeface(Typeface.MONOSPACE);code.setTextIsSelectable(true);
        code.setHorizontallyScrolling(true);code.setPadding(dp(8),dp(8),dp(8),dp(8));
        code.setText(result);
        horizontal.addView(code,new android.widget.FrameLayout.LayoutParams(-2,-2));
        addTo(target,horizontal,0,6);
        if(end<lines.size()){
            TextView more=action("Показать строки "+(end+1)+"–"+
                    Math.min(lines.size(),end+220)+" из "+lines.size(),()->{});
            more.setOnClickListener(v->{target.removeView(more);appendPatchPage(target,lines,end);});
            addTo(target,more,0,6);
        }
    }

    private TextView action(String title,Runnable onClick){
        TextView button=label(title,12,true,accent);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(44));
        button.setBackground(shape(card,12));
        button.setOnClickListener(v->onClick.run());
        return button;
    }

    private void add(View child,int height,int margin){addTo(host,child,height,margin);}
    private void addTo(LinearLayout parent,View child,int height,int margin){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,height==0?-2:dp(height));
        p.setMargins(0,0,0,dp(margin));
        parent.addView(child,p);
    }
    private TextView label(String message,int size,boolean bold,int color){
        TextView text=new TextView(activity);
        text.setText(message);
        text.setTextSize(size);
        text.setTextColor(color);
        if(bold)text.setTypeface(null,Typeface.BOLD);
        return text;
    }
    private GradientDrawable shape(int fill,int radius){
        GradientDrawable b=new GradientDrawable();
        b.setColor(fill);
        b.setCornerRadius(dp(radius));
        b.setStroke(dp(1),border);
        return b;
    }
    private int mix(int a,int b,float t){
        return Color.rgb((int)(Color.red(a)*(1-t)+Color.red(b)*t),
                (int)(Color.green(a)*(1-t)+Color.green(b)*t),
                (int)(Color.blue(a)*(1-t)+Color.blue(b)*t));
    }
    private int dp(int value){
        return (int)(value*activity.getResources().getDisplayMetrics().density+.5f);
    }
    private static String shortSha(String sha){return sha.substring(0,Math.min(8,sha.length()));}
    private static String firstLine(String message){int i=message.indexOf('\n');return i<0?message:message.substring(0,i);}
    private static String formatDate(String raw){
        try{
            return java.time.Instant.parse(raw).atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        }catch(Exception ignored){return raw;}
    }
}
