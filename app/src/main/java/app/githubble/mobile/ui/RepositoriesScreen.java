package app.githubble.mobile.ui;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import app.githubble.mobile.R;

/** A native repository workspace, built from actual account-scoped RepositoryStore snapshots. */
public final class RepositoriesScreen {
    public interface Actions {
        void create();
        void refresh();
        void sort();
        void toggleLayout(boolean grid);
        void open(String fullName);
        void menu(JSONObject repository);
    }

    private final Activity activity;
    private final LinearLayout parent;
    private final Actions actions;
    private final int card, textColor, muted, green, border, subtle;
    private final Set<String> saved;
    private JSONArray repositories = new JSONArray();
    private String filter = "all";
    private String query = "";
    private boolean gridMode;
    private boolean languagesExpanded;
    private LinearLayout listHost, filtersHost;
    private TextView allCount, privateCount, publicCount, savedCount, resultCount, state;
    private EditText search;

    public RepositoriesScreen(Activity activity, LinearLayout parent, Actions actions,
                              int card, int textColor, int muted, int green, int border,
                              int subtle, Set<String> saved, boolean gridMode) {
        this.activity = activity;
        this.parent = parent;
        this.actions = actions;
        this.card = card;
        this.textColor = textColor;
        this.muted = muted;
        this.green = green;
        this.border = border;
        this.subtle = subtle;
        this.saved = saved;
        this.gridMode = gridMode;
    }

