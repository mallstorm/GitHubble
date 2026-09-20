package app.githubble.mobile.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import app.githubble.mobile.R;

/** The approved GitHubble home screen: native Android Views, shared palette and real account data. */
public final class HomeScreen {
    public interface Actions {
        void openIssues();
        void openPullRequests();
        void openDiscussions();
        void openRepositories();
        void openOrganizations();
        void openProjects();
        void openReleases();
        void openFavorites();
        void openHistory();
        void openTracking();
        void openProfile();
        void openAccountCenter();
        void openRepository(String fullName);
        void openActivity();
        void openExplore();
        void openActivityItem(String repo, String kind, int number);
        void refreshDashboard();
    }

    public static final class RepoPreview {
        public final String fullName;
        public final String description;
        public final int stars;
        public final String updated;
        public RepoPreview(String fullName, String description, int stars, String updated) {
            this.fullName = fullName;
            this.description = description;
            this.stars = stars;
            this.updated = updated;
        }
    }

    public static final class ActivityPreview {
        public final String repo;
        public final String title;
        public final String updated;
        public final String kind;
        public final int number;
        public ActivityPreview(String repo, String title, String updated, String kind, int number) {
            this.repo = repo == null ? "" : repo;
            this.title = title == null ? "" : title;
            this.updated = updated == null ? "" : updated;
            this.kind = kind == null ? "" : kind;
            this.number = number;
        }
    }

    public static final class DashboardData {
        public final int issues;
        public final int pullRequests;
        public final int repositories;
        public final int notifications;
        public final int trackedDevelopers;
        public final int trackedRepositories;
        public final int trackingUnread;
        public final List<RepoPreview> recentRepositories;
        public final List<ActivityPreview> recentActivity;
        public final String login;
        public final long syncedAt;
        public DashboardData(int issues, int pullRequests, int repositories, int notifications,
                             int trackedDevelopers, int trackedRepositories, int trackingUnread,
                             List<RepoPreview> recentRepositories, List<ActivityPreview> recentActivity,
                             String login, long syncedAt) {
            this.issues = issues;
            this.pullRequests = pullRequests;
            this.repositories = repositories;
            this.notifications = notifications;
            this.trackedDevelopers = trackedDevelopers;
            this.trackedRepositories = trackedRepositories;
            this.trackingUnread = trackingUnread;
            this.recentRepositories = recentRepositories == null ? Collections.emptyList() : recentRepositories;
            this.recentActivity = recentActivity == null ? Collections.emptyList() : recentActivity;
            this.login = login == null ? "" : login;
            this.syncedAt = syncedAt;
        }
    }

    public static final class Palette {
        public final int background, card, subtle, text, muted, green, border;
        public final int[] tileColors;
        public final boolean dark;
        public Palette(int background, int card, int subtle, int text, int muted, int green,
                       int border, int[] tileColors, boolean dark) {
            this.background = background;
            this.card = card;
            this.subtle = subtle;
            this.text = text;
            this.muted = muted;
            this.green = green;
            this.border = border;
            this.tileColors = tileColors;
            this.dark = dark;
        }
    }

    private final Activity activity;
    private final LinearLayout content;
    private final Palette palette;
    private final boolean signedIn;
    private final Actions actions;
    private final DashboardData data;
    private final int blue = Color.rgb(44, 125, 225);
    private final int red = Color.rgb(219, 65, 88);
    private final int violet = Color.rgb(129, 72, 203);
    private final int teal = Color.rgb(33, 149, 157);
    private final int amber = Color.rgb(221, 159, 22);
    private final int orange = Color.rgb(235, 127, 27);
    private final int slate = Color.rgb(88, 106, 112);

    public HomeScreen(Activity activity, LinearLayout content, Palette palette, boolean signedIn,
                      String buildLabel, Actions actions, DashboardData data) {
        this.activity = activity;
        this.content = content;
        this.palette = palette;
        this.signedIn = signedIn;
        this.actions = actions;
        this.data = data == null
                ? new DashboardData(-1,-1,-1,-1,0,0,0,Collections.emptyList(),
                                    Collections.emptyList(),"",0) : data;
    }

