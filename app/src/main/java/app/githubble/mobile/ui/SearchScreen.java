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
        void searchFileNames(String query);
        default void searchAdvanced(String query, boolean intelligentRussian, SearchMode mode, SearchFilters filters) {
            search(query, intelligentRussian);
        }
        /** The connected model turns a free-form request into a GitHub query. */
        default void aiSearch(String query, SearchMode mode, SearchFilters filters) {
            searchAdvanced(query, true, mode, filters);
        }
    }

    public static final String PREF_SMART_RU = "smart_search_ru_enabled";
    public static final String PREF_AI = "search_ai_enabled";
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
    private TextView smartToggle, aiToggle, goButton;
    private boolean aiEnabled;
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
        this.aiEnabled = prefs.getBoolean(PREF_AI, false);
        this.filters = loadFilters();
    }

    public void render() {
        // Search card: gradient field with lens, clear and microphone.
        FrameLayout searchBox=new FrameLayout(activity);
        searchBox.setBackground(gradient(palette.green,false,20));
        ImageView lens=new ImageView(activity);lens.setImageResource(R.drawable.ic_search);
        lens.setColorFilter(palette.green);
        FrameLayout.LayoutParams lensP=new FrameLayout.LayoutParams(dp(22),dp(22),Gravity.START|Gravity.CENTER_VERTICAL);
        lensP.setMargins(dp(14),0,0,0);searchBox.addView(lens,lensP);
        query=new EditText(activity);query.setHintTextColor(palette.muted);
        query.setText(prefs.getString("search_draft_query",""));
        query.setTextColor(palette.text);query.setSingleLine(true);query.setTextSize(15);
        query.setPadding(dp(46),0,dp(96),0);query.setBackgroundColor(Color.TRANSPARENT);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setContentDescription("Поиск GitHub");
        searchBox.addView(query,new FrameLayout.LayoutParams(-1,-1));
        TextView clear=text("×",22,true);clear.setTextColor(palette.muted);clear.setGravity(Gravity.CENTER);
        clear.setContentDescription("Очистить запрос");
        clear.setOnClickListener(v->{query.setText("");query.requestFocus();});
        searchBox.addView(clear,new FrameLayout.LayoutParams(dp(40),dp(54),Gravity.END|Gravity.CENTER_VERTICAL));
        ((FrameLayout.LayoutParams)clear.getLayoutParams()).setMargins(0,0,dp(46),0);
        ImageView mic=new ImageView(activity);
        mic.setImageResource(R.drawable.ic_mic);
        mic.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        mic.setContentDescription("Начать голосовой поиск; долгое нажатие — выбор языка");
        mic.setPadding(dp(11),dp(11),dp(11),dp(11));
        mic.setOnClickListener(v->actions.dictate(query,mic));
        mic.setOnLongClickListener(v->{actions.chooseDictationLanguage();return true;});
        searchBox.addView(mic,new FrameLayout.LayoutParams(dp(52),dp(54),Gravity.END|Gravity.CENTER_VERTICAL));
        LinearLayout.LayoutParams qP=new LinearLayout.LayoutParams(-1,dp(56));
        qP.setMargins(0,dp(4),0,dp(10));content.addView(searchBox,qP);

        renderModeSelector();renderFilterRow();

        goButton=text("",15,true);goButton.setGravity(Gravity.CENTER);goButton.setTextColor(Color.WHITE);
        goButton.setBackground(gradient(palette.green,true,16));goButton.setOnClickListener(v->submit());
        LinearLayout.LayoutParams goP=new LinearLayout.LayoutParams(-1,dp(52));goP.setMargins(0,dp(2),0,dp(2));
        content.addView(goButton,goP);
        TextView fileNameSearch=text("⌕  Найти файл по имени в выбранном репозитории  ›",12,true);
        fileNameSearch.setTextColor(palette.green);
        fileNameSearch.setGravity(Gravity.CENTER_VERTICAL|Gravity.CENTER_HORIZONTAL);
        fileNameSearch.setMinHeight(dp(45));
        fileNameSearch.setContentDescription("Поиск файлов по имени без поиска содержимого кода");
        fileNameSearch.setOnClickListener(v->{
            String text=query.getText().toString().trim();
            prefs.edit().putString("search_draft_query",text).apply();
            actions.searchFileNames(text);
        });
        content.addView(fileNameSearch,new LinearLayout.LayoutParams(-1,dp(45)));
        query.setOnEditorActionListener((v,id,event)->{
            if(id==EditorInfo.IME_ACTION_SEARCH){submit();return true;}return false;
        });
        applyModeVisuals();applySmartVisuals();applyAiVisuals();

        renderRecentSearches();
        section("Быстрые фильтры","Все фильтры",this::showFilterDialog);
        quickChips();
        section("Популярные направления","Все",()->
            actions.searchAdvanced("stars:>1000",false,SearchMode.REPOSITORIES,new SearchFilters()));
        directionGrid();
        section("Сохранённые поиски","Сохранить текущий",this::saveQuery);
        savedBlock=new LinearLayout(activity);savedBlock.setOrientation(LinearLayout.VERTICAL);
        content.addView(savedBlock);drawSaved();
    }

    /** Gradient surface tinted with {@code accent}; filled when active. */
    private GradientDrawable gradient(int accent,boolean active,int radius){
        GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.TL_BR,active
                ?new int[]{LanguageColors.mix(accent,Color.WHITE,.12f),LanguageColors.mix(accent,Color.BLACK,.26f)}
                :new int[]{LanguageColors.mix(palette.card,accent,.16f),LanguageColors.mix(palette.card,accent,.04f),palette.card});
        g.setCornerRadius(dp(DesignSystem.radius(radius)));
        g.setStroke(dp(1),active?LanguageColors.mix(accent,Color.WHITE,.2f):LanguageColors.mix(palette.border,accent,.30f));
        return g;
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
        p.setMargins(0,dp(14),0,dp(4));content.addView(line,p);
    }

    /** One horizontally scrolling row of tinted chips. */
    private void chipRow(List<View> chips){
        HorizontalScrollView scroll=new HorizontalScrollView(activity);scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);
        for(View chip:chips){
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(40));p.setMargins(0,0,dp(8),0);row.addView(chip,p);
        }
        scroll.addView(row);
        content.addView(scroll,new LinearLayout.LayoutParams(-1,dp(44)));
    }

    private TextView tintChip(String label,int accent,Runnable action){
        TextView chip=text(label,12,true);chip.setGravity(Gravity.CENTER);chip.setSingleLine(true);
        chip.setPadding(dp(14),0,dp(14),0);chip.setBackground(gradient(accent,false,16));
        chip.setOnClickListener(v->action.run());return chip;
    }

    private void quickChips(){
        String[][] items={{"Android","topic:android","3DDC84"},{"Kotlin","language:kotlin","7755CC"},
                {"Python","language:python stars:>500","4E9B70"},{"Rust","language:rust stars:>500","B5654A"},
                {"Go","language:go stars:>500","159E97"},{"TypeScript","language:typescript stars:>500","3178C6"},
                {"Приватные","is:private",null},{"За 30 дней","pushed:>="+LocalDate.now().minusDays(30),null},
                {"★ 1000+","stars:>1000",null},{"Без форков","fork:false stars:>100",null}};
        List<View> chips=new ArrayList<>();
        for(String[] item:items){
            final String term=item[1];
            int accent=item[2]==null?palette.green:0xFF000000|Integer.parseInt(item[2],16);
            chips.add(tintChip(item[0],accent,()->actions.searchAdvanced(term,false,SearchMode.REPOSITORIES,new SearchFilters())));
        }
        chipRow(chips);
    }

    private void directionGrid(){
        Object[][] items={
                {"Android","Приложения и библиотеки",R.drawable.ic_android,"topic:android stars:>200",0x3DDC84},
                {"Kotlin","JVM и Android",R.drawable.ic_kotlin_brand,"language:kotlin stars:>200",0x7755CC},
                {"ИИ и LLM","Модели, агенты, RAG",R.drawable.ic_ai,"topic:llm stars:>300",0x9B5DE5},
                {"Open Source","Популярные проекты",R.drawable.ic_star,"stars:>10000",0xF2B705},
                {"Безопасность","Аудит и инструменты",R.drawable.ic_lock,"topic:security stars:>500",0xE5484D},
                {"Telegram-боты","Клиенты и боты",R.drawable.ic_discussion,"topic:telegram-bot stars:>100",0x2AABEE},
                {"Игры","Движки и open-source игры",R.drawable.ic_explore,"topic:game stars:>500",0xE8683F},
                {"Новые проекты","За последние 30 дней",R.drawable.ic_history,"created:>="+LocalDate.now().minusDays(30)+" stars:>20",0x4C7BBB}};
        for(int i=0;i<items.length;i+=2){
            LinearLayout row=new LinearLayout(activity);
            for(int k=0;k<2;k++){
                Object[] it=items[i+k];
                int accent=0xFF000000|(Integer)it[4];
                LinearLayout card=new LinearLayout(activity);card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(12),dp(11),dp(10),dp(10));card.setBackground(gradient(accent,false,18));
                ImageView badge=new ImageView(activity);badge.setImageResource((Integer)it[2]);
                if((Integer)it[2]!=R.drawable.ic_kotlin_brand)badge.setColorFilter(Color.WHITE);
                int pad=dp(7);badge.setPadding(pad,pad,pad,pad);
                GradientDrawable badgeBg=new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                        new int[]{LanguageColors.mix(accent,Color.WHITE,.12f),LanguageColors.mix(accent,Color.BLACK,.28f)});
                badgeBg.setCornerRadius(dp(11));badge.setBackground(badgeBg);
                card.addView(badge,new LinearLayout.LayoutParams(dp(34),dp(34)));
                TextView name=text((String)it[0],14,true);name.setPadding(0,dp(8),0,0);name.setSingleLine(true);card.addView(name);
                TextView sub=text((String)it[1],11,false);sub.setTextColor(palette.muted);sub.setPadding(0,0,0,0);
                sub.setSingleLine(true);sub.setEllipsize(TextUtils.TruncateAt.END);card.addView(sub);
                final String term=(String)it[3];
                card.setOnClickListener(v->actions.searchAdvanced(term,false,SearchMode.REPOSITORIES,new SearchFilters()));
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(96),1);
                p.setMargins(k==0?0:dp(5),0,k==0?dp(5):0,dp(10));row.addView(card,p);
            }
            content.addView(row,new LinearLayout.LayoutParams(-1,-2));
        }
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
            TextView chip = text(shortTitle(item), 12, true);
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setOnClickListener(v -> setMode(item));
            modeViews.put(item, chip);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(42), 1f);
            p.setMargins(item == SearchMode.REPOSITORIES ? 0 : dp(3), 0, item == SearchMode.PEOPLE ? 0 : dp(3), 0);
            row.addView(chip, p);
        }
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(42));
        p.setMargins(0, 0, 0, dp(8));
        content.addView(row, p);
    }

    private void renderFilterRow() {
        LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);
        TextView button=text("⚙ Фильтры",12,true);
        button.setGravity(Gravity.CENTER);button.setSingleLine(true);
        button.setBackground(gradient(palette.green,false,14));
        button.setOnClickListener(v->showFilterDialog());
        row.addView(button,new LinearLayout.LayoutParams(0,dp(42),1f));
        smartToggle=text("",12,true);smartToggle.setGravity(Gravity.CENTER);smartToggle.setSingleLine(true);
        smartToggle.setOnClickListener(v->setSmartEnabled(!smartEnabled));
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,dp(42),1f);sp.setMargins(dp(6),0,0,0);
        row.addView(smartToggle,sp);
        aiToggle=text("",12,true);aiToggle.setGravity(Gravity.CENTER);aiToggle.setSingleLine(true);
        aiToggle.setOnClickListener(v->setAiEnabled(!aiEnabled));
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(0,dp(42),1f);ap.setMargins(dp(6),0,0,0);
        row.addView(aiToggle,ap);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(42));
        p.setMargins(0,0,0,dp(4));content.addView(row,p);
        filterSummary=text("",11,false);filterSummary.setTextColor(palette.muted);
        filterSummary.setSingleLine(true);filterSummary.setEllipsize(TextUtils.TruncateAt.END);
        filterSummary.setPadding(dp(4),0,dp(4),0);
        content.addView(filterSummary,new LinearLayout.LayoutParams(-1,-2));
    }

    private void submit() {
        String value = query.getText().toString().trim();
        if (!value.isEmpty()) {
            prefs.edit().putString("search_draft_query",value).apply();
            if(aiEnabled)actions.aiSearch(value,mode,filters.copy());
            else actions.searchAdvanced(value,smartEnabled,mode,filters.copy());
        }
    }

    private void setSmartEnabled(boolean enabled) {
        smartEnabled = enabled;
        prefs.edit().putBoolean(PREF_SMART_RU, enabled).apply();
        applySmartVisuals();
    }

    private void setAiEnabled(boolean enabled) {
        aiEnabled = enabled;
        prefs.edit().putBoolean(PREF_AI, enabled).apply();
        applyAiVisuals();
    }

    private void applyAiVisuals() {
        if (aiToggle == null) return;
        aiToggle.setText(aiEnabled ? "✦ ИИ · Вкл" : "✦ ИИ");
        int accent = 0xFF9B5DE5;
        aiToggle.setTextColor(aiEnabled ? Color.WHITE : palette.text);
        aiToggle.setBackground(gradient(accent, aiEnabled, 14));
        aiToggle.setContentDescription(aiEnabled
                ? "ИИ-поиск включён: подключённая нейросеть сама составит запрос GitHub. Нажмите, чтобы выключить."
                : "ИИ-поиск выключен. Нажмите, чтобы нейросеть составляла запрос GitHub по описанию.");
        if (goButton != null) goButton.setText(aiEnabled ? "✦  Найти с ИИ" : "⌕  Найти на GitHub");
        if (query != null) query.setHint(hintFor(mode, smartEnabled || aiEnabled));
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
            v.setBackground(gradient(palette.green, active, 14));
            v.setContentDescription(e.getKey().title + (active ? ", выбран" : ""));
        }
        if (subtitle != null) subtitle.setText(subtitleFor(mode));
        if (filterSummary != null) {
            boolean any = !filters.isEmpty(mode);
            filterSummary.setVisibility(any ? View.VISIBLE : View.GONE);
            filterSummary.setText("Фильтры: " + filters.summary(mode));
        }
    }

    private void applySmartVisuals() {
        if (smartToggle == null || query == null) return;
        smartToggle.setText(smartEnabled ? "RU · Вкл ✓" : "RU · Выкл");
        if (smartEnabled) {
            smartToggle.setTextColor(Color.WHITE);
            smartToggle.setBackground(gradient(palette.green, true, 14));
            smartToggle.setContentDescription("Интеллектуальный русский поиск включён. Нажмите, чтобы выключить.");
        } else {
            smartToggle.setTextColor(palette.muted);
            smartToggle.setBackground(gradient(palette.green, false, 14));
            smartToggle.setContentDescription("Интеллектуальный русский поиск выключен. Нажмите, чтобы включить.");
        }
        query.setHint(hintFor(mode, smartEnabled || aiEnabled));
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
        LinearLayout head = new LinearLayout(activity); head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text("Недавние запросы", 17, true), new LinearLayout.LayoutParams(0, dp(40), 1));
        TextView clear = text("Очистить ›", 11, true); clear.setTextColor(palette.green); clear.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        head.addView(clear, new LinearLayout.LayoutParams(dp(90), dp(40)));
        content.addView(head);
        List<View> chips = new ArrayList<>();
        for (int i = 0; i < Math.min(8, recent.size()); i++) {
            SmartSearchHistory.Entry entry = recent.get(i);
            String label = entry.query.length() > 26 ? entry.query.substring(0, 25) + "…" : entry.query;
            chips.add(tintChip((entry.smart ? "RU ✦ " : "⏱ ") + label, palette.green, () -> actions.search(entry.query, entry.smart)));
        }
        LinearLayout holder = new LinearLayout(activity); holder.setOrientation(LinearLayout.VERTICAL);
        int before = content.getChildCount();
        chipRow(chips);
        View row = content.getChildAt(content.getChildCount() - 1);
        clear.setOnClickListener(v -> { history.clear(); row.setVisibility(View.GONE); head.setVisibility(View.GONE); });
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
    private GradientDrawable round(int fill,int radius,int stroke,int width){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(DesignSystem.radius(radius)));if(width>0)d.setStroke(dp(width),stroke);return d;}
    private int dp(int value){return(int)(value*activity.getResources().getDisplayMetrics().density+0.5f);}
}
