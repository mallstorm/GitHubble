package app.githubble.mobile.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;
import app.githubble.mobile.R;

/** Native GitHubble activity timeline. All values come from the available GitHub feed. */
public final class ActivityScreen {
    public interface Actions {
        void openEvent(JSONObject event);
        void refresh();
        void openGitHubNotifications();
        void openBuilds();
    }

    private final Activity activity;
    private final LinearLayout content;
    private final Actions actions;
    private final int card, textColor, muted, green, border, subtle;
    private final boolean dark;
    private LinearLayout feedHost;
    private LinearLayout filterHost;
    private LinearLayout statsHost;
    private TextView status;
    private String selected="Все";
    private List<JSONObject> feed = new ArrayList<>();
    private static final String[] FILTERS={"Все","Репозитории","PR","Issues","Обсуждения","Сборки"};

    public ActivityScreen(Activity activity, LinearLayout content, Actions actions,
                          int card,int textColor,int muted,int green,int border,int subtle,boolean dark) {
        this.activity=activity;
        this.content=content;
        this.actions=actions;
        this.card=card;
        this.textColor=textColor;
        this.muted=muted;
        this.green=green;
        this.border=border;
        this.subtle=subtle;
        this.dark=dark;
    }

    public void render(List<JSONObject> initial, String initialStatus) {
        LinearLayout hero=horizontal(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(11),dp(12),dp(9),dp(12));
        hero.setBackground(round(mix(card,green,dark?.11f:.035f),17,border,1));
        ImageView bell=icon(R.drawable.ic_bell,Color.WHITE);
        bell.setPadding(dp(16),dp(16),dp(16),dp(16));
        bell.setBackground(round(green,14,0,0));
        hero.addView(bell,new LinearLayout.LayoutParams(dp(57),dp(57)));
        LinearLayout titleBlock=vertical();
        titleBlock.setPadding(dp(11),0,0,0);
        TextView title=label("Активность GitHub",19,true,textColor);
        TextView subtitle=label("События проектов, задач и Pull Requests",11,false,muted);
        subtitle.setMaxLines(2);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        titleBlock.addView(title);
        titleBlock.addView(subtitle);
        hero.addView(titleBlock,new LinearLayout.LayoutParams(0,-2,1));
        TextView more=label("›",25,false,muted);
        more.setGravity(Gravity.CENTER);
        more.setContentDescription("Открыть уведомления на сайте GitHub");
        more.setOnClickListener(v->actions.openGitHubNotifications());
        hero.addView(more,new LinearLayout.LayoutParams(dp(24),dp(48)));
        add(hero,0,dp(9));

        HorizontalScrollView scroller=new HorizontalScrollView(activity);
        scroller.setHorizontalScrollBarEnabled(false);
        filterHost=horizontal(Gravity.CENTER_VERTICAL);
        scroller.addView(filterHost);
        add(scroller,dp(43),dp(9));
        buildFilters();

        statsHost=vertical();
        add(statsHost,0,dp(9));
        status=label(initialStatus,10,false,muted);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setOnClickListener(v->actions.refresh());
        add(status,dp(20),dp(3));
        feedHost=vertical();
        add(feedHost,0,0);
        setFeed(initial);
    }

    public void setStatus(String value) {
        if(status!=null)status.setText(value);
    }

    public void setFeed(List<JSONObject> fresh){
        this.feed=fresh==null?new ArrayList<>():new ArrayList<>(fresh);
        drawStats();
        drawTimeline();
    }

