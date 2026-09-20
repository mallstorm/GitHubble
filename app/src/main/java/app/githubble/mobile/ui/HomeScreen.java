package app.githubble.mobile.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
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
        void openSearch();
        void openNotifications();
        ImageView profileAvatar();
        void openAccountCenter();
        void openRepository(String fullName);
        void openActivity();
        void openExplore();
        void openAllSections();
        boolean toggleRecommendedFavorite(String fullName);
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

    public static final class RecommendationPreview {
        public final String fullName;
        public final long stars;
        public final boolean favorite;
        public RecommendationPreview(String fullName, long stars, boolean favorite) {
            this.fullName = fullName;
            this.stars = stars;
            this.favorite = favorite;
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
        public final List<RecommendationPreview> recommendations;
        public DashboardData(int issues, int pullRequests, int repositories, int notifications,
                             int trackedDevelopers, int trackedRepositories, int trackingUnread,
                             List<RepoPreview> recentRepositories, List<ActivityPreview> recentActivity,
                             String login, long syncedAt,
                             List<RecommendationPreview> recommendations) {
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
            this.recommendations = recommendations == null ? Collections.emptyList() : recommendations;
        }
        public RecommendationPreview recommendation(String fullName) {
            for (RecommendationPreview item : recommendations) if (item.fullName.equals(fullName)) return item;
            return new RecommendationPreview(fullName, -1L, false);
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
    private final boolean spaciousCards;
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
        this.spaciousCards = "spacious".equals(activity.getSharedPreferences("githubble",Activity.MODE_PRIVATE)
                .getString("ui_card_density","compact"));
        this.actions = actions;
        this.data = data == null
                ? new DashboardData(-1,-1,-1,-1,0,0,0,Collections.emptyList(),
                                    Collections.emptyList(),"",0,Collections.emptyList()) : data;
    }

    public void render() {
        renderHero();
        renderStats();
        section("Быстрый доступ", "Все разделы", actions::openAllSections);
        renderQuickAccess();
        section("Недавняя активность", "Все события", actions::openActivity);
        renderActivity();
        section("Рекомендуем для вас", "Больше", actions::openExplore);
        renderRecommendations();
    }

    private void renderHero() {
        // All home header controls and the welcome message belong to ONE banner.
        FrameLayout hero = new FrameLayout(activity);
        hero.setBackground(round(palette.dark ? 0xFF263A37 : 0xFF173C34, 18, palette.border, 0));
        hero.setClipToOutline(true);

        ImageView landscape = new ImageView(activity);
        landscape.setImageResource(R.drawable.home_geo_emerald);
        landscape.setScaleType(ImageView.ScaleType.CENTER_CROP);
        hero.addView(landscape, new FrameLayout.LayoutParams(-1, -1));

        View scrim = new View(activity);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x76112335, 0x2E142935, 0x16102429}));
        hero.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(11), dp(5), dp(8), dp(5));

        ImageView brand = new ImageView(activity);
        brand.setImageResource(R.drawable.ic_home_rocket);
        brand.setBackground(round(0xEEFFFFFF, 13, 0, 0));
        brand.setPadding(dp(5), dp(5), dp(5), dp(5));
        brand.setContentDescription("GitHubble");
        header.addView(brand, new LinearLayout.LayoutParams(dp(36), dp(36)));

        LinearLayout brandText = new LinearLayout(activity);
        brandText.setOrientation(LinearLayout.VERTICAL);
        brandText.setPadding(dp(7), 0, 0, 0);
        TextView logo = text("GitHubble", 19, true);
        logo.setSingleLine();
        logo.setEllipsize(TextUtils.TruncateAt.END);
        logo.setTextColor(Color.WHITE);
        TextView tagline = text("Ваш GitHub всегда рядом", 9, false);
        tagline.setTextColor(0xF0FFFFFF);
        tagline.setSingleLine();
        tagline.setEllipsize(TextUtils.TruncateAt.END);
        brandText.addView(logo);
        brandText.addView(tagline);
        header.addView(brandText, new LinearLayout.LayoutParams(0, -2, 1));

        ImageView account = actions.profileAvatar();
        account.setContentDescription("Профиль GitHub");
        account.setOnClickListener(v -> actions.openProfile());
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        avatarLp.setMargins(dp(2), 0, 0, 0);
        header.addView(account, avatarLp);

        FrameLayout.LayoutParams headerLp = new FrameLayout.LayoutParams(-1, dp(56), Gravity.TOP);
        hero.addView(header, headerLp);

        TextView greeting = text(daypart() + (signedIn && !data.login.isEmpty()
                ? ",\n" + data.login + "!" : "!"), 17, true);
        greeting.setTextColor(Color.WHITE);
        greeting.setMaxLines(2);
        greeting.setEllipsize(TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams greetingLp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        greetingLp.setMargins(dp(16), dp(54), dp(114), 0);
        hero.addView(greeting, greetingLp);

        TextView subtitle = text("Развивайте свои идеи.\nКод. Проекты. Сообщество.", 11, false);
        subtitle.setTextColor(Color.WHITE);
        subtitle.setMaxLines(2);
        FrameLayout.LayoutParams subtitleLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        subtitleLp.setMargins(dp(16), 0, dp(129), dp(13));
        hero.addView(subtitle, subtitleLp);

        TextView quote = text("«Большие проекты\nначинаются с одной идеи»", 9, false);
        quote.setTextColor(Color.WHITE);
        quote.setPadding(dp(7), dp(7), dp(7), dp(7));
        quote.setBackground(round(0xB523383B, 10, 0x55FFFFFF, 1));
        FrameLayout.LayoutParams quoteLp = new FrameLayout.LayoutParams(dp(112), -2, Gravity.END | Gravity.BOTTOM);
        quoteLp.setMargins(0, 0, dp(8), dp(13));
        hero.addView(quote, quoteLp);

        installHeroCarousel(hero, landscape);
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(-1, dp(145));
        heroLp.setMargins(0, 0, 0, dp(7));
        content.addView(hero, heroLp);
    }

    private FrameLayout headerAction(int drawable, String description) {
        FrameLayout target = new FrameLayout(activity);
        target.setContentDescription(description);
        ImageView icon = imageIcon(drawable, Color.WHITE);
        icon.setContentDescription(description);
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(23), dp(23), Gravity.CENTER);
        target.addView(icon, iconLp);
        return target;
    }


    private static int retainedBannerIndex = 0;
    private static final int[] HERO_IMAGES = {
            R.drawable.home_geo_emerald, R.drawable.home_geo_amethyst,
            R.drawable.home_geo_copper, R.drawable.home_geo_aurora
    };

    private void installHeroCarousel(FrameLayout hero, ImageView landscape) {
        LinearLayout dots = new LinearLayout(activity);
        dots.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams dotParams = new FrameLayout.LayoutParams(-2, dp(7),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dotParams.setMargins(0, 0, 0, dp(3));
        hero.addView(dots, dotParams);
        final int[] selected = {retainedBannerIndex % HERO_IMAGES.length};
        final Runnable updateDots = () -> {
            dots.removeAllViews();
            for (int i = 0; i < HERO_IMAGES.length; i++) {
                View dot = new View(activity);
                dot.setBackground(round(i == selected[0] ? 0xFF53D96B : 0x99FFFFFF, 6, 0, 0));
                LinearLayout.LayoutParams dotP = new LinearLayout.LayoutParams(
                        dp(i == selected[0] ? 17 : 5), dp(4));
                dotP.setMargins(dp(2), 0, dp(2), 0);
                dots.addView(dot, dotP);
            }
        };
        landscape.setImageResource(HERO_IMAGES[selected[0]]);
        updateDots.run();
        final Runnable[] change = new Runnable[1];
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable[] tick = new Runnable[1];
        change[0] = () -> {
            retainedBannerIndex = selected[0];
            landscape.animate().cancel();
            landscape.setAlpha(0.55f);
            landscape.setImageResource(HERO_IMAGES[selected[0]]);
            landscape.animate().alpha(1f).setDuration(330).start();
            updateDots.run();
        };
        tick[0] = () -> {
            if (!hero.isAttachedToWindow() || hero.getWindowVisibility() != View.VISIBLE) return;
            if (activity.hasWindowFocus()) {
                selected[0] = (selected[0] + 1) % HERO_IMAGES.length;
                change[0].run();
            }
            handler.postDelayed(tick[0], 8000L);
        };
        hero.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {
                handler.removeCallbacks(tick[0]);
                handler.postDelayed(tick[0], 8000L);
            }
            @Override public void onViewDetachedFromWindow(View v) {
                handler.removeCallbacks(tick[0]);
                landscape.animate().cancel();
            }
        });
        final float[] x = {0}, y = {0};
        hero.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                x[0] = event.getX(); y[0] = event.getY();
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                float dx = event.getX() - x[0];
                float dy = event.getY() - y[0];
                if (Math.abs(dx) > dp(35) && Math.abs(dx) > Math.abs(dy)) {
                    selected[0] = (selected[0] + (dx < 0 ? 1 : HERO_IMAGES.length - 1))
                            % HERO_IMAGES.length;
                    change[0].run();
                    handler.removeCallbacks(tick[0]);
                    handler.postDelayed(tick[0], 8000L);
                    return true;
                }
                view.performClick();
                return true;
            }
            return event.getActionMasked() == MotionEvent.ACTION_MOVE;
        });
        hero.setContentDescription("Карусель изображений GitHubble. Проведите влево или вправо.");
    }

    private String daypart() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (h < 5) return "Доброй ночи";
        if (h < 12) return "Доброе утро";
        if (h < 18) return "Добрый день";
        return "Добрый вечер";
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
                    new Tile("Репозитории","Мои проекты",R.drawable.ic_repository,palette.green,actions::openRepositories),
                    new Tile("Pull Requests","Проверка кода",R.drawable.ic_pull_request,blue,actions::openPullRequests),
                    new Tile("Релизы","Версии и сборки",R.drawable.ic_release,orange,actions::openReleases),
                    new Tile("Проекты","Планирование",R.drawable.ic_projects,violet,actions::openProjects)
                },palette.green,true);
        addGroup(groups,"Сообщество и управление",
                new Tile[]{
                    new Tile("Issues","Задачи и баги",R.drawable.ic_issue,red,actions::openIssues),
                    new Tile("Обсуждения","Идеи и общение",R.drawable.ic_discussion,violet,actions::openDiscussions),
                    new Tile("Организации","Команды и доступ",R.drawable.ic_organization,teal,actions::openOrganizations),
                    new Tile("Избранное","Сохранённые",R.drawable.ic_star,amber,actions::openFavorites)
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
        box.setPadding(dp(8),dp(9),dp(5),dp(4));
        box.setBackground(round(palette.card,10,palette.border,1));
        // Always draw a visible icon: vector tinted white on its own branded color badge.
        ImageView icon = imageIcon(item.icon, Color.WHITE);
        icon.setBackground(round(item.accent, 10, 0, 0));
        icon.setPadding(dp(6), dp(6), dp(6), dp(6));
        icon.setContentDescription(item.title);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(34), dp(34));
        iconLp.setMargins(dp(5), 0, 0, 0);
        box.addView(icon, iconLp);
        TextView title = text(item.title,10,true);
        title.setPadding(0,dp(2),0,0);
        title.setSingleLine();
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.setMargins(dp(2), 0, 0, 0);
        box.addView(title, titleLp);
        TextView subtitle = text(item.subtitle,8,false);
        subtitle.setTextColor(palette.muted);
        subtitle.setPadding(0,0,0,0);
        subtitle.setSingleLine();
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams subtitleLp = new LinearLayout.LayoutParams(-1, -2);
        subtitleLp.setMargins(dp(2), 0, 0, 0);
        box.addView(subtitle, subtitleLp);
        box.setOnClickListener(v -> item.action.run());
        GridLayout.LayoutParams p = new GridLayout.LayoutParams();
        p.width=0;p.height=dp(spaciousCards ? 94 : 77);
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
        int iconRes = "Issue".equals(event.kind) ? R.drawable.ic_issue
                : "PullRequest".equals(event.kind) ? R.drawable.ic_pull_request : R.drawable.ic_repository;
        ImageView icon = imageIcon(iconRes, Color.WHITE);
        icon.setBackground(round(accent, 9, 0, 0));
        icon.setPadding(dp(7), dp(7), dp(7), dp(7));
        row.addView(icon,new LinearLayout.LayoutParams(dp(35),dp(35)));
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
        recommendation(row, "Android", "Android-проекты", R.drawable.ic_android, palette.green,
                "android/architecture-samples", true);
        recommendation(row, "Kotlin", "Язык Kotlin", R.drawable.ic_kotlin_brand, violet,
                "JetBrains/kotlin", false);
        recommendation(row, "GitHub", "Open Source", R.drawable.ic_github_mark, slate,
                "github/gitignore", false);
        content.addView(row, new LinearLayout.LayoutParams(-1, dp(spaciousCards ? 108 : 88)));
    }

    private String formatStars(long value) {
        if (value < 0) return "—";
        if (value < 1000) return Long.toString(value);
        double amount = value >= 1000000 ? value / 1000000d : value / 1000d;
        String unit = value >= 1000000 ? " млн" : " тыс.";
        if (amount >= 100 || amount == Math.floor(amount)) {
            return String.format(java.util.Locale.forLanguageTag("ru"), "%.0f%s", amount, unit);
        }
        return String.format(java.util.Locale.forLanguageTag("ru"), "%.1f%s", amount, unit);
    }

    private void recommendation(LinearLayout row, String name, String subtitle, int icon,
                                int accent, String repo, boolean first) {
        RecommendationPreview info = data.recommendation(repo);
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(6), dp(6), dp(6), dp(5));
        card.setBackground(round(palette.card, 12, palette.border, 1));
        card.setOnClickListener(v -> actions.openRepository(repo));

        LinearLayout top = new LinearLayout(activity);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView art = imageIcon(icon, Color.WHITE);
        if ("Kotlin".equals(name)) art.clearColorFilter();
        art.setPadding(dp(5), dp(5), dp(5), dp(5));
        art.setBackground(round("Kotlin".equals(name) ? 0xFF17243C : accent, 9, 0, 0));
        art.setContentDescription("Логотип " + name);
        top.addView(art, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout names = new LinearLayout(activity);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(4), 0, 0, 0);
        TextView title = text(name, 10, true);
        title.setSingleLine(); title.setEllipsize(TextUtils.TruncateAt.END);
        TextView description = text(subtitle, 8, false);
        description.setTextColor(palette.muted);
        description.setSingleLine(); description.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(title);
        names.addView(description);
        top.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(top);

        LinearLayout spacer = new LinearLayout(activity);
        card.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout bottom = new LinearLayout(activity);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        ImageView star = imageIcon(R.drawable.ic_star, amber);
        bottom.addView(star, new LinearLayout.LayoutParams(dp(14), dp(14)));
        TextView count = text(formatStars(info.stars), 9, true);
        count.setTextColor(palette.text);
        count.setSingleLine();
        count.setEllipsize(TextUtils.TruncateAt.END);
        count.setPadding(dp(2), 0, 0, 0);
        bottom.addView(count, new LinearLayout.LayoutParams(0, -2, 1));

        TextView favorite = text(info.favorite ? "✓" : "+", 18, true);
        favorite.setGravity(Gravity.CENTER);
        favorite.setTextColor(palette.green);
        favorite.setContentDescription(info.favorite ? "Удалить из избранного: " + repo
                : "Добавить в избранное: " + repo);
        favorite.setBackground(round(info.favorite
                ? blend(palette.card, palette.green, 0.16f) : palette.card,
                18, palette.green, 1));
        favorite.setOnClickListener(v -> {
            boolean saved = actions.toggleRecommendedFavorite(repo);
            favorite.setText(saved ? "✓" : "+");
            favorite.setContentDescription(saved ? "Удалить из избранного: " + repo
                    : "Добавить в избранное: " + repo);
            favorite.setBackground(round(saved ? blend(palette.card, palette.green, 0.16f)
                    : palette.card, 18, palette.green, 1));
        });
        bottom.addView(favorite, new LinearLayout.LayoutParams(dp(30), dp(30)));
        card.addView(bottom);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1);
        p.setMargins(first ? 0 : dp(3), 0, dp(3), 0);
        row.addView(card, p);
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
