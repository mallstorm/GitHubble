package app.githubble.mobile.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.ImageView;
import java.time.LocalDate;
import org.json.JSONArray;
import org.json.JSONObject;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.*;

import app.githubble.mobile.R;
import app.githubble.mobile.search.SearchFilters;
import app.githubble.mobile.search.SearchMode;
import app.githubble.mobile.search.SmartSearchHistory;
import java.util.*;

/** Unified public GitHub search entry screen with optional RU semantic expansion. */
public final class SearchScreen {
    public interface Actions {
        void search(String query, boolean intelligentRussian);
        void dictate(EditText target, ImageView microphone);
        void chooseDictationLanguage();
        default void searchAdvanced(String query, boolean intelligentRussian, SearchMode mode, SearchFilters filters) {
            search(query, intelligentRussian);
        }
    }

    public static final String PREF_SMART_RU = "smart_search_ru_enabled";
    private static final String PREF_MODE = "smart_search_mode";
    private static final String PREF_FILTER_PREFIX = "smart_filter_";

    private final Activity activity;
    private final LinearLayout content;
    private final HomeScreen.Palette palette;
    private final Actions actions;
    private final SharedPreferences prefs;
    private final SmartSearchHistory history;
    private final Map<SearchMode, TextView> modeViews = new EnumMap<>(SearchMode.class);
    private EditText query;
    private TextView smartToggle;
    private TextView subtitle;
    private LinearLayout savedBlock;
    private static final String SAVED_KEY="saved_searches_v1";
    private TextView filterSummary;
    private boolean smartEnabled;
    private SearchMode mode;
    private SearchFilters filters;

    public SearchScreen(Activity activity, LinearLayout content, HomeScreen.Palette palette, Actions actions) {
        this.activity = activity;
        this.content = content;
        this.palette = palette;
        this.actions = actions;
        this.prefs = activity.getSharedPreferences("githubble", Activity.MODE_PRIVATE);
        this.history = new SmartSearchHistory(prefs);
        boolean russianLocale = "ru".equalsIgnoreCase(Locale.getDefault().getLanguage());
        this.smartEnabled = prefs.getBoolean(PREF_SMART_RU, russianLocale);
        try { this.mode = SearchMode.valueOf(prefs.getString(PREF_MODE, SearchMode.REPOSITORIES.name())); }
        catch (Exception ignored) { this.mode = SearchMode.REPOSITORIES; }
        this.filters = loadFilters();
    }