    public void render(JSONArray initial, String sortLabel, String initialStatus) {
        repositories = initial == null ? new JSONArray() : initial;

        LinearLayout overview = vertical();
        overview.setPadding(dp(12),dp(13),dp(12),dp(12));
        overview.setBackground(shape(mix(card, green, .045f),18,border,1));
        LinearLayout heading = horizontal(Gravity.CENTER_VERTICAL);
        ImageView brand = new ImageView(activity);
        brand.setImageResource(R.drawable.ic_repository);
        brand.setColorFilter(Color.WHITE);
        brand.setPadding(dp(9),dp(9),dp(9),dp(9));
        brand.setBackground(shape(green,11,0,0));
        heading.addView(brand, new LinearLayout.LayoutParams(dp(42),dp(42)));
        LinearLayout names = vertical();
        names.setPadding(dp(9),0,0,0);
        TextView title = label("Мои репозитории",17,true,textColor);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(title);
        TextView subtitle = label("Ваши проекты на GitHub",11,false,muted);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(subtitle);
        heading.addView(names,new LinearLayout.LayoutParams(0,-2,1));
        TextView create = label("+  Создать",12,true,green);
        create.setGravity(Gravity.CENTER);
        create.setBackground(shape(mix(card,green,.12f),20,0,0));
        create.setOnClickListener(v->actions.create());
        create.setContentDescription("Создать новый репозиторий");
        LinearLayout.LayoutParams createP = new LinearLayout.LayoutParams(dp(91),dp(44));
        createP.setMargins(dp(5),0,0,0);
        heading.addView(create,createP);
        overview.addView(heading);
        LinearLayout summary=horizontal(Gravity.CENTER);
        summary.setPadding(0,dp(12),0,0);
        allCount=stat(summary,R.drawable.ic_repository,"Все",green);
        privateCount=stat(summary,R.drawable.ic_lock,"Приватные",muted);
        publicCount=stat(summary,R.drawable.ic_globe,"Публичные",green);
        savedCount=stat(summary,R.drawable.ic_star,"Избранные",green);
        overview.addView(summary);
        add(overview,0,10);

        search = new EditText(activity);
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setTextColor(textColor);
        search.setHintTextColor(muted);
        search.setHint("⌕  Поиск по репозиториям");
        search.setBackground(shape(mix(card,subtle,.5f),14,border,1));
        search.setPadding(dp(14),0,dp(10),0);
        search.setOnFocusChangeListener((v,hasFocus)->{ if(!hasFocus)hideKeyboard(); });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) {
                query=s.toString().trim().toLowerCase(Locale.ROOT);
                updateRows();
            }
            @Override public void afterTextChanged(Editable e) {}
        });
        add(search,dp(49),8);
        HorizontalScrollView filterScroll = new HorizontalScrollView(activity);
        filterScroll.setHorizontalScrollBarEnabled(false);
        filtersHost=horizontal(Gravity.CENTER_VERTICAL);
        filtersHost.setPadding(0,0,0,0);
        filterScroll.addView(filtersHost);
        add(filterScroll,dp(42),9);
        makeFilters();
        LinearLayout sorting=horizontal(Gravity.CENTER_VERTICAL);
        TextView sort=label("↕  "+sortLabel+"  ⌄",12,false,textColor);
        sort.setGravity(Gravity.CENTER_VERTICAL);
        sort.setPadding(dp(12),0,dp(12),0);
        sort.setBackground(shape(card,13,border,1));
        sort.setContentDescription("Сортировать репозитории");
        sort.setOnClickListener(v->actions.sort());
        sorting.addView(sort,new LinearLayout.LayoutParams(-2,dp(42)));
        TextView layout=label(gridMode?"▤ Плитки":"☰ Список",12,true,green);
        layout.setGravity(Gravity.CENTER);
        layout.setContentDescription(gridMode?"Переключить на список":"Переключить на плитки");
        layout.setBackground(shape(card,12,border,1));
        LinearLayout.LayoutParams layoutParams=new LinearLayout.LayoutParams(dp(91),dp(42));
        layoutParams.setMargins(dp(6),0,0,0);
        sorting.addView(layout,layoutParams);
        layout.setOnClickListener(v->{
            gridMode=!gridMode;
            actions.toggleLayout(gridMode);
            layout.setText(gridMode?"▤ Плитки":"☰ Список");
            layout.setContentDescription(gridMode?"Переключить на список":"Переключить на плитки");
            updateRows();
        });
        resultCount=label("",11,false,muted);
        resultCount.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        resultCount.setSingleLine(true);
        sorting.addView(resultCount,new LinearLayout.LayoutParams(0,dp(42),1));
        add(sorting,dp(42),8);
        state=label(initialStatus,10,false,muted);
        state.setSingleLine(true);
        state.setEllipsize(TextUtils.TruncateAt.END);
        add(state,dp(18),4);
        listHost=vertical();
        add(listHost,0,0);
        updateData(repositories);
    }

    public void updateData(JSONArray newest) {
        repositories=newest == null ? new JSONArray() : newest;
        int priv=0,pub=0,pinned=0;
        for(int i=0;i<repositories.length();i++){
            JSONObject r=repositories.optJSONObject(i);
            if(r==null)continue;
            if(r.optBoolean("private",false))priv++;else pub++;
            if(saved.contains(r.optString("full_name","")))pinned++;
        }
        allCount.setText(Integer.toString(priv+pub));
        privateCount.setText(Integer.toString(priv));
        publicCount.setText(Integer.toString(pub));
        savedCount.setText(Integer.toString(pinned));
        updateRows();
    }

    public void setStatus(String status) { if(state!=null)state.setText(status); }

    private TextView stat(LinearLayout row,int icon,String title,int tint){
        LinearLayout cell=vertical();
        cell.setGravity(Gravity.CENTER);
        ImageView art=new ImageView(activity);
        art.setImageResource(icon);
        art.setColorFilter(tint);
        cell.addView(art,new LinearLayout.LayoutParams(dp(20),dp(20)));
        TextView value=label("—",17,true,green);
        value.setGravity(Gravity.CENTER);
        cell.addView(value);
        TextView caption=label(title,10,false,muted);
        caption.setSingleLine(true);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        caption.setGravity(Gravity.CENTER);
        cell.addView(caption,new LinearLayout.LayoutParams(-1,-2));
        row.addView(cell,new LinearLayout.LayoutParams(0,dp(76),1));
        return value;
    }

    private void makeFilters(){
        filtersHost.removeAllViews();
        chip("Все","all");
        chip("Недавние","recent");
        chip("Приватные","private");
        chip("Публичные","public");
        chip("Избранные","saved");
        chip(languagesExpanded?"Языки ▴":"Языки ▾","langs");
        if(languagesExpanded||isLanguageFilter()){
            chip("Kotlin","kotlin");
            chip("Java","java");
            chip("Go","go");
            chip("C++","c++");
        }
    }
    private boolean isLanguageFilter(){
        return !"all".equals(filter)&&!"recent".equals(filter)
                &&!"private".equals(filter)&&!"public".equals(filter)&&!"saved".equals(filter);
    }
    private void chip(String title,String value){
        boolean active=filter.equals(value)||("langs".equals(value)&&languagesExpanded);
        TextView chip=label(title,12,active,active?Color.WHITE:textColor);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(12),0,dp(12),0);
        chip.setBackground(shape(active?green:card,17,active?green:border,active?0:1));
        chip.setOnClickListener(v->{
            if("langs".equals(value)){
                languagesExpanded=!languagesExpanded;
                makeFilters();
                return;
            }
            filter=value;
            makeFilters();
            updateRows();
        });
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(36));
        p.setMargins(0,0,dp(6),0);
        filtersHost.addView(chip,p);
    }

    /**
     * Rendering at most 24 cards per batch avoids a frozen search screen when
     * an installation exposes hundreds of private repositories.
     */
    private void updateRows(){
        if(listHost==null)return;
        listHost.removeAllViews();
        List<JSONObject> matching=new ArrayList<>();
        for(int i=0;i<repositories.length();i++){
            JSONObject repo=repositories.optJSONObject(i);
            if(repo==null)continue;
            String full=repo.optString("full_name","");
            if(full.isEmpty())continue;
            String language=repo.optString("language","");
            String haystack=(full+" "+repo.optString("description","")+" "+language)
                    .toLowerCase(Locale.ROOT);
            if(!haystack.contains(query))continue;
            if("private".equals(filter)&&!repo.optBoolean("private",false))continue;
            if("public".equals(filter)&&repo.optBoolean("private",false))continue;
            if("saved".equals(filter)&&!saved.contains(full))continue;
            if("recent".equals(filter)&&!isRecent(repo))continue;
            if(isLanguageFilter()&&!filter.equalsIgnoreCase(language))continue;
            matching.add(repo);
        }
        resultCount.setText("Найдено: "+matching.size());
        if(matching.isEmpty()){
            TextView empty=label(repositories.length()==0
                    ?"Репозитории пока не загружены. Нажмите «Обновить»."
                    :"По этому запросу репозиториев не найдено.",13,false,muted);
            empty.setPadding(dp(12),dp(20),dp(12),dp(20));
            empty.setBackground(shape(card,14,border,1));
            listHost.addView(empty);
            return;
        }
        ViewGroup rows=listHost;
        if(gridMode){
            GridLayout grid=new GridLayout(activity);
            boolean tablet=activity.getResources().getConfiguration().smallestScreenWidthDp>=600
                    &&activity.getResources().getConfiguration().fontScale<1.5f;
            grid.setColumnCount(tablet?2:1);
            listHost.addView(grid,new LinearLayout.LayoutParams(-1,-2));
            rows=grid;
        }
        appendRows(matching,0,rows);
    }

    private void appendRows(List<JSONObject> matching,int start,ViewGroup target){
        int end=Math.min(start+24,matching.size());
        for(int i=start;i<end;i++)addRepository(matching.get(i),target);
        if(end>=matching.size())return;
        TextView more=label("Показать ещё "+(matching.size()-end)+" репозиториев  ›",
                13,true,green);
        more.setGravity(Gravity.CENTER);
        more.setBackground(shape(card,13,border,1));
        more.setContentDescription("Загрузить следующие репозитории");
        LinearLayout.LayoutParams moreParams=new LinearLayout.LayoutParams(-1,dp(48));
        moreParams.setMargins(0,dp(4),0,dp(8));
        listHost.addView(more,moreParams);
        more.setOnClickListener(v->{
            listHost.removeView(more);
            appendRows(matching,end,target);
        });
    }

    private boolean isRecent(JSONObject r){
        try {
            String value=r.optString("pushed_at",r.optString("updated_at",""));
            long age=System.currentTimeMillis()-Instant.parse(value).toEpochMilli();
            return age>=0&&age<30L*24*60*60*1000L;
        }catch(Exception ignored){return false;}
    }

    private void addRepository(JSONObject repo,ViewGroup target){
        String full=repo.optString("full_name","");
        String name=repo.optString("name",full.contains("/")
                ?full.substring(full.indexOf('/')+1):full);
        boolean privateRepo=repo.optBoolean("private",false);
        String description=repo.optString("description","");
        if(description.isEmpty()||"null".equals(description))description="Описание не указано";
        String language=repo.optString("language","");
        if(language.isEmpty()||"null".equals(language))language="—";
        int accent=languageColor(language);

        LinearLayout box=vertical();
        box.setPadding(dp(11),dp(11),dp(11),dp(9));
        box.setBackground(shape(card,15,border,1));
        LinearLayout titleLine=horizontal(Gravity.CENTER_VERTICAL);
        ImageView icon=new ImageView(activity);
        icon.setImageResource(name.equalsIgnoreCase("GitHubble")
                ?R.drawable.ic_home_rocket:R.drawable.ic_repository);
        icon.setColorFilter(Color.WHITE);
        icon.setPadding(dp(10),dp(10),dp(10),dp(10));
        icon.setBackground(shape(accent,11,0,0));
        titleLine.addView(icon,new LinearLayout.LayoutParams(dp(43),dp(43)));

        LinearLayout info=vertical();
        info.setPadding(dp(9),0,0,0);
        TextView title=label(name,14,true,textColor);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(title);
        TextView detail=label(description,11,false,muted);
        detail.setMaxLines(2);
        detail.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(detail);
        titleLine.addView(info,new LinearLayout.LayoutParams(0,-2,1));

        TextView privacy=label(privateRepo?"◧ Закрыт":"◎ Открыт",10,false,privateRepo?muted:green);
        privacy.setPadding(dp(5),dp(4),dp(5),dp(4));
        privacy.setBackground(shape(mix(card,privateRepo?muted:green,.10f),15,0,0));
        LinearLayout.LayoutParams privacyLp=new LinearLayout.LayoutParams(-2,-2);
        privacyLp.setMargins(dp(3),0,0,0);
        titleLine.addView(privacy,privacyLp);

        TextView more=label("⋮",23,true,muted);
        more.setGravity(Gravity.CENTER);
        more.setContentDescription("Действия с репозиторием "+name);
        more.setOnClickListener(v->actions.menu(repo));
        LinearLayout.LayoutParams moreLp=new LinearLayout.LayoutParams(dp(30),dp(40));
        titleLine.addView(more,moreLp);
        box.addView(titleLine);
        LinearLayout meta=horizontal(Gravity.CENTER_VERTICAL);
        meta.setPadding(0,dp(8),0,0);
        ImageView dot=new ImageView(activity);
        dot.setBackground(shape(accent,6,0,0));
        meta.addView(dot,new LinearLayout.LayoutParams(dp(9),dp(9)));
        TextView lang=label(" "+language,10,false,textColor);
        lang.setSingleLine(true);
        meta.addView(lang);
        TextView stats=label(" ★ "+repo.optInt("stargazers_count",0)
                +"   ⑂ "+repo.optInt("forks_count",0)
                +"   ◉ "+repo.optInt("open_issues_count",0),10,false,muted);
        stats.setSingleLine(true);
        stats.setEllipsize(TextUtils.TruncateAt.END);
        meta.addView(stats,new LinearLayout.LayoutParams(0,-2,1));
        TextView updated=label(relativeUpdated(repo),10,false,muted);
        updated.setSingleLine(true);
        updated.setEllipsize(TextUtils.TruncateAt.END);
        updated.setGravity(Gravity.END);
        meta.addView(updated,new LinearLayout.LayoutParams(-2,-2));
        box.addView(meta);
        box.setContentDescription(full+(privateRepo?", приватный репозиторий":", публичный репозиторий"));
        box.setOnClickListener(v->actions.open(full));
        if(target instanceof GridLayout){
            GridLayout.LayoutParams p=new GridLayout.LayoutParams();
            p.width=0;
            p.height=-2;
            p.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);
            p.setMargins(dp(3),dp(3),dp(3),dp(6));
            target.addView(box,p);
        }else{
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
            p.setMargins(0,0,0,dp(8));
            target.addView(box,p);
        }
    }

    private String relativeUpdated(JSONObject repo){
        try{
            String raw=repo.optString("pushed_at",repo.optString("updated_at",""));
            long age=System.currentTimeMillis()-Instant.parse(raw).toEpochMilli();
            if(age<0)return "Обновлён недавно";
            long mins=age/60000;
            if(mins<1)return "Сейчас";
            if(mins<60)return "Обновлён "+mins+" мин";
            if(mins<1440)return "Обновлён "+(mins/60)+" ч";
            if(mins<10080)return "Обновлён "+(mins/1440)+" д";
            return "Обновлён "+(mins/10080)+" нед";
        }catch(Exception ignored){return "Дата неизвестна";}
    }

    private int languageColor(String lang){
        if("Kotlin".equalsIgnoreCase(lang))return 0xFF7755CC;
        if("Java".equalsIgnoreCase(lang))return 0xFFC27A38;
        if("Go".equalsIgnoreCase(lang))return 0xFF159E97;
        if("C++".equalsIgnoreCase(lang))return 0xFF4C7BBB;
        if("Python".equalsIgnoreCase(lang))return 0xFF4E9B70;
        return green;
    }

    private void hideKeyboard() {
        try{
            android.view.inputmethod.InputMethodManager imm=
                    (android.view.inputmethod.InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
            if(imm!=null)imm.hideSoftInputFromWindow(search.getWindowToken(),0);
        }catch(Exception ignored){}
    }

    private LinearLayout horizontal(int gravity){
        LinearLayout v=new LinearLayout(activity);
        v.setGravity(gravity);
        return v;
    }
    private LinearLayout vertical(){
        LinearLayout v=new LinearLayout(activity);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }
    private TextView label(String value,int sp,boolean bold,int color){
        TextView v=new TextView(activity);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        if(bold)v.setTypeface(null,Typeface.BOLD);
        return v;
    }
    private GradientDrawable shape(int fill,int radius,int stroke,int width){
        GradientDrawable d=new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radius));
        if(width>0)d.setStroke(dp(width),stroke);
        return d;
    }
    private int mix(int a,int b,float w){
        return Color.rgb((int)(Color.red(a)*(1-w)+Color.red(b)*w),
                (int)(Color.green(a)*(1-w)+Color.green(b)*w),
                (int)(Color.blue(a)*(1-w)+Color.blue(b)*w));
    }
    private void add(View item,int height,int bottom){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,height==0?-2:height);
        p.setMargins(0,0,0,dp(bottom));
        parent.addView(item,p);
    }
    private int dp(int value){
        return (int)(value*activity.getResources().getDisplayMetrics().density+.5f);
    }
}