    private void buildFilters(){
        filterHost.removeAllViews();
        for(String filter:FILTERS){
            boolean active=filter.equals(selected);
            TextView chip=label(filter,12,active,active?Color.WHITE:textColor);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(12),0,dp(12),0);
            chip.setBackground(round(active?green:card,14,active?green:border,active?0:1));
            chip.setOnClickListener(v->{
                if("Сборки".equals(filter)){actions.openBuilds();return;}
                selected=filter;
                buildFilters();
                drawTimeline();
            });
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(38));
            p.setMargins(0,0,dp(6),0);
            filterHost.addView(chip,p);
        }
    }

    private void drawStats(){
        if(statsHost==null)return;
        statsHost.removeAllViews();
        int today=0,pr=0,issues=0,discussions=0,releases=0,commits=0;
        for(JSONObject event:feed){
            if(localDay(event).equals(LocalDate.now()))today++;
            String kind=category(event);
            if("PR".equals(kind))pr++;
            else if("Issues".equals(kind))issues++;
            else if("Обсуждения".equals(kind))discussions++;
            else if("Релизы".equals(kind))releases++;
            else if("Коммиты".equals(kind))commits++;
        }
        LinearLayout row=horizontal(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(9),dp(11),dp(9),dp(10));
        row.setBackground(round(card,16,border,1));
        LinearLayout total=vertical();
        TextView number=label(Integer.toString(today),26,true,green);
        total.addView(number);
        TextView caption=label("событий в ленте сегодня",10,false,muted);
        caption.setMaxLines(2);
        total.addView(caption);
        row.addView(total,new LinearLayout.LayoutParams(0,-2,1.2f));
        View divider=new View(activity);
        divider.setBackgroundColor(border);
        row.addView(divider,new LinearLayout.LayoutParams(dp(1),dp(43)));
        miniStat(row,R.drawable.ic_pull_request,pr,"PR",0xFF347DE2);
        miniStat(row,R.drawable.ic_issue,issues,"Issues",0xFFDF455B);
        miniStat(row,R.drawable.ic_discussion,discussions,"Темы",0xFF8C50D7);
        miniStat(row,R.drawable.ic_release,releases,"Релизы",0xFFE98830);
        miniStat(row,R.drawable.ic_repository,commits,"Коммиты",green);
        statsHost.addView(row,new LinearLayout.LayoutParams(-1,dp(85)));
    }

    private void miniStat(LinearLayout host,int drawable,int value,String description,int tint) {
        LinearLayout cell=vertical();
        cell.setGravity(Gravity.CENTER);
        ImageView image=icon(drawable,tint);
        cell.addView(image,new LinearLayout.LayoutParams(dp(20),dp(20)));
        TextView count=label(Integer.toString(value),12,true,textColor);
        count.setGravity(Gravity.CENTER);
        cell.addView(count);
        TextView name=label(description,8,false,muted);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        cell.addView(name);
        host.addView(cell,new LinearLayout.LayoutParams(0,-1,1));
    }

    private void drawTimeline(){
        if(feedHost==null)return;
        feedHost.removeAllViews();
        List<JSONObject> matching=new ArrayList<>();
        for(JSONObject event:feed)if(matches(event))matching.add(event);
        if(matching.isEmpty()){
            TextView empty=label(feed.isEmpty()
                    ?"Нет доступных событий. Нажмите «Обновить» или проверьте доступ GitHub."
                    :"В выбранной категории событий пока нет.",13,false,muted);
            empty.setPadding(dp(12),dp(18),dp(12),dp(18));
            empty.setBackground(round(card,14,border,1));
            feedHost.addView(empty);
            return;
        }
        LocalDate today=LocalDate.now(), yesterday=today.minusDays(1);
        String previous="";
        for(JSONObject event:matching){
            LocalDate date=localDay(event);
            String group=date.equals(LocalDate.MIN)?"Без даты":date.equals(today)?"Сегодня":date.equals(yesterday)?"Вчера":
                    date.getYear()==today.getYear()?"Ранее · "+date.format(DateTimeFormatter.ofPattern("d MMMM",new Locale("ru"))):
                            date.format(DateTimeFormatter.ofPattern("d MMMM yyyy",new Locale("ru")));
            if(!group.equals(previous)){
                previous=group;
                TextView heading=label(group,19,true,textColor);
                LinearLayout.LayoutParams headingP=new LinearLayout.LayoutParams(-1,-2);
                headingP.setMargins(0,dp(8),0,dp(9));
                feedHost.addView(heading,headingP);
            }
            addEvent(event);
        }
    }

    private boolean matches(JSONObject event){
        if("Все".equals(selected))return true;
        String category=category(event);
        if("Репозитории".equals(selected))
            return "Релизы".equals(category)||"Коммиты".equals(category)||"Репозитории".equals(category);
        return selected.equals(category);
    }

    private String category(JSONObject event){
        String kind=event.optString("kind","Event");
        String type=event.optString("event_type","");
        if("PullRequest".equals(kind)||type.contains("PullRequest"))return "PR";
        if("Issue".equals(kind)||"IssuesEvent".equals(type)||"IssueCommentEvent".equals(type))return "Issues";
        if(type.contains("Discussion"))return "Обсуждения";
        if("ReleaseEvent".equals(type))return "Релизы";
        if("PushEvent".equals(type))return "Коммиты";
        return "Репозитории";
    }

    private void addEvent(JSONObject event){
        String repo=event.optString("repo","");
        if(repo.isEmpty())repo="GitHub";
        String kind=category(event);
        int tint= "PR".equals(kind)?0xFF367EE2:"Issues".equals(kind)?0xFFDF455B:
                "Обсуждения".equals(kind)?0xFF8C50D7:"Релизы".equals(kind)?0xFFE98830:green;
        int res= "PR".equals(kind)?R.drawable.ic_pull_request:
                "Issues".equals(kind)?R.drawable.ic_issue:
                "Обсуждения".equals(kind)?R.drawable.ic_discussion:
                "Релизы".equals(kind)?R.drawable.ic_release:
                "Коммиты".equals(kind)?R.drawable.ic_pull_request:R.drawable.ic_repository;

        LinearLayout tile=horizontal(Gravity.CENTER_VERTICAL);
        tile.setPadding(dp(9),dp(8),dp(8),dp(8));
        tile.setBackground(round(card,15,border,1));
        ImageView icon=icon(res,Color.WHITE);
        icon.setBackground(round(tint,11,0,0));
        icon.setPadding(dp(9),dp(9),dp(9),dp(9));
        tile.addView(icon,new LinearLayout.LayoutParams(dp(43),dp(43)));
        LinearLayout description=vertical();
        description.setPadding(dp(10),0,dp(3),0);
        TextView project=label(repo,13,true,textColor);
        project.setSingleLine(true);
        project.setEllipsize(TextUtils.TruncateAt.END);
        description.addView(project);
        String text=event.optString("title","Событие GitHub");
        int number=event.optInt("number",0);
        if(number>0&&!text.contains("#"+number))text+="#"+number;
        TextView title=label(text,11,false,textColor);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        description.addView(title);
        String details=event.optString("summary","");
        if(!details.isEmpty()){
            TextView summary=label(details,10,false,muted);
            summary.setSingleLine(true);
            summary.setEllipsize(TextUtils.TruncateAt.END);
            description.addView(summary);
        }
        String date=relativeTime(event.optString("updated_at",""));
        TextView age=label(date,10,false,muted);
        description.addView(age);
        tile.addView(description,new LinearLayout.LayoutParams(0,-2,1));
        TextView arrow=label("›",25,false,muted);
        arrow.setGravity(Gravity.CENTER);
        tile.addView(arrow,new LinearLayout.LayoutParams(dp(21),dp(38)));
        tile.setContentDescription(repo+": "+text+", "+date);
        tile.setOnClickListener(v->actions.openEvent(event));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,0,0,dp(7));
        feedHost.addView(tile,p);
    }

    private LocalDate localDay(JSONObject event){
        try{return Instant.parse(event.optString("updated_at",""))
                .atZone(ZoneId.systemDefault()).toLocalDate();}
        catch(Exception ignored){return LocalDate.MIN;}
    }

    private String relativeTime(String iso){
        try{
            long diff=System.currentTimeMillis()-Instant.parse(iso).toEpochMilli();
            if(diff<0)return "недавно";
            long minutes=diff/60000;
            if(minutes<1)return "только что";
            if(minutes<60)return minutes+" мин назад";
            if(minutes<1440)return (minutes/60)+" ч назад";
            if(minutes<2880)return "вчера, "+Instant.parse(iso)
                    .atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"));
            return Instant.parse(iso).atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        }catch(Exception ignored){return "дата неизвестна";}
    }

    private ImageView icon(int drawable,int tint){
        ImageView icon=new ImageView(activity);
        icon.setImageResource(drawable);
        icon.setColorFilter(tint);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        return icon;
    }
    private TextView label(String value,int size,boolean bold,int color){
        TextView t=new TextView(activity);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        if(bold)t.setTypeface(null,Typeface.BOLD);
        return t;
    }
    private LinearLayout horizontal(int gravity){
        LinearLayout row=new LinearLayout(activity);
        row.setGravity(gravity);
        return row;
    }
    private LinearLayout vertical(){
        LinearLayout col=new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        return col;
    }
    private GradientDrawable round(int fill,int radius,int stroke,int width){
        GradientDrawable d=new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radius));
        if(width>0)d.setStroke(dp(width),stroke);
        return d;
    }
    private int mix(int base,int accent,float w){
        return Color.rgb((int)(Color.red(base)*(1-w)+Color.red(accent)*w),
                (int)(Color.green(base)*(1-w)+Color.green(accent)*w),
                (int)(Color.blue(base)*(1-w)+Color.blue(accent)*w));
    }
    private void add(View view,int height,int bottomMargin) {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,height==0?-2:height);
        p.setMargins(0,0,0,bottomMargin);
        content.addView(view,p);
    }
    private int dp(int value){
        return (int)(value*activity.getResources().getDisplayMetrics().density+.5f);
    }
}