    public void render() {
        FrameLayout searchBox=new FrameLayout(activity);
        searchBox.setBackground(round(palette.card,15,palette.border,1));
        ImageView lens=new ImageView(activity);lens.setImageResource(R.drawable.ic_search);
        lens.setColorFilter(palette.muted);
        FrameLayout.LayoutParams lensP=new FrameLayout.LayoutParams(dp(21),dp(21),Gravity.START|Gravity.CENTER_VERTICAL);
        lensP.setMargins(dp(11),0,0,0);searchBox.addView(lens,lensP);
        query=new EditText(activity);query.setHintTextColor(palette.muted);
        query.setTextColor(palette.text);query.setSingleLine(true);query.setTextSize(14);
        query.setPadding(dp(39),0,dp(56),0);query.setBackgroundColor(Color.TRANSPARENT);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setContentDescription("Поиск GitHub");
        searchBox.addView(query,new FrameLayout.LayoutParams(-1,-1));
        ImageView mic=new ImageView(activity);
        mic.setImageResource(R.drawable.ic_mic);
        mic.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        mic.setContentDescription("Начать голосовой поиск; долгое нажатие — выбор языка");
        mic.setPadding(dp(11),dp(11),dp(11),dp(11));
        mic.setOnClickListener(v->actions.dictate(query,mic));
        mic.setOnLongClickListener(v->{actions.chooseDictationLanguage();return true;});
        FrameLayout.LayoutParams micP=new FrameLayout.LayoutParams(dp(52),dp(52),Gravity.END|Gravity.CENTER_VERTICAL);
        searchBox.addView(mic,micP);
        LinearLayout.LayoutParams qP=new LinearLayout.LayoutParams(-1,dp(52));
        qP.setMargins(0,dp(5),0,dp(8));content.addView(searchBox,qP);
        renderModeSelector();renderFilterRow();
        Button go=new Button(activity);go.setText("⌕  Найти на GitHub");
        go.setAllCaps(false);go.setTextSize(14);go.setTextColor(Color.WHITE);
        go.setBackground(round(palette.green,13,0,0));go.setOnClickListener(v->submit());
        content.addView(go,new LinearLayout.LayoutParams(-1,dp(48)));
        query.setOnEditorActionListener((v,id,event)->{
            if(id==EditorInfo.IME_ACTION_SEARCH){submit();return true;}return false;
        });
        applyModeVisuals();applySmartVisuals();
        renderRecentSearches();
        section("Быстрые фильтры","Все фильтры",this::showFilterDialog);
        quickFilters();
        section("Популярные направления","Все",()->
            actions.searchAdvanced("stars:>1000",false,SearchMode.REPOSITORIES,new SearchFilters()));
        LinearLayout popular=new LinearLayout(activity);popular.setOrientation(LinearLayout.VERTICAL);
        popular.setBackground(round(palette.card,14,palette.border,1));
        direction(popular,"Android","Мобильные приложения и библиотеки",
                R.drawable.ic_android,"topic:android",false);
        direction(popular,"Kotlin","JVM- и Android-проекты",
                R.drawable.ic_kotlin_brand,"language:kotlin",true);
        direction(popular,"Open Source","Популярные открытые проекты",
                R.drawable.ic_star,"stars:>10000",true);
        direction(popular,"Новые проекты","Созданы за последние 30 дней",
                R.drawable.ic_history,"created:>="+LocalDate.now().minusDays(30),true);
        content.addView(popular,new LinearLayout.LayoutParams(-1,-2));
        section("Сохранённые поиски","Сохранить текущий",this::saveQuery);
        savedBlock=new LinearLayout(activity);savedBlock.setOrientation(LinearLayout.VERTICAL);
        content.addView(savedBlock);drawSaved();
    }