    public void render() {
        renderHero();
        renderStats();
        section("Быстрый доступ", "Все разделы", actions::openRepositories);
        renderQuickAccess();
        section("Недавняя активность", "Все события", actions::openActivity);
        renderActivity();
        section("Рекомендуем для вас", "Больше", actions::openExplore);
        renderRecommendations();
    }

    private void renderHero() {
        FrameLayout hero = new FrameLayout(activity);
        hero.setBackground(round(palette.dark ? 0xFF263A37 : 0xFF173C34, 17, palette.border, 0));
        hero.setClipToOutline(true);

        ImageView landscape = new ImageView(activity);
        landscape.setImageResource(R.drawable.home_hero);
        landscape.setScaleType(ImageView.ScaleType.CENTER_CROP);
        hero.addView(landscape, new FrameLayout.LayoutParams(-1,-1));

        View scrim = new View(activity);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xC70A2328,0x8A122B31,0x39102429}));
        hero.addView(scrim, new FrameLayout.LayoutParams(-1,-1));

        TextView greeting = text((daypart() + (signedIn && !data.login.isEmpty()
                ? ",\n" + data.login + "!" : "!")), 21, true);
        greeting.setTextColor(Color.WHITE);
        greeting.setMaxLines(3);
        greeting.setPadding(0,0,0,0);
        FrameLayout.LayoutParams greetingP = new FrameLayout.LayoutParams(
                -1, -2, Gravity.START | Gravity.TOP);
        greetingP.setMargins(dp(16),dp(24),dp(155),0);
        hero.addView(greeting,greetingP);

        TextView subtitle = text("Развивайте свои идеи.\nКод. Проекты. Сообщество.", 11, false);
        subtitle.setTextColor(Color.WHITE);
        subtitle.setPadding(0,0,0,0);
        subtitle.setMaxLines(2);
        FrameLayout.LayoutParams subtitleP = new FrameLayout.LayoutParams(
                -1, -2, Gravity.START | Gravity.BOTTOM);
        subtitleP.setMargins(dp(16),0,dp(154),dp(13));
        hero.addView(subtitle,subtitleP);

        TextView status = text(syncText(), 10, true);
        status.setTextColor(0xFF176C39);
        status.setPadding(dp(8),dp(5),dp(8),dp(5));
        status.setGravity(Gravity.CENTER);
        status.setBackground(round(0xF4FFFFFF,13,0,0));
        FrameLayout.LayoutParams statusP = new FrameLayout.LayoutParams(
                -2, dp(29), Gravity.END | Gravity.TOP);
        statusP.setMargins(0,dp(9),dp(10),0);
        hero.addView(status,statusP);
        status.setOnClickListener(v -> { if (!signedIn) actions.openProfile(); else actions.refreshDashboard(); });

        // A short quote on the right, like the approved reference.
        TextView quote = text("«Большие проекты\nначинаются с одной идеи»", 9, false);
        quote.setTextColor(Color.WHITE);
        quote.setPadding(dp(7),dp(6),dp(7),dp(6));
        quote.setGravity(Gravity.CENTER_VERTICAL);
        quote.setBackground(round(0xAA253B3B,10,0x44FFFFFF,1));
        FrameLayout.LayoutParams quoteP = new FrameLayout.LayoutParams(
                dp(142), -2, Gravity.END | Gravity.BOTTOM);
        quoteP.setMargins(0,0,dp(10),dp(11));
        hero.addView(quote,quoteP);

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,dp(110));
        p.setMargins(0,0,0,dp(7));
        content.addView(hero,p);
    }

    private String daypart() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (h < 5) return "Доброй ночи";
        if (h < 12) return "Доброе утро";
        if (h < 18) return "Добрый день";
        return "Добрый вечер";
    }

    private String syncText() {
        if (!signedIn) return "Войти в GitHub ›";
        if (data.syncedAt == 0L) return "◌ Обновление";
        if (System.currentTimeMillis() - data.syncedAt < 15L * 60L * 1000L)
            return "✓ Синхронизировано ›";
        return "↻ Обновить ›";
    }

    private void renderStats() {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER);
        row.setPadding(dp(3),dp(4),dp(3),dp(4));
        row.setBackground(round(blend(palette.card,palette.green, palette.dark?0.09f:0.025f),
                17,palette.border,1));
        row.addView(stat(R.drawable.ic_issue,data.issues,"Задач",palette.green,actions::openIssues),
                new LinearLayout.LayoutParams(0,-1,1));
        divider(row);
        row.addView(stat(R.drawable.ic_pull_request,data.pullRequests,"PR",blue,actions::openPullRequests),
                new LinearLayout.LayoutParams(0,-1,1));
        divider(row);
        row.addView(stat(R.drawable.ic_repository,data.repositories,"Репозитории",red,actions::openRepositories),
                new LinearLayout.LayoutParams(0,-1,1));
        divider(row);
        row.addView(stat(R.drawable.ic_bell,data.notifications,"Уведомления",palette.green,actions::openActivity),
                new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,dp(75));
        p.setMargins(0,0,0,dp(5));
        content.addView(row,p);
    }

    private View stat(int icon, int count, String label, int accent, Runnable click) {
        LinearLayout cell = new LinearLayout(activity);
        cell.setGravity(Gravity.CENTER);
        cell.setOrientation(LinearLayout.VERTICAL);
        ImageView image = imageIcon(icon,accent);
        cell.addView(image,new LinearLayout.LayoutParams(dp(21),dp(21)));
        TextView number = text(count < 0 ? "…" : Integer.toString(count),15,true);
        number.setTextColor(accent);
        number.setGravity(Gravity.CENTER);
        number.setPadding(0,dp(1),0,0);
        cell.addView(number);
        TextView caption = text(label,9,false);
        caption.setGravity(Gravity.CENTER);
        caption.setTextColor(palette.muted);
        caption.setPadding(0,0,0,0);
        caption.setSingleLine();
        caption.setEllipsize(TextUtils.TruncateAt.END);
        cell.addView(caption,new LinearLayout.LayoutParams(-1,-2));
        cell.setOnClickListener(v->click.run());
        return cell;
    }

    private void divider(LinearLayout row) {
        View line = new View(activity);
        line.setBackgroundColor(palette.border);
        row.addView(line,new LinearLayout.LayoutParams(dp(1),dp(36)));
    }

    private void renderQuickAccess() {
        LinearLayout groups = new LinearLayout(activity);
        groups.setGravity(Gravity.TOP);
        addGroup(groups,"Разработка и код",
                new Tile[]{
                    new Tile("Репозитории","Мои проекты",R.drawable.tile_repository,palette.green,actions::openRepositories),
                    new Tile("Pull Requests","Проверка кода",R.drawable.tile_pull_request,blue,actions::openPullRequests),
                    new Tile("Релизы","Версии и сборки",R.drawable.tile_release,orange,actions::openReleases),
                    new Tile("Проекты","Планирование",R.drawable.tile_projects,violet,actions::openProjects)
                },palette.green,true);
        addGroup(groups,"Сообщество и управление",
                new Tile[]{
                    new Tile("Issues","Задачи и баги",R.drawable.tile_issue,red,actions::openIssues),
                    new Tile("Обсуждения","Идеи и общение",R.drawable.tile_discussion,violet,actions::openDiscussions),
                    new Tile("Организации","Команды и доступ",R.drawable.tile_organization,teal,actions::openOrganizations),
                    new Tile("Избранное","Сохранённые",R.drawable.tile_star,amber,actions::openFavorites)
                },blue,false);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,0,0,dp(5));
        content.addView(groups,p);
    }

    private static final class Tile {
        final String title,subtitle;
        final int icon,accent;
        final Runnable action;
        Tile(String title,String subtitle,int icon,int accent,Runnable action) {
            this.title=title;this.subtitle=subtitle;this.icon=icon;this.accent=accent;this.action=action;
        }
    }

    private void addGroup(LinearLayout target,String name,Tile[] tiles,int accent,boolean first) {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(5),dp(7),dp(5),dp(5));
        panel.setBackground(round(blend(palette.card,accent,palette.dark?0.12f:0.055f),
                13,blend(palette.border,accent,0.13f),1));
        TextView heading = text(name,10,true);
        heading.setTextColor(palette.dark?palette.text:blend(accent,Color.BLACK,0.24f));
        heading.setPadding(dp(3),0,0,dp(5));
        heading.setMaxLines(1);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        panel.addView(heading);
        GridLayout grid = new GridLayout(activity);
        grid.setColumnCount(2);
        grid.setUseDefaultMargins(false);
        for (Tile tile:tiles) renderTile(grid,tile);
        panel.addView(grid,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,-2,1);
        if(first)p.setMargins(0,0,dp(3),0);else p.setMargins(dp(3),0,0,0);
        target.addView(panel,p);
    }

    private void renderTile(GridLayout grid, Tile item) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.TOP);
        box.setPadding(dp(5),dp(5),dp(4),dp(3));
        box.setBackground(round(palette.card,10,palette.border,1));
        ImageView icon = new ImageView(activity);
        icon.setImageResource(item.icon);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        box.addView(icon,new LinearLayout.LayoutParams(dp(31),dp(31)));
        TextView title = text(item.title,10,true);
        title.setPadding(0,dp(2),0,0);
        title.setSingleLine();
        title.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(title,new LinearLayout.LayoutParams(-1,-2));
        TextView subtitle = text(item.subtitle,8,false);
        subtitle.setTextColor(palette.muted);
        subtitle.setPadding(0,0,0,0);
        subtitle.setSingleLine();
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(subtitle,new LinearLayout.LayoutParams(-1,-2));
        box.setOnClickListener(v -> item.action.run());
        GridLayout.LayoutParams p = new GridLayout.LayoutParams();
        p.width=0;p.height=dp(72);
        p.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);
        p.setMargins(dp(2),dp(2),dp(2),dp(2));
        grid.addView(box,p);
    }

    private void renderActivity() {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(palette.card,16,palette.border,1));
        card.setPadding(dp(9),dp(3),dp(9),dp(3));
        if (!signedIn) {
            emptyRow(card,"Подключите GitHub, чтобы видеть события вашего аккаунта.");
        } else if (data.recentActivity.isEmpty()) {
            emptyRow(card, "События появятся после синхронизации с GitHub.");
        } else {
            for(int i=0;i<Math.min(3,data.recentActivity.size());i++) {
                if(i!=0){View divider=new View(activity);divider.setBackgroundColor(palette.border);
                    card.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));}
                renderActivityItem(card,data.recentActivity.get(i));
            }
        }
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,0,0,dp(4));
        content.addView(card,p);
    }

    private void emptyRow(LinearLayout list,String message) {
        TextView hint = text(message,12,false);
        hint.setTextColor(palette.muted);
        hint.setGravity(Gravity.CENTER_VERTICAL);
        list.addView(hint,new LinearLayout.LayoutParams(-1,dp(62)));
        list.setOnClickListener(v->{if(signedIn) actions.refreshDashboard(); else actions.openProfile();});
    }

    private void renderActivityItem(LinearLayout list,ActivityPreview event) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int accent = "Issue".equals(event.kind)?red:"PullRequest".equals(event.kind)?blue:palette.green;
        int iconRes = "Issue".equals(event.kind)?R.drawable.tile_issue
                :"PullRequest".equals(event.kind)?R.drawable.tile_pull_request:R.drawable.tile_repository;
        ImageView icon = new ImageView(activity);
        icon.setImageResource(iconRes);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        row.addView(icon,new LinearLayout.LayoutParams(dp(33),dp(33)));
        LinearLayout info = new LinearLayout(activity);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(7),0,dp(3),0);
        TextView repo = text(event.repo.isEmpty()?"GitHub":event.repo,11,true);
        repo.setPadding(0,0,0,0);repo.setSingleLine();repo.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(repo);
        TextView title=text(event.title,10,false);
        title.setTextColor(palette.muted);
        title.setPadding(0,0,0,0);
        title.setSingleLine();title.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(title);
        TextView age=text(event.updated,9,false);
        age.setTextColor(palette.muted);age.setPadding(0,0,0,0);
        age.setSingleLine();info.addView(age);
        row.addView(info,new LinearLayout.LayoutParams(0,-2,1));
        TextView arrow=text("›",22,false);
        arrow.setGravity(Gravity.CENTER);
        arrow.setTextColor(palette.muted);
        row.addView(arrow,new LinearLayout.LayoutParams(dp(17),dp(42)));
        row.setOnClickListener(v->actions.openActivityItem(event.repo,event.kind,event.number));
        list.addView(row,new LinearLayout.LayoutParams(-1,dp(49)));
    }

    private void renderRecommendations() {
        LinearLayout row = new LinearLayout(activity);
        recommendation(row,"android","Android-проекты",R.drawable.ic_android,palette.green,
                "android/architecture-samples",true);
        recommendation(row,"kotlin","Язык Kotlin",R.drawable.ic_kotlin,violet,
                "JetBrains/kotlin",false);
        recommendation(row,"github","Open Source",R.drawable.ic_repository,slate,
                "github/gitignore",false);
        content.addView(row,new LinearLayout.LayoutParams(-1,dp(74)));
    }

    private void recommendation(LinearLayout row,String title,String subtitle,int icon,int accent,
                                String repo,boolean first) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(6),dp(7),dp(5),dp(5));
        card.setBackground(round(palette.card,12,palette.border,1));
        LinearLayout top = new LinearLayout(activity);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView art=imageIcon(icon,accent);
        art.setPadding(dp(6),dp(6),dp(6),dp(6));
        art.setBackground(round(blend(palette.card,accent,0.12f),9,0,0));
        top.addView(art,new LinearLayout.LayoutParams(dp(26),dp(26)));
        LinearLayout names = new LinearLayout(activity);
        names.setPadding(dp(4),0,0,0);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView name=text(title,10,true);
        name.setPadding(0,0,0,0);name.setSingleLine();name.setEllipsize(TextUtils.TruncateAt.END);
        TextView desc=text(subtitle,8,false);
        desc.setTextColor(palette.muted);desc.setPadding(0,0,0,0);
        desc.setSingleLine();desc.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(name);names.addView(desc);
        top.addView(names,new LinearLayout.LayoutParams(0,-2,1));
        card.addView(top);
        TextView button=text("Открыть ›",9,true);
        button.setTextColor(palette.green);
        button.setGravity(Gravity.END);
        button.setPadding(0,dp(7),dp(2),0);
        card.addView(button);
        card.setOnClickListener(v->actions.openRepository(repo));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,-1,1);
        p.setMargins(first?0:dp(3),0,dp(3),0);
        row.addView(card,p);
    }

    private void section(String title,String action,Runnable open) {
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView text=text(title,16,true);
        text.setSingleLine();text.setEllipsize(TextUtils.TruncateAt.END);
        text.setPadding(0,0,0,0);
        header.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        TextView link=text(action+" ›",11,true);
        link.setTextColor(palette.green);
        link.setGravity(Gravity.CENTER_VERTICAL);
        link.setPadding(dp(5),0,0,0);
        link.setOnClickListener(v->open.run());
        header.addView(link,new LinearLayout.LayoutParams(-2,dp(34)));
        content.addView(header,new LinearLayout.LayoutParams(-1,dp(34)));
    }

    private TextView text(String value,int size,boolean bold) {
        TextView v=new TextView(activity);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(palette.text);
        if(bold)v.setTypeface(null,android.graphics.Typeface.BOLD);
        v.setIncludeFontPadding(false);
        return v;
    }

    private ImageView imageIcon(int drawable,int tint) {
        ImageView image=new ImageView(activity);
        image.setImageResource(drawable);
        image.setColorFilter(tint);
        image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        return image;
    }

    private GradientDrawable round(int fill,int radius,int stroke,int strokeWidth) {
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        if(strokeWidth>0)shape.setStroke(dp(strokeWidth),stroke);
        return shape;
    }

    private int blend(int base,int accent,float weight) {
        float w=Math.max(0f,Math.min(1f,weight));
        return Color.rgb((int)(Color.red(base)*(1-w)+Color.red(accent)*w),
                         (int)(Color.green(base)*(1-w)+Color.green(accent)*w),
                         (int)(Color.blue(base)*(1-w)+Color.blue(accent)*w));
    }

    private int dp(int n) {
        return (int)(n*activity.getResources().getDisplayMetrics().density+0.5f);
    }
}
