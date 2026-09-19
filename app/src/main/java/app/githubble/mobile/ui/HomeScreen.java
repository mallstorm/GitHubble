package app.githubble.mobile.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import app.githubble.mobile.R;

/**
 * Independent renderer for the GitHubble home dashboard.
 * Keeps the main Activity focused on navigation and screen orchestration.
 */
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
        void openRepository(String fullName);
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

    public static final class DashboardData {
        public final int issues;
        public final int pullRequests;
        public final int repositories;
        public final int notifications;
        public final int trackedDevelopers;
        public final int trackedRepositories;
        public final int trackingUnread;
        public final List<RepoPreview> recentRepositories;

        public DashboardData(int issues, int pullRequests, int repositories, int notifications,
                             int trackedDevelopers, int trackedRepositories, int trackingUnread,
                             List<RepoPreview> recentRepositories) {
            this.issues = issues;
            this.pullRequests = pullRequests;
            this.repositories = repositories;
            this.notifications = notifications;
            this.trackedDevelopers = trackedDevelopers;
            this.trackedRepositories = trackedRepositories;
            this.trackingUnread = trackingUnread;
            this.recentRepositories = recentRepositories == null
                    ? Collections.emptyList() : recentRepositories;
        }
    }

    public static final class Palette {
        public final int background;
        public final int card;
        public final int subtle;
        public final int text;
        public final int muted;
        public final int green;
        public final int border;
        public final int[] tileColors;
        public final boolean dark;

        public Palette(int background, int card, int subtle, int text, int muted,
                       int green, int border, int[] tileColors, boolean dark) {
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
    private final String buildLabel;
    private final Actions actions;
    private final DashboardData dashboard;

    public HomeScreen(Activity activity, LinearLayout content, Palette palette,
                      boolean signedIn, String buildLabel, Actions actions, DashboardData dashboard) {
        this.activity = activity;
        this.content = content;
        this.palette = palette;
        this.signedIn = signedIn;
        this.buildLabel = buildLabel;
        this.actions = actions;
        this.dashboard = dashboard == null
                ? new DashboardData(-1, -1, -1, -1, 0, 0, 0, Collections.emptyList()) : dashboard;
    }

    public void render() {
        renderSummary();
        sectionTitle("Быстрый доступ", null);
        renderQuickGrid();
        sectionTitle("Наблюдение", dashboard.trackingUnread > 0 ? dashboard.trackingUnread + " новых" : "Открыть", actions::openTracking);
        renderTrackingSummary();
        sectionTitle("Недавние репозитории", "Все", actions::openRepositories);
        renderRecentRepositories();

        TextView build = text(buildLabel, 11, false);
        build.setTextColor(palette.muted);
        build.setGravity(Gravity.CENTER);
        build.setPadding(0, dp(10), 0, dp(2));
        content.addView(build);
    }

    private void renderSummary() {
        LinearLayout summary = new LinearLayout(activity);
        summary.setOrientation(LinearLayout.VERTICAL);
        summary.setPadding(dp(14), dp(9), dp(14), dp(8));
        summary.setBackground(round(palette.subtle, 18, palette.border, 1));

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Сегодня в GitHubble", 18, true);
        title.setPadding(0, 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(30), 1));

        TextView status = text(signedIn ? "Синхронизация" : "Гостевой режим", 12, true);
        status.setTextColor(palette.green);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 0, 0, 0);
        status.setOnClickListener(v -> {
            if (!signedIn) actions.openProfile();
        });
        header.addView(status);
        summary.addView(header);

        LinearLayout stats = new LinearLayout(activity);
        stats.setGravity(Gravity.CENTER);
        String emptyValue = signedIn ? "…" : "Нет";
        stats.addView(statCell(R.drawable.ic_issue, countValue(dashboard.issues, emptyValue), "Задач", palette.tileColors[0]),
                new LinearLayout.LayoutParams(0, dp(58), 1));
        stats.addView(statCell(R.drawable.ic_pull_request, countValue(dashboard.pullRequests, emptyValue), "PR", palette.tileColors[1]),
                new LinearLayout.LayoutParams(0, dp(58), 1));
        stats.addView(statCell(R.drawable.ic_repository, countValue(dashboard.repositories, emptyValue), "Репо", palette.tileColors[4]),
                new LinearLayout.LayoutParams(0, dp(58), 1));
        stats.addView(statCell(R.drawable.ic_bell, countValue(dashboard.notifications, emptyValue), "Активно", palette.green),
                new LinearLayout.LayoutParams(0, dp(58), 1));
        summary.addView(stats);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(6));
        content.addView(summary, params);
    }

    private void renderQuickGrid() {
        GridLayout grid = new GridLayout(activity);
        grid.setColumnCount(adaptiveColumns());
        grid.setUseDefaultMargins(false);
        addTile(grid, R.drawable.tile_issue, "Задачи", palette.tileColors[0], actions::openIssues);
        addTile(grid, R.drawable.tile_pull_request, "Pull Requests", palette.tileColors[1], actions::openPullRequests);
        addTile(grid, R.drawable.tile_discussion, "Обсуждения", palette.tileColors[2], actions::openDiscussions);
        addTile(grid, R.drawable.tile_repository, "Репозитории", palette.tileColors[4], actions::openRepositories);
        addTile(grid, R.drawable.tile_organization, "Организации", palette.tileColors[6], actions::openOrganizations);
        addTile(grid, R.drawable.tile_projects, "Проекты", palette.tileColors[3], actions::openProjects);
        addTile(grid, R.drawable.tile_release, "Релизы", palette.tileColors[7], actions::openReleases);
        addTile(grid, R.drawable.tile_star, "Избранное", palette.tileColors[5], actions::openFavorites);
        addTile(grid, R.drawable.tile_history, "История", Color.rgb(93, 111, 104), actions::openHistory);
        content.addView(grid, new LinearLayout.LayoutParams(-1, -2));
    }


    private void renderTrackingSummary() {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(11), dp(14), dp(11));
        row.setBackground(round(palette.card, 17, palette.border, 1));

        ImageView icon = imageIcon(R.drawable.ic_bell, palette.green);
        icon.setBackground(round(palette.subtle, 16, palette.border, 1));
        row.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout info = new LinearLayout(activity);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(12), 0, dp(6), 0);
        TextView title = text(dashboard.trackedDevelopers == 0 ? "Следите за разработчиками" : "Новое у разработчиков", 15, true);
        title.setPadding(0,0,0,0);
        TextView detail = text(dashboard.trackedDevelopers == 0
                ? "Получайте новые репозитории, релизы и рост проектов"
                : dashboard.trackedDevelopers + " разработчиков · " + dashboard.trackedRepositories + " репозиториев · " + dashboard.trackingUnread + " новых", 11, false);
        detail.setTextColor(palette.muted);
        detail.setPadding(0, dp(3), 0, 0);
        info.addView(title); info.addView(detail);
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

        TextView arrow = text("›", 28, false);
        arrow.setTextColor(palette.muted); arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(28), dp(48)));
        row.setOnClickListener(v -> actions.openTracking());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(72));
        params.setMargins(0, 0, 0, dp(7));
        content.addView(row, params);
    }

    private void renderRecentRepositories() {
        if (!signedIn) {
            TextView empty = text("Войдите в GitHub, чтобы увидеть свои и недавно открытые репозитории.", 13, false);
            empty.setTextColor(palette.muted);
            card(empty);
            return;
        }
        if (dashboard.recentRepositories.isEmpty()) {
            TextView sync = text("Репозитории загружаются. При отсутствии сети будет показан сохранённый список.", 13, false);
            sync.setTextColor(palette.muted);
            card(sync);
            return;
        }
        int max = Math.min(3, dashboard.recentRepositories.size());
        for (int i = 0; i < max; i++) renderRecentRepository(dashboard.recentRepositories.get(i));
    }

    private String countValue(int value, String fallback) {
        return value < 0 ? fallback : String.valueOf(value);
    }

    private void renderRecentRepository(RepoPreview repo) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(11), dp(9), dp(11), dp(9));
        row.setBackground(round(palette.card, 17, palette.border, 1));

        String initial = repo.fullName.isEmpty() ? "R" : repo.fullName.substring(0, 1).toUpperCase(Locale.ROOT);
        TextView avatar = text(initial, 16, true);
        avatar.setTextColor(Color.WHITE);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(round(palette.green, 16, palette.green, 0));
        row.addView(avatar, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout info = new LinearLayout(activity);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(11), 0, dp(6), 0);
        TextView name = text(repo.fullName, 14, true);
        name.setPadding(0, 0, 0, 0);
        name.setMaxLines(1);
        TextView description = text(repo.description.isEmpty() ? "Без описания" : repo.description, 11, false);
        description.setTextColor(palette.muted);
        description.setPadding(0, dp(2), 0, 0);
        description.setMaxLines(1);
        info.addView(name);
        info.addView(description);
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout meta = new LinearLayout(activity);
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.setGravity(Gravity.END);
        TextView stars = text("★ " + repo.stars, 10, true);
        stars.setTextColor(palette.tileColors[5]);
        stars.setGravity(Gravity.END);
        stars.setPadding(0, 0, 0, 0);
        TextView updated = text(repo.updated, 9, false);
        updated.setTextColor(palette.muted);
        updated.setGravity(Gravity.END);
        updated.setPadding(0, dp(2), 0, 0);
        meta.addView(stars);
        meta.addView(updated);
        row.addView(meta);

        row.setOnClickListener(v -> actions.openRepository(repo.fullName));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(64));
        params.setMargins(0, 0, 0, dp(7));
        content.addView(row, params);
    }

    private View statCell(int iconRes, String value, String label, int color) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        ImageView icon = imageIcon(iconRes, color);
        box.addView(icon, new LinearLayout.LayoutParams(dp(23), dp(20)));
        TextView v = text(value, value.length() > 4 ? 11 : 15, true);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER);
        v.setPadding(0, 0, 0, 0);
        TextView l = text(label, 10, false);
        l.setTextColor(palette.muted);
        l.setGravity(Gravity.CENTER);
        l.setPadding(0, 0, 0, 0);
        box.addView(v);
        box.addView(l);
        return box;
    }

    private void addTile(GridLayout grid, int iconRes, String title, int color, Runnable action) {
        LinearLayout tile = new LinearLayout(activity);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(5), dp(7), dp(5), dp(7));
        int tintedCard = blend(palette.card, color, palette.dark ? 0.14f : 0.075f);
        tile.setBackground(round(tintedCard, 17, blend(palette.border, color, 0.12f), 1));
        tile.setElevation(dp(1));

        ImageView art = new ImageView(activity);
        art.setImageResource(iconRes);
        art.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams artParams = new LinearLayout.LayoutParams(dp(60), dp(60));
        artParams.gravity = Gravity.CENTER_HORIZONTAL;
        tile.addView(art, artParams);

        TextView t = text(title, title.length() > 13 ? 12 : 13, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(1), dp(2), dp(1), 0);
        t.setMaxLines(1);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tile.addView(t, new LinearLayout.LayoutParams(-1, -2));
        tile.setOnClickListener(v -> action.run());

        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = dp(104);
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        grid.addView(tile, params);
    }

    private void sectionTitle(String title, String action) {
        sectionTitle(title, action, null);
    }

    private void sectionTitle(String title, String action, Runnable clickAction) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(title, 20, true);
        row.addView(t, new LinearLayout.LayoutParams(0, dp(44), 1));
        if (action != null) {
            TextView a = text(action, 14, true);
            a.setTextColor(palette.green);
            a.setGravity(Gravity.CENTER);
            row.addView(a);
            if (clickAction != null) a.setOnClickListener(v -> clickAction.run());
        }
        content.addView(row);
    }

    private void card(View child) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(13), dp(16), dp(13));
        box.setBackground(round(palette.card, 18, palette.border, 1));
        box.setElevation(dp(1));
        box.addView(child);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(10));
        content.addView(box, p);
    }

    private ImageView imageIcon(int iconRes, int tint) {
        ImageView view = new ImageView(activity);
        view.setImageResource(iconRes);
        view.setColorFilter(tint);
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        view.setPadding(dp(1), dp(1), dp(1), dp(1));
        return view;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(palette.text);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setPadding(dp(4), dp(4), dp(4), dp(4));
        view.setLineSpacing(0f, activity.getResources().getConfiguration().fontScale >= 1.25f ? 1.18f : 1.08f);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        if(android.os.Build.VERSION.SDK_INT>=29){view.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY);view.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NORMAL);}
        return view;
    }

    private GradientDrawable round(int fill, int radius, int stroke, int width) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        if (width > 0) drawable.setStroke(dp(width), stroke);
        return drawable;
    }

    private int blend(int base, int accent, float amount) {
        amount = Math.max(0f, Math.min(1f, amount));
        int r = (int) (Color.red(base) * (1f - amount) + Color.red(accent) * amount);
        int g = (int) (Color.green(base) * (1f - amount) + Color.green(accent) * amount);
        int b = (int) (Color.blue(base) * (1f - amount) + Color.blue(accent) * amount);
        return Color.rgb(r, g, b);
    }

    private int adaptiveColumns(){int sw=activity.getResources().getConfiguration().smallestScreenWidthDp;float font=activity.getResources().getConfiguration().fontScale;if(sw>=840)return 4;if(sw>=600)return 4;if(font>=1.25f)return 2;return 3;}

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}