    private void section(String title,String link,Runnable action){
        LinearLayout line=new LinearLayout(activity);line.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading=text(title,17,true);heading.setSingleLine(true);
        heading.setEllipsize(TextUtils.TruncateAt.END);heading.setPadding(0,0,0,0);
        line.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        TextView right=text(link+" ›",11,true);right.setTextColor(palette.green);
        right.setGravity(Gravity.CENTER_VERTICAL|Gravity.END);
        right.setOnClickListener(v->action.run());line.addView(right);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(41));
        p.setMargins(0,dp(9),0,dp(3));content.addView(line,p);
    }

    private void quickFilters(){
        LinearLayout line=new LinearLayout(activity);
        String[] names={"Android","Kotlin","Приватные","За 30 дней"};
        String[] details={"Мобильные","JVM","С доступом","Обновлены"};
        String[] terms={"topic:android","language:kotlin","is:private",
                "pushed:>="+LocalDate.now().minusDays(30)};
        int[] icons={R.drawable.ic_android,R.drawable.ic_kotlin_brand,R.drawable.ic_lock,R.drawable.ic_history};
        for(int i=0;i<4;i++){
            final String term=terms[i];
            LinearLayout tile=new LinearLayout(activity);tile.setOrientation(LinearLayout.VERTICAL);
            tile.setPadding(dp(6),dp(6),dp(3),dp(4));
            tile.setBackground(round(palette.card,11,palette.border,1));
            ImageView art=new ImageView(activity);art.setImageResource(icons[i]);
            if(i!=1)art.setColorFilter(palette.green);
            LinearLayout.LayoutParams artP=new LinearLayout.LayoutParams(dp(23),dp(23));
            artP.setMargins(dp(3),0,0,dp(4));tile.addView(art,artP);
            TextView name=text(names[i],10,true);name.setPadding(0,0,0,0);
            name.setSingleLine(true);name.setEllipsize(TextUtils.TruncateAt.END);tile.addView(name);
            TextView desc=text(details[i],9,false);desc.setPadding(0,0,0,0);
            desc.setTextColor(palette.muted);desc.setSingleLine(true);
            desc.setEllipsize(TextUtils.TruncateAt.END);tile.addView(desc);
            tile.setOnClickListener(v->actions.searchAdvanced(term,false,
                    SearchMode.REPOSITORIES,new SearchFilters()));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(78),1);
            p.setMargins(i==0?0:dp(2),0,i==3?0:dp(2),0);line.addView(tile,p);
        }
        content.addView(line);
    }

    private void direction(LinearLayout container,String title,String sub,int image,String term,boolean divider){
        if(divider){View v=new View(activity);v.setBackgroundColor(palette.border);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(1));
            lp.setMargins(dp(10),0,dp(10),0);container.addView(v,lp);}
        LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8),dp(6),dp(7),dp(6));
        ImageView art=new ImageView(activity);art.setImageResource(image);
        if(image!=R.drawable.ic_kotlin_brand)art.setColorFilter(palette.green);
        art.setPadding(dp(10),dp(10),dp(10),dp(10));
        art.setBackground(round(palette.subtle,10,0,0));
        row.addView(art,new LinearLayout.LayoutParams(dp(42),dp(42)));
        LinearLayout labels=new LinearLayout(activity);labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(9),0,0,0);
        TextView name=text(title,13,true);name.setPadding(0,0,0,0);
        TextView detail=text(sub,11,false);detail.setPadding(0,0,0,0);
        detail.setTextColor(palette.muted);detail.setSingleLine(true);
        detail.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(name);labels.addView(detail);
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        TextView arrow=text("›",22,false);arrow.setTextColor(palette.muted);
        arrow.setGravity(Gravity.CENTER);row.addView(arrow,new LinearLayout.LayoutParams(dp(20),dp(38)));
        row.setOnClickListener(v->actions.searchAdvanced(term,false,
                SearchMode.REPOSITORIES,new SearchFilters()));
        container.addView(row,new LinearLayout.LayoutParams(-1,dp(58)));
    }

    private JSONArray saved(){
        try{return new JSONArray(prefs.getString(SAVED_KEY,"[]"));}
        catch(Exception ignored){return new JSONArray();}
    }
    private void saveQuery(){
        String value=query.getText().toString().trim();
        if(value.isEmpty()){Toast.makeText(activity,"Введите запрос для сохранения",Toast.LENGTH_SHORT).show();return;}
        JSONArray old=saved(),next=new JSONArray();
        try{
            next.put(new JSONObject().put("q",value).put("mode",mode.name()).put("smart",smartEnabled));
            for(int i=0;i<old.length()&&next.length()<8;i++){
                JSONObject item=old.optJSONObject(i);
                if(item!=null&&!value.equalsIgnoreCase(item.optString("q","")))next.put(item);
            }
            prefs.edit().putString(SAVED_KEY,next.toString()).apply();drawSaved();
        }catch(Exception ignored){}
    }
    private void drawSaved(){
        if(savedBlock==null)return;
        savedBlock.removeAllViews();
        JSONArray entries=saved();
        if(entries.length()==0){
            TextView empty=text("Пока нет сохранённых запросов. Введите запрос и нажмите «Сохранить текущий».",11,false);
            empty.setTextColor(palette.muted);empty.setPadding(dp(10),dp(10),dp(10),dp(10));
            empty.setBackground(round(palette.card,12,palette.border,1));savedBlock.addView(empty);return;
        }
        LinearLayout group=new LinearLayout(activity);group.setOrientation(LinearLayout.VERTICAL);
        group.setBackground(round(palette.card,13,palette.border,1));
        for(int i=0;i<Math.min(5,entries.length());i++){
            JSONObject item=entries.optJSONObject(i);if(item==null)continue;
            final int idx=i;final String term=item.optString("q","");
            if(i>0){View divider=new View(activity);divider.setBackgroundColor(palette.border);
                group.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));}
            LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(11),0,dp(8),0);
            ImageView mark=new ImageView(activity);mark.setImageResource(R.drawable.ic_star);
            mark.setColorFilter(palette.green);
            row.addView(mark,new LinearLayout.LayoutParams(dp(21),dp(21)));
            TextView label=text(term,12,true);label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(label,new LinearLayout.LayoutParams(0,-2,1));
            TextView del=text("×",22,true);del.setTextColor(palette.muted);
            del.setGravity(Gravity.CENTER);del.setContentDescription("Удалить сохранённый запрос "+term);
            del.setOnClickListener(v->{
                JSONArray updated=new JSONArray(),before=saved();
                for(int j=0;j<before.length();j++)if(j!=idx)updated.put(before.optJSONObject(j));
                prefs.edit().putString(SAVED_KEY,updated.toString()).apply();drawSaved();
            });
            row.addView(del,new LinearLayout.LayoutParams(dp(32),dp(43)));
            row.setOnClickListener(v->{
                SearchMode savedMode=SearchMode.REPOSITORIES;
                try{savedMode=SearchMode.valueOf(item.optString("mode","REPOSITORIES"));}
                catch(Exception ignored){}
                actions.searchAdvanced(term,item.optBoolean("smart",false),savedMode,new SearchFilters());
            });
            group.addView(row,new LinearLayout.LayoutParams(-1,dp(48)));
        }
        savedBlock.addView(group);
    }

    private void renderModeSelector() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setContentDescription("Тип поиска");
        for (SearchMode item : SearchMode.values()) {
            TextView chip = text(shortTitle(item), 11, true);
            chip.setGravity(Gravity.CENTER);
            chip.setMinHeight(dp(44));
            chip.setOnClickListener(v -> setMode(item));
            modeViews.put(item, chip);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(44), 1f);
            p.setMargins(item == SearchMode.REPOSITORIES ? 0 : dp(3), 0, item == SearchMode.PEOPLE ? 0 : dp(3), 0);
            row.addView(chip, p);
        }
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(44));
        p.setMargins(0, 0, 0, dp(8));
        content.addView(row, p);
    }

    private void renderFilterRow() {
        LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);
        TextView button=text("⚙ Фильтры",12,true);
        button.setGravity(Gravity.CENTER);button.setBackground(round(palette.card,12,palette.border,1));
        button.setOnClickListener(v->showFilterDialog());
        row.addView(button,new LinearLayout.LayoutParams(dp(102),dp(40)));
        filterSummary=text("",10,false);filterSummary.setTextColor(palette.muted);
        filterSummary.setSingleLine(true);filterSummary.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(filterSummary,new LinearLayout.LayoutParams(0,-2,1));
        smartToggle=text("",11,true);smartToggle.setGravity(Gravity.CENTER);
        smartToggle.setOnClickListener(v->setSmartEnabled(!smartEnabled));
        row.addView(smartToggle,new LinearLayout.LayoutParams(dp(101),dp(40)));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(42));
        p.setMargins(0,0,0,dp(8));content.addView(row,p);
    }

    private void submit() {
        String value = query.getText().toString().trim();
        if (!value.isEmpty()) actions.searchAdvanced(value, smartEnabled, mode, filters.copy());
    }

    private void setSmartEnabled(boolean enabled) {
        smartEnabled = enabled;
        prefs.edit().putBoolean(PREF_SMART_RU, enabled).apply();
        applySmartVisuals();
    }

    private void setMode(SearchMode next) {
        mode = next == null ? SearchMode.REPOSITORIES : next;
        prefs.edit().putString(PREF_MODE, mode.name()).apply();
        applyModeVisuals();
        applySmartVisuals();
    }

    private void applyModeVisuals() {
        for (Map.Entry<SearchMode,TextView> e : modeViews.entrySet()) {
            boolean active = e.getKey() == mode;
            TextView v=e.getValue();
            v.setTextColor(active ? Color.WHITE : palette.muted);
            v.setBackground(round(active ? palette.green : palette.card, 11, active ? palette.green : palette.border, 1));
            v.setContentDescription(e.getKey().title + (active ? ", выбран" : ""));
        }
        if (subtitle != null) subtitle.setText(subtitleFor(mode));
        if (filterSummary != null) filterSummary.setText("Фильтры: " + filters.summary(mode));
    }

    private void applySmartVisuals() {
        if (smartToggle == null || query == null) return;
        smartToggle.setText(smartEnabled ? "RU · Вкл ✓" : "RU · Выкл");
        if (smartEnabled) {
            smartToggle.setTextColor(Color.WHITE);
            smartToggle.setBackground(round(palette.green, 12, palette.green, 0));
            smartToggle.setContentDescription("Интеллектуальный русский поиск включён. Нажмите, чтобы выключить.");
        } else {
            smartToggle.setTextColor(palette.muted);
            smartToggle.setBackground(round(palette.card, 12, palette.border, 1));
            smartToggle.setContentDescription("Интеллектуальный русский поиск выключен. Нажмите, чтобы включить.");
        }
        query.setHint(hintFor(mode, smartEnabled));
    }

    private void showFilterDialog() {
        switch (mode) {
            case ISSUES_PRS: showIssueFilters(); break;
            case PEOPLE: showPeopleFilters(); break;
            case CODE: showRepoLikeFilters(true); break;
            case REPOSITORIES:
            default: showRepoLikeFilters(false); break;
        }
    }

    private void showRepoLikeFilters(boolean codeMode) {
        LinearLayout box = dialogBox();
        EditText language = field("Язык, например Kotlin", filters.language, InputType.TYPE_CLASS_TEXT); box.addView(language);
        EditText owner = field("Владелец: login, user:login или org:name", filters.ownerOrOrg, InputType.TYPE_CLASS_TEXT); box.addView(owner);
        EditText stars = null, updated = null, topic = null;
        if (!codeMode) {
            stars = field("Минимум звёзд, например 100", filters.minStars<=0?"":String.valueOf(filters.minStars), InputType.TYPE_CLASS_NUMBER); box.addView(stars);
            updated = field("Обновлён после: YYYY-MM-DD", filters.updatedAfter, InputType.TYPE_CLASS_DATETIME); box.addView(updated);
            topic = field("Тема, например android", filters.topic, InputType.TYPE_CLASS_TEXT); box.addView(topic);
        }
        final EditText fStars=stars,fUpdated=updated,fTopic=topic;
        new AlertDialog.Builder(activity).setTitle(codeMode?"Фильтры поиска кода":"Фильтры репозиториев").setView(box)
                .setPositiveButton("Применить", (d,w) -> {
                    filters.language=clean(language.getText().toString());
                    filters.ownerOrOrg=clean(owner.getText().toString());
                    if(!codeMode){filters.minStars=parseInt(fStars.getText().toString());filters.updatedAfter=clean(fUpdated.getText().toString());filters.topic=clean(fTopic.getText().toString());}
                    saveFilters();applyModeVisuals();
                })
                .setNeutralButton("Сбросить", (d,w) -> { if(codeMode){filters.language="";filters.ownerOrOrg="";}else resetRepoFilters();saveFilters();applyModeVisuals(); })
                .setNegativeButton("Отмена",null).show();
    }

    private void showIssueFilters() {
        LinearLayout box=dialogBox();
        Spinner kind=new Spinner(activity);kind.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,new String[]{"Все","Только Issues","Только Pull Requests"}));kind.setSelection("issue".equals(filters.issueKind)?1:"pr".equals(filters.issueKind)?2:0);box.addView(kind,new LinearLayout.LayoutParams(-1,dp(52)));
        Spinner state=new Spinner(activity);state.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,new String[]{"Любое состояние","Открытые","Закрытые"}));state.setSelection("open".equals(filters.issueState)?1:"closed".equals(filters.issueState)?2:0);box.addView(state,new LinearLayout.LayoutParams(-1,dp(52)));
        new AlertDialog.Builder(activity).setTitle("Фильтры Issues / PR").setView(box).setPositiveButton("Применить",(d,w)->{
            filters.issueKind=kind.getSelectedItemPosition()==1?"issue":kind.getSelectedItemPosition()==2?"pr":"all";
            filters.issueState=state.getSelectedItemPosition()==1?"open":state.getSelectedItemPosition()==2?"closed":"all";
            saveFilters();applyModeVisuals();
        }).setNeutralButton("Сбросить",(d,w)->{filters.issueKind="all";filters.issueState="all";saveFilters();applyModeVisuals();}).setNegativeButton("Отмена",null).show();
    }

    private void showPeopleFilters() {
        LinearLayout box=dialogBox();
        Spinner kind=new Spinner(activity);kind.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,new String[]{"Пользователи и организации","Только пользователи","Только организации"}));kind.setSelection("user".equals(filters.peopleKind)?1:"org".equals(filters.peopleKind)?2:0);box.addView(kind,new LinearLayout.LayoutParams(-1,dp(52)));
        EditText owner=field("Дополнительно: точный login",filters.ownerOrOrg,InputType.TYPE_CLASS_TEXT);box.addView(owner);
        new AlertDialog.Builder(activity).setTitle("Фильтры людей и организаций").setView(box).setPositiveButton("Применить",(d,w)->{
            filters.peopleKind=kind.getSelectedItemPosition()==1?"user":kind.getSelectedItemPosition()==2?"org":"all";
            filters.ownerOrOrg=clean(owner.getText().toString());saveFilters();applyModeVisuals();
        }).setNeutralButton("Сбросить",(d,w)->{filters.peopleKind="all";filters.ownerOrOrg="";saveFilters();applyModeVisuals();}).setNegativeButton("Отмена",null).show();
    }

    private LinearLayout dialogBox(){LinearLayout b=new LinearLayout(activity);b.setOrientation(LinearLayout.VERTICAL);b.setPadding(dp(18),dp(6),dp(18),dp(4));return b;}
    private EditText field(String hint,String value,int type){EditText e=new EditText(activity);e.setHint(hint);e.setText(value==null?"":value);e.setSingleLine(true);e.setInputType(type);e.setTextSize(14);e.setPadding(dp(6),dp(4),dp(6),dp(4));e.setMinHeight(dp(50));return e;}

    private SearchFilters loadFilters(){
        SearchFilters f=new SearchFilters();
        f.language=prefs.getString(PREF_FILTER_PREFIX+"language","");f.minStars=prefs.getInt(PREF_FILTER_PREFIX+"stars",0);
        f.updatedAfter=prefs.getString(PREF_FILTER_PREFIX+"updated","");f.ownerOrOrg=prefs.getString(PREF_FILTER_PREFIX+"owner","");
        f.topic=prefs.getString(PREF_FILTER_PREFIX+"topic","");f.issueState=prefs.getString(PREF_FILTER_PREFIX+"issue_state","all");
        f.issueKind=prefs.getString(PREF_FILTER_PREFIX+"issue_kind","all");f.peopleKind=prefs.getString(PREF_FILTER_PREFIX+"people_kind","all");
        return f;
    }
    private void saveFilters(){prefs.edit().putString(PREF_FILTER_PREFIX+"language",filters.language).putInt(PREF_FILTER_PREFIX+"stars",filters.minStars).putString(PREF_FILTER_PREFIX+"updated",filters.updatedAfter).putString(PREF_FILTER_PREFIX+"owner",filters.ownerOrOrg).putString(PREF_FILTER_PREFIX+"topic",filters.topic).putString(PREF_FILTER_PREFIX+"issue_state",filters.issueState).putString(PREF_FILTER_PREFIX+"issue_kind",filters.issueKind).putString(PREF_FILTER_PREFIX+"people_kind",filters.peopleKind).apply();}
    private void resetRepoFilters(){filters.language="";filters.minStars=0;filters.updatedAfter="";filters.ownerOrOrg="";filters.topic="";}

    private void renderRecentSearches() {
        List<SmartSearchHistory.Entry> recent = history.recent();
        if (recent.isEmpty()) return;
        LinearLayout block = new LinearLayout(activity); block.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(activity); head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text("Недавние запросы", 17, true), new LinearLayout.LayoutParams(0, dp(44), 1));
        TextView clear = text("Очистить", 12, true); clear.setTextColor(palette.green); clear.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        clear.setOnClickListener(v -> { history.clear(); block.setVisibility(View.GONE); }); head.addView(clear, new LinearLayout.LayoutParams(dp(82), dp(44))); block.addView(head);
        int count = Math.min(3, recent.size());
        for (int i=0;i<count;i++) {
            SmartSearchHistory.Entry entry=recent.get(i);LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(12),dp(6),dp(10),dp(6));row.setBackground(round(palette.card,14,palette.border,1));
            TextView label=text(entry.query,13,false);label.setMaxLines(1);label.setEllipsize(android.text.TextUtils.TruncateAt.END);row.addView(label,new LinearLayout.LayoutParams(0,dp(40),1));
            if(entry.smart){TextView badge=text("RU ✦",10,true);badge.setTextColor(palette.green);badge.setGravity(Gravity.CENTER);row.addView(badge,new LinearLayout.LayoutParams(dp(48),dp(40)));}
            row.setOnClickListener(v->actions.search(entry.query,entry.smart));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(48));p.setMargins(0,0,0,dp(6));block.addView(row,p);
        }
        content.addView(block);
    }

    private void addDirection(String title,String subtitle,String q,boolean smart){
        LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(14),dp(10),dp(12),dp(10));row.setBackground(round(palette.card,16,palette.border,1));
        TextView initial=text(title.substring(0,1).toUpperCase(Locale.ROOT),17,true);initial.setGravity(Gravity.CENTER);initial.setTextColor(palette.green);initial.setBackground(round(palette.subtle,14,palette.border,1));row.addView(initial,new LinearLayout.LayoutParams(dp(48),dp(48)));
        LinearLayout labels=new LinearLayout(activity);labels.setOrientation(LinearLayout.VERTICAL);labels.setPadding(dp(12),0,dp(6),0);TextView heading=text(title,15,true);heading.setPadding(0,0,0,0);TextView detail=text(subtitle,12,false);detail.setTextColor(palette.muted);detail.setPadding(0,dp(2),0,0);labels.addView(heading);labels.addView(detail);row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        TextView arrow=text("›",28,false);arrow.setTextColor(palette.muted);arrow.setGravity(Gravity.CENTER);row.addView(arrow,new LinearLayout.LayoutParams(dp(24),dp(48)));row.setOnClickListener(v->actions.searchAdvanced(q,smart,SearchMode.REPOSITORIES,new SearchFilters()));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(72));p.setMargins(0,0,0,dp(7));content.addView(row,p);
    }

    private String shortTitle(SearchMode m){switch(m){case ISSUES_PRS:return "Issues/PR";case CODE:return "Код";case PEOPLE:return "Люди";default:return "Репо";}}
    private String subtitleFor(SearchMode m){switch(m){case ISSUES_PRS:return "Задачи и Pull Requests по смыслу";case CODE:return "Файлы, классы и фрагменты кода";case PEOPLE:return "Пользователи и организации GitHub";default:return "Репозитории, темы и технологии";}}
    private String hintFor(SearchMode m,boolean smart){switch(m){case ISSUES_PRS:return smart?"Например: ошибка авторизации Android":"Например: oauth is:open";case CODE:return smart?"Например: обработка oauth токена":"Например: OAuthManager language:Java";case PEOPLE:return smart?"Например: разработчик android":"Например: torvalds";default:return smart?"Например: клиент Telegram для Android":"Например: kotlin android";}}
    private int parseInt(String v){try{return Math.max(0,Integer.parseInt(clean(v)));}catch(Exception e){return 0;}}
    private String clean(String v){return v==null?"":v.trim().replaceAll("\\s+"," ");}

    private TextView text(String value,int sp,boolean bold){TextView view=new TextView(activity);view.setText(value);view.setTextSize(sp);view.setTextColor(palette.text);if(bold)view.setTypeface(null,android.graphics.Typeface.BOLD);view.setPadding(dp(4),dp(4),dp(4),dp(4));view.setLineSpacing(0f,activity.getResources().getConfiguration().fontScale>=1.25f?1.18f:1.08f);view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);return view;}
    private GradientDrawable round(int fill,int radius,int stroke,int width){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));if(width>0)d.setStroke(dp(width),stroke);return d;}
    private int dp(int value){return(int)(value*activity.getResources().getDisplayMetrics().density+0.5f);}
}
