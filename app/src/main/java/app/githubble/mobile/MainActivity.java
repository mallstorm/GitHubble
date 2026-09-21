package app.githubble.mobile;

import android.app.*;
import android.os.*;
import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.text.TextUtils;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.RelativeSizeSpan;
import android.text.util.Linkify;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import app.githubble.mobile.auth.OAuthManager;
import app.githubble.mobile.auth.DeviceCodeAssist;
import app.githubble.mobile.network.GitHubApiClient;
import app.githubble.mobile.network.GitHubGraphQlClient;
import app.githubble.mobile.security.SecureStore;
import app.githubble.mobile.ui.HomeScreen;
import app.githubble.mobile.ui.BottomNavigationBar;
import app.githubble.mobile.build.BuildRunFilter;
import app.githubble.mobile.ui.SearchScreen;
import app.githubble.mobile.tracking.TrackingDatabase;
import app.githubble.mobile.tracking.TrackingEngine;
import app.githubble.mobile.tracking.TrackingJobService;
import app.githubble.mobile.build.BuildMonitor;
import app.githubble.mobile.build.BuildMonitorJobService;
import app.githubble.mobile.build.BuildArtifactDownloads;
import app.githubble.mobile.tracking.TrackingNotificationHelper;
import app.githubble.mobile.web.GitHubCustomTabs;
import app.githubble.mobile.translation.LanguageDetector;
import app.githubble.mobile.translation.TranslationCache;
import app.githubble.mobile.translation.TranslationEngine;
import app.githubble.mobile.code.CodeHighlighter;
import app.githubble.mobile.diagnostics.TrackingDiagnostics;
import app.githubble.mobile.diagnostics.DiagnosticsExporter;
import app.githubble.mobile.cache.EntityCache;
import app.githubble.mobile.controller.RepositoryController;
import app.githubble.mobile.controller.CommitDetailController;
import app.githubble.mobile.ui.CommitDetailScreen;
import app.githubble.mobile.controller.RepositoryAccessController;
import app.githubble.mobile.controller.AccountOperationsController;
import app.githubble.mobile.controller.RepositoryImportController;
import app.githubble.mobile.controller.ProfileController;
import app.githubble.mobile.controller.TranslationController;
import app.githubble.mobile.controller.CopilotAgentController;
import app.githubble.mobile.controller.CopilotPermissionReport;
import app.githubble.mobile.copilot.CopilotNotificationHelper;
import app.githubble.mobile.copilot.CopilotTaskMonitorJobService;
import app.githubble.mobile.state.ScreenStateStore;
import app.githubble.mobile.state.FeatureUiState;
import app.githubble.mobile.util.DeviceCodeFormatter;
import app.githubble.mobile.screen.FeatureControllerRegistry;
import app.githubble.mobile.offline.OfflineActionQueue;
import app.githubble.mobile.offline.OfflineMutationDispatcher;
import app.githubble.mobile.diff.DiffModel;
import app.githubble.mobile.preview.FilePreviewController;
import app.githubble.mobile.backup.PortableSettingsManager;
import app.githubble.mobile.performance.PerformanceMonitor;
import app.githubble.mobile.accessibility.AccessibilityAudit;
import app.githubble.mobile.privacy.UserDataManager;
import app.githubble.mobile.release.ReleaseIntegrity;
import app.githubble.mobile.database.SchemaMigrationRegistry;
import app.githubble.mobile.search.SmartSearchPlanner;
import app.githubble.mobile.search.RepositorySearchRanker;
import app.githubble.mobile.search.SmartSearchHistory;
import app.githubble.mobile.search.SearchMode;
import app.githubble.mobile.search.SearchFilters;
import app.githubble.mobile.search.AdvancedSearchPlanner;
import app.githubble.mobile.repository.RepositoryStore;
import app.githubble.mobile.activity.RepositoryActivityFeed;

public class MainActivity extends Activity {
    private LinearLayout content;
    private final ExecutorService io = Executors.newFixedThreadPool(4);
    // Long-running storage inventory must not occupy the interactive API pool.
    private final ExecutorService inventoryIo = Executors.newSingleThreadExecutor();
    private java.util.concurrent.Future<?> accountInventoryFuture;
    private AtomicBoolean accountInventoryCancelled = new AtomicBoolean(true);
    private SharedPreferences prefs;
    private SecureStore secureStore;
    private GitHubApiClient api;
    private GitHubGraphQlClient graphql;
    private OAuthManager oauth;
    private TrackingDatabase trackingDb;
    private TrackingEngine trackingEngine;
    private TranslationCache translationCache;
    private TranslationEngine translationEngine;
    private EntityCache entityCache;
    private RepositoryController repositoryController;
    private RepositoryAccessController repositoryAccess;
    private RepositoryStore repositoryStore;
    private boolean repositoriesPrivateOnly = false;
    private boolean repositoriesPublicOnly = false;
    private boolean repositoriesGrid = false;
    private String buildRunFilter = BuildRunFilter.ALL;
    private int buildVisibleLimit = 12;
    private String repositoriesSort = "updated";
    private AccountOperationsController accountOperations;
    private ProfileController profileController;
    private TranslationController translationController;
    private ScreenStateStore screenState;
    private FeatureControllerRegistry features;
    private PerformanceMonitor performanceMonitor;
    private UserDataManager userDataManager;
    private final FeatureUiState featureState = new FeatureUiState();
    private ScrollView currentScroll;
    private int BG, SURFACE, CARD, SUBTLE, TEXT, MUTED, BLUE, GREEN, BORDER;
    private int[] tileColors;
    private String activeTheme = "light";
    private final Deque<Runnable> backStack = new ArrayDeque<>();
    private Runnable currentScreen;
    private boolean restoringBack = false;
    private String currentTitle = "";
    private final AtomicBoolean profileRefreshInFlight = new AtomicBoolean(false);
    private final AtomicBoolean notificationRefreshInFlight = new AtomicBoolean(false);
    private final AtomicBoolean homeDashboardRefreshInFlight = new AtomicBoolean(false);
    private final AtomicBoolean homeRecommendationsRefreshInFlight = new AtomicBoolean(false);
    private static final String[] HOME_RECOMMENDED_REPOS = {
            "android/architecture-samples", "JetBrains/kotlin", "github/gitignore"
    };
    private final AtomicBoolean trackingCheckInFlight = new AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicLong screenGeneration = new java.util.concurrent.atomic.AtomicLong();
    private final Handler copilotPollHandler = new Handler(Looper.getMainLooper());
    private Runnable copilotPollRunnable;
    private String activeCopilotTaskId = "";
    private boolean registrationCustomTabActive = false;
    private boolean registrationCustomTabWasPaused = false;
    private boolean registrationReturnDialogVisible = false;
    private boolean repositoryAccessBrowserActive = false;
    private boolean repositoryAccessBrowserWasPaused = false;
    private static final String PREF_REGISTRATION_PENDING = "github_registration_pending";
    private static final String PREF_REGISTRATION_LAUNCHED_AT = "github_registration_launched_at";
    private static final String PREF_AUTH_JUST_COMPLETED = "github_auth_just_completed";
    private static final String EXTRA_OPEN_PROFILE_AFTER_AUTH = "open_profile_after_github_auth";
    private static final int REQUEST_EXPORT_PORTABLE = 2701;
    private static final int REQUEST_IMPORT_PORTABLE = 2702;
    private static final int REQUEST_EXPORT_USER_DATA = 2703;
    private static final int REQUEST_REPOSITORY_FOLDER = 2710;
    private static final int REQUEST_REPOSITORY_ZIP = 2711;
    private static final int REQUEST_REPOSITORY_FILE = 2712;
    private static final int REQUEST_VOICE_AUDIO = 2720;
    private static final int REQUEST_LOCAL_AVATAR = 2721;
    private SpeechRecognizer voiceRecognizer;
    private EditText voiceTarget;
    private ImageView voiceButton;
    private boolean voiceRecording;
    private boolean voiceWaiting;
    private String pendingAvatarAccount = "";
    private String pendingPortableExport = "";
    private String pendingUserDataExport = "";
    private String pendingRepositoryImportFull = "";
    private String pendingRepositoryImportBranch = "main";
    private final Set<String> smartSearchRendered = new HashSet<>();

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    this::goBack);
        }
        prefs = getSharedPreferences("githubble", MODE_PRIVATE);
        repositoriesGrid = prefs.getBoolean("repositories_grid", false);
        repositoriesSort = prefs.getString("repositories_sort", "updated");
        secureStore = new SecureStore(this);
        entityCache = new EntityCache(this);
        api = new GitHubApiClient(secureStore, entityCache);
        graphql = new GitHubGraphQlClient(api);
        oauth = new OAuthManager(this, secureStore);
        trackingDb = new TrackingDatabase(this);
        trackingEngine = new TrackingEngine(this, trackingDb, api);
        translationCache = new TranslationCache(this);
        translationEngine = new TranslationEngine();
        repositoryController = new RepositoryController(api);
        repositoryAccess = new RepositoryAccessController(api);
        repositoryStore = new RepositoryStore(prefs);
        accountOperations = new AccountOperationsController(api);
        profileController = new ProfileController(api);
        translationController = new TranslationController(secureStore, prefs, translationCache, translationEngine);
        screenState = new ScreenStateStore(this);
        features = new FeatureControllerRegistry(this, api, graphql, prefs, trackingDb);
        performanceMonitor = new PerformanceMonitor(this);
        userDataManager = new UserDataManager(this, prefs, secureStore, entityCache, translationCache, trackingDb, features.offlineQueue, features.portableSettings);
        featureState.restore(b);
        featureState.diffMode=prefs.getString("diff_mode",featureState.diffMode);
        featureState.collapseDiffContext=prefs.getBoolean("diff_collapse_context",featureState.collapseDiffContext);
        screenState.restore(b);
        TrackingNotificationHelper.createChannel(this);
        BuildMonitor.createChannel(this);
        BuildMonitorJobService.reschedule(this);
        CopilotNotificationHelper.createChannel(this);
        if (prefs.getBoolean("copilot_monitor_enabled", false)) CopilotTaskMonitorJobService.schedule(this, prefs.getInt("copilot_monitor_interval_minutes", 60));
        if (!trackingDb.getDevelopers(true).isEmpty()) TrackingJobService.schedule(this, prefs.getInt("tracking_interval_hours", 6));
        io.execute(() -> { try { features.offlineQueue.processReady(api); } catch (Exception ignored) {} });
        if (!oauth.hasUsableToken() && oauth.canRefresh()) io.execute(() -> { try { if(oauth.refreshIfNeeded(BuildConfig.GITHUB_CLIENT_ID)) runOnUiThread(this::showHome); } catch(Exception ignored) { } });
        migrateSecureSettings();
        applyTheme();
        restoreLegacyThemeIconAfterUpgrade();
        showHome();
        getWindow().getDecorView().post(performanceMonitor::markFirstFrame);
        handleIntent(getIntent());
        if (b != null && (getIntent() == null || !getIntent().getBooleanExtra(EXTRA_OPEN_PROFILE_AFTER_AUTH, false))) {
            screenState.restore(b);
            new Handler(Looper.getMainLooper()).postDelayed(this::restoreScreenFromState, 120L);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_OPEN_PROFILE_AFTER_AUTH, false)) {
            intent.removeExtra(EXTRA_OPEN_PROFILE_AFTER_AUTH);
            prefs.edit().putBoolean(PREF_AUTH_JUST_COMPLETED, false).apply();
            openRoot(this::showProfile);
            return;
        }
        handleIntent(intent);
    }

    @Override protected void onPause() {
        stopCopilotForegroundPolling();
        super.onPause();
        if (registrationCustomTabActive) registrationCustomTabWasPaused = true;
        if (repositoryAccessBrowserActive) repositoryAccessBrowserWasPaused = true;
    }

    @Override protected void onResume() {
        super.onResume();
        if (prefs == null) return;
        BuildMonitorJobService.checkSoon(this);
        if (features != null && features.offlineDispatcher.isOnline()) io.execute(() -> { try { OfflineActionQueue.ProcessResult r=features.offlineQueue.processReady(api); if(r.completed>0||r.conflicts>0) runOnUiThread(() -> toast("Офлайн-очередь: отправлено "+r.completed+", конфликтов "+r.conflicts)); } catch (Exception ignored) {} });
        if (prefs.getBoolean(PREF_AUTH_JUST_COMPLETED, false)) {
            prefs.edit().putBoolean(PREF_AUTH_JUST_COMPLETED, false).apply();
            openRoot(this::showProfile);
            return;
        }
        boolean returnedFromCurrentLaunch = registrationCustomTabActive && registrationCustomTabWasPaused;
        boolean restoredPending = prefs.getBoolean(PREF_REGISTRATION_PENDING, false)
            && System.currentTimeMillis() - prefs.getLong(PREF_REGISTRATION_LAUNCHED_AT, 0L) > 1000L;
        if (returnedFromCurrentLaunch || restoredPending) {
            registrationCustomTabActive = false;
            registrationCustomTabWasPaused = false;
            prefs.edit().putBoolean(PREF_REGISTRATION_PENDING, false).apply();
            new Handler(Looper.getMainLooper()).postDelayed(this::showRegistrationReturnDialog, 250L);
        }
        if (repositoryAccessBrowserActive && repositoryAccessBrowserWasPaused) {
            repositoryAccessBrowserActive = false;
            repositoryAccessBrowserWasPaused = false;
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (isSignedIn()) openRoot(() -> showMyRepositories(true));
            }, 450L);
        }
    }

    private void setCurrentScreen(Runnable screen) { currentScreen = screen; }

    private void navigate(Runnable next) {
        if (currentScreen != null && !restoringBack) backStack.push(currentScreen);
        next.run();
    }

    private void openRoot(Runnable next) {
        backStack.clear();
        next.run();
    }

    private void goBack() {
        if (backStack.isEmpty()) { finish(); return; }
        Runnable previous = backStack.pop();
        restoringBack = true;
        try { previous.run(); } finally { restoringBack = false; }
    }

    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() {
        goBack();
    }

    private void shell(String title, boolean showSearchAction) {
        // Discard old account-inventory callbacks on ANY screen transition.
        accountInventoryCancelled.set(true);
        if(accountInventoryFuture!=null&&!accountInventoryFuture.isDone())
            accountInventoryFuture.cancel(true);
        screenGeneration.incrementAndGet();
        currentTitle = title;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        if (!"Главная".equals(title)) {
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp("Главная".equals(title) ? 12 : 20), dp(8), dp(10), dp(8));
        top.setBackgroundColor(SURFACE);

        if (!backStack.isEmpty()) {
            ImageView back = iconButton(R.drawable.ic_back, "Назад");
            back.setOnClickListener(v -> goBack());
            top.addView(back, new LinearLayout.LayoutParams(dp(46), dp(46)));
        }

        if ("Главная".equals(title) || "Репозитории".equals(title) || "Активность".equals(title) || "Поиск".equals(title) || "Профиль".equals(title)) {
            ImageView homeBrand = new ImageView(this);
            homeBrand.setImageResource(R.drawable.ic_home_rocket);
            homeBrand.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(dp(32), dp(32));
            brandParams.setMargins(0, 0, dp(8), 0);
            top.addView(homeBrand, brandParams);
        }
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_VERTICAL);
        TextView logo = text("GitHubble", 20, true);
        logo.setPadding(0, 0, 0, 0);
        TextView page = text(("Главная".equals(title) || "Репозитории".equals(title) || "Активность".equals(title)) ? "Ваш GitHub всегда рядом" : title, 13, false);
        page.setTextColor(MUTED);
        page.setPadding(0, 0, 0, 0);
        titles.addView(logo);
        titles.addView(page);
        top.addView(titles, new LinearLayout.LayoutParams(0, dp(52), 1));

        // Global search and notifications are available only in the bottom navigation.
        // No top search or bell on any screen, regardless of showSearchAction/title.

        // Account avatar opens Profile from every root tab. Profile has no
        // duplicate gear: Settings live in the fifth bottom navigation tab.
        if (!"Профиль".equals(title)) {
            ImageView account = createTopAccountIcon();
            account.setContentDescription("Личный профиль");
            account.setOnClickListener(v -> navigate(this::showProfile));
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(42),dp(42));
            ap.setMargins(dp(4),0,0,0);
            top.addView(account, ap);
        }
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(isLargeText()?76:64)));
        }

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), ("Главная".equals(title) || "Профиль".equals(title)) ? 0 : dp(10), dp(12), dp(18));
        currentScroll = new ScrollView(this);
        currentScroll.setFillViewport(true);
        currentScroll.addView(content);
        FrameLayout scrollHost = new FrameLayout(this);
        int maxContent = dp(getResources().getConfiguration().smallestScreenWidthDp >= 600 ? 900 : 1400);
        FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(Math.min(getResources().getDisplayMetrics().widthPixels, maxContent), -1, Gravity.CENTER);
        scrollHost.addView(currentScroll, scrollParams);
        root.addView(scrollHost, new LinearLayout.LayoutParams(-1, 0, 1));
        int restoreY = screenState == null ? 0 : screenState.scroll();
        if (restoreY > 0) currentScroll.postDelayed(() -> currentScroll.scrollTo(0, restoreY), 180L);

        root.addView(bottomNav(title), new LinearLayout.LayoutParams(-1, dp(isLargeText()?82:68)));
        AccessibilityAudit.prepareTree(root, dp(48));
        setContentView(root);
    }

    private int currentIconResource() {
        if ("beige".equals(activeTheme)) return app.githubble.mobile.R.mipmap.ic_launcher_beige;
        if ("dark".equals(activeTheme)) return app.githubble.mobile.R.mipmap.ic_launcher_dark;
        return app.githubble.mobile.R.mipmap.ic_launcher_light;
    }

    private LinearLayout bottomNav(String title) {
        return BottomNavigationBar.create(this,title,SURFACE,SUBTLE,GREEN,MUTED,
                new BottomNavigationBar.Actions() {
                    @Override public void openHome(){openRoot(MainActivity.this::showHome);}
                    @Override public void openRepositories(){openRoot(MainActivity.this::showMyRepositories);}
                    @Override public void openSearch(){openRoot(MainActivity.this::showSearch);}
                    @Override public void openActivity(){openRoot(MainActivity.this::showNotifications);}
                    @Override public void openSettings(){openRoot(MainActivity.this::showSettings);}
                });
    }

    private ImageView iconButton(int iconRes, String description) {
        ImageView v = imageIcon(iconRes, BLUE, 24);
        v.setContentDescription(description);
        v.setBackground(round(Color.TRANSPARENT, 18, Color.TRANSPARENT, 0));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(50), dp(50));
        p.setMargins(dp(2),0,dp(2),0);
        v.setLayoutParams(p);
        return v;
    }

    private ImageView imageIcon(int iconRes, int tint, int paddingDp) {
        ImageView v = new ImageView(this);
        v.setImageResource(iconRes);
        v.setColorFilter(tint);
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        v.setPadding(dp(paddingDp/4), dp(paddingDp/4), dp(paddingDp/4), dp(paddingDp/4));
        return v;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(TEXT);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(dp(4), dp(4), dp(4), dp(4));
        t.setLineSpacing(0f, isLargeText()?1.18f:1.08f);
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        if(Build.VERSION.SDK_INT>=29){t.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY);t.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NORMAL);}
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s); b.setAllCaps(false); b.setTextSize(14); b.setTextColor(Color.WHITE);
        b.setBackground(round(GREEN, 14, GREEN, 0));
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setContentDescription(s);
        return b;
    }

    private GradientDrawable round(int fill, int radius, int stroke, int width) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill); g.setCornerRadius(dp(radius));
        if (width > 0) g.setStroke(dp(width), stroke);
        return g;
    }

    private int blend(int base, int accent, float amount) {
        amount = Math.max(0f, Math.min(1f, amount));
        int r = (int)(Color.red(base) * (1f-amount) + Color.red(accent) * amount);
        int g = (int)(Color.green(base) * (1f-amount) + Color.green(accent) * amount);
        int b = (int)(Color.blue(base) * (1f-amount) + Color.blue(accent) * amount);
        return Color.rgb(r,g,b);
    }

    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }
    private boolean isLargeText(){return getResources().getConfiguration().fontScale>=1.25f;}
    private int adaptiveColumns(int preferred){int sw=getResources().getConfiguration().smallestScreenWidthDp;if(sw>=840)return Math.max(preferred,4);if(sw>=600)return Math.max(preferred,3);if(isLargeText())return Math.min(preferred,2);return preferred;}

    private void sectionTitle(String title, String action) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(title, 20, true); row.addView(t, new LinearLayout.LayoutParams(0, dp(48), 1));
        if (action != null) { TextView a = text(action, 14, true); a.setTextColor(BLUE); a.setGravity(Gravity.CENTER); row.addView(a); }
        content.addView(row);
    }

    private void card(View v) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        box.setBackground(round(CARD, 18, BORDER, 1));
        box.setElevation(dp(1));
        box.addView(v);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(12)); content.addView(box, p);
    }

    private void showHome() {
        rememberScreen("home",null);
        setCurrentScreen(this::showHome);
        shell("Главная", true);
        boolean signedIn = isSignedIn();
        if(signedIn) refreshHomeDashboard();
        refreshHomeRecommendations();
        HomeScreen.Palette palette = new HomeScreen.Palette(
                BG, CARD, SUBTLE, TEXT, MUTED, GREEN, BORDER, tileColors, "dark".equals(activeTheme));
        HomeScreen.Actions actions = new HomeScreen.Actions() {
            @Override public void openIssues() { navigate(MainActivity.this::showIssues); }
            @Override public void openPullRequests() { navigate(MainActivity.this::showPullRequests); }
            @Override public void openDiscussions() { navigate(MainActivity.this::showDiscussions); }
            @Override public void openRepositories() { navigate(signedIn ? MainActivity.this::showMyRepositories : () -> searchRepos("stars:>50000")); }
            @Override public void openOrganizations() { navigate(signedIn ? MainActivity.this::showOrganizations : MainActivity.this::showProfile); }
            @Override public void openProjects() { navigate(MainActivity.this::showProjects); }
            @Override public void openReleases() { navigate(MainActivity.this::showReleases); }
            @Override public void openFavorites() { navigate(MainActivity.this::showFavorites); }
            @Override public void openHistory() { navigate(signedIn ? MainActivity.this::showAccountActivity : MainActivity.this::showProfile); }
            @Override public void openTracking() { navigate(MainActivity.this::showTracking); }
            @Override public void openProfile() { navigate(MainActivity.this::showProfile); }
            @Override public void openSearch() { showQuickSearchDialog(); }
            @Override public boolean toggleRecommendedFavorite(String repo) {
                Set<String> current = new LinkedHashSet<>(prefs.getStringSet("favorites", new LinkedHashSet<>()));
                boolean saved;
                if (current.contains(repo)) { current.remove(repo); saved = false; }
                else { current.add(repo); saved = true; }
                prefs.edit().putStringSet("favorites", current).apply();
                return saved;
            }
            @Override public void openNotifications() { navigate(MainActivity.this::showNotifications); }
            @Override public ImageView profileAvatar() { return createTopAccountIcon(); }
            @Override public void openAccountCenter() { navigate(signedIn ? MainActivity.this::showAccountCenter : MainActivity.this::showProfile); }
            @Override public void openRepository(String fullName) { navigate(() -> showRepository(fullName)); }
            @Override public void openActivity() { navigate(MainActivity.this::showNotifications); }
            @Override public void openExplore() { navigate(MainActivity.this::showExplore); }
            @Override public void openAllSections() {
                String[] labels = {"Репозитории", "Pull Requests", "Релизы", "Проекты",
                        "Issues", "Обсуждения", "Организации", "Избранное", "История", "Наблюдение"};
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Все разделы GitHubble")
                    .setItems(labels, (dialog, index) -> {
                        switch (index) {
                            case 0: openRepositories(); break;
                            case 1: openPullRequests(); break;
                            case 2: openReleases(); break;
                            case 3: openProjects(); break;
                            case 4: openIssues(); break;
                            case 5: openDiscussions(); break;
                            case 6: openOrganizations(); break;
                            case 7: openFavorites(); break;
                            case 8: openHistory(); break;
                            case 9: openTracking(); break;
                            default: break;
                        }
                    }).show();
            }
            @Override public void openActivityItem(String repo, String kind, int number) {
                if ("Issue".equals(kind) && number > 0 && repo.contains("/")) navigate(() -> showIssueDetail(repo, number));
                else if ("PullRequest".equals(kind) && number > 0 && repo.contains("/")) navigate(() -> showPullDetail(repo, number));
                else if (repo.contains("/")) navigate(() -> showRepository(repo));
                else navigate(MainActivity.this::showNotifications);
            }
            @Override public void refreshDashboard() {
                prefs.edit().putLong("home_dashboard_last_refresh", 0L).apply();
                showHome();
            }
        };
        new HomeScreen(this, content, palette, signedIn, getString(R.string.stabilization_build), actions, getHomeDashboardData()).render();
    }

    private HomeScreen.DashboardData getHomeDashboardData() {
        int issues = prefs.getInt("home_open_issues", -1);
        int pullRequests = prefs.getInt("home_open_prs", -1);
        int notifications = isSignedIn() ? prefs.getInt("activity_new_count", 0) + trackingDb.unreadCount() : -1;
        List<HomeScreen.RepoPreview> recent = new ArrayList<>();
        int repositoryCount = -1;
        String accountId = currentAccountId();
        repositoryStore.migrateLegacy(accountId);
        RepositoryStore.Snapshot snapshot = repositoryStore.read(accountId);
        if (snapshot.hasData()) {
            try {
                JSONArray repos = snapshot.repositories;
                repositoryCount = repos.length();
                int max = Math.min(5, repos.length());
                for (int i = 0; i < max; i++) {
                    JSONObject repo = repos.optJSONObject(i);
                    if (repo == null) continue;
                    recent.add(new HomeScreen.RepoPreview(
                            repo.optString("full_name", ""),
                            repo.optString("description", ""),
                            repo.optInt("stargazers_count", 0),
                            formatGitHubDate(repo.optString("pushed_at", repo.optString("updated_at", "")))
                    ));
                }
            } catch (Exception ignored) {}
        }
        List<HomeScreen.ActivityPreview> activityPreview = new ArrayList<>();
        if (isSignedIn()) {
            try {
                JSONArray activity = new JSONArray(prefs.getString("home_recent_activity_" + accountId, "[]"));
                for (int i = 0; i < Math.min(3, activity.length()); i++) {
                    JSONObject event = activity.optJSONObject(i);
                    if (event == null) continue;
                    activityPreview.add(new HomeScreen.ActivityPreview(
                            event.optString("repo", ""), event.optString("title", "Событие GitHub"),
                            formatGitHubDate(event.optString("updated_at", "")),
                            event.optString("kind", "Event"), event.optInt("number", 0)));
                }
            } catch (Exception ignored) {}
        }
        List<HomeScreen.RecommendationPreview> recommended = new ArrayList<>();
        Set<String> favorites = prefs.getStringSet("favorites", new LinkedHashSet<>());
        for (String repo : HOME_RECOMMENDED_REPOS) {
            recommended.add(new HomeScreen.RecommendationPreview(repo,
                    prefs.getLong("home_rec_stars_" + repo.replace('/', '_'), -1L),
                    favorites.contains(repo)));
        }
        JSONObject trackingStats = trackingDb.statistics();
        return new HomeScreen.DashboardData(issues, pullRequests, repositoryCount, notifications,
                trackingStats.optInt("developers",0), trackingStats.optInt("repositories",0),
                trackingStats.optInt("unread",0), recent, activityPreview,
                isSignedIn() ? getProfileLogin() : "", snapshot.lastSyncedAt, recommended);
    }

    private void refreshHomeRecommendations() {
        long lastSuccess = prefs.getLong("home_recs_refresh_at", 0L);
        if (System.currentTimeMillis() - lastSuccess < 6L * 60L * 60L * 1000L) return;
        if (!homeRecommendationsRefreshInFlight.compareAndSet(false, true)) return;
        io.execute(() -> {
            boolean updated = false;
            SharedPreferences.Editor editor = prefs.edit();
            for (String repo : HOME_RECOMMENDED_REPOS) {
                try {
                    JSONObject data = getJson("https://api.github.com/repos/" + repo);
                    if (!data.has("stargazers_count") || data.isNull("stargazers_count")) continue;
                    long stars = data.optLong("stargazers_count", -1L);
                    if (stars < 0L) continue;
                    editor.putLong("home_rec_stars_" + repo.replace('/', '_'), stars);
                    updated = true;
                } catch (Exception ignored) {
                    // The last verified star count remains visible when the API is offline.
                }
            }
            editor.putLong("home_recs_refresh_at", System.currentTimeMillis()
                    - (updated ? 0L : 5L * 60L * 60L * 1000L)).apply();
            homeRecommendationsRefreshInFlight.set(false);
            if (updated) runOnUiThread(() -> {
                if ("Главная".equals(currentTitle)) showHome();
            });
        });
    }

    private void refreshHomeDashboard() {
        if (!isSignedIn()) return;
        long lastRefresh = prefs.getLong("home_dashboard_last_refresh", 0L);
        if (System.currentTimeMillis() - lastRefresh < 120000L) return;
        if (!homeDashboardRefreshInFlight.compareAndSet(false, true)) return;
        final String homeAccountId = currentAccountId();
        io.execute(() -> {
            SharedPreferences.Editor editor = prefs.edit();
            try {
                JSONArray issues = getArray("https://api.github.com/issues?filter=all&state=open&sort=updated&per_page=100");
                int count = 0;
                for (int i = 0; i < issues.length(); i++) {
                    JSONObject item = issues.optJSONObject(i);
                    if (item != null && !item.has("pull_request")) count++;
                }
                editor.putInt("home_open_issues", count);
            } catch (Exception ignored) {}
            try {
                String login = getProfileLogin();
                if (!login.isEmpty()) {
                    String q = "is:pr is:open involves:" + login;
                    JSONObject result = getJson("https://api.github.com/search/issues?q=" + URLEncoder.encode(q, "UTF-8") + "&per_page=1");
                    editor.putInt("home_open_prs", result.optInt("total_count", 0));
                }
            } catch (Exception ignored) {}
            try { repositoryStore.saveSuccess(currentAccountId(), repositoryAccess.sync(false).repositories); }
            catch (Exception error) { repositoryStore.saveError(currentAccountId(), error); }
            try {
                long seen = prefs.getLong("activity_last_seen_epoch", 0L);
                List<JSONObject> activity = loadSupportedActivityFeed();
                int fresh = 0;
                JSONArray preview = new JSONArray();
                for (int i = 0; i < activity.size(); i++) {
                    JSONObject item = activity.get(i);
                    if (parseIsoEpoch(item.optString("updated_at", "")) > seen) fresh++;
                    if (i < 3) preview.put(item);
                }
                editor.putString("home_recent_activity_" + homeAccountId, preview.toString());
                editor.putInt("activity_new_count", fresh);
            } catch (Exception ignored) {}
            editor.putLong("home_dashboard_last_refresh", System.currentTimeMillis()).apply();
            homeDashboardRefreshInFlight.set(false);
            runOnUiThread(() -> { if ("Главная".equals(currentTitle) && homeAccountId.equals(currentAccountId())) showHome(); });
        });
    }

    private View statCell(int iconRes, String value, String label, int color) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        ImageView icon = imageIcon(iconRes, color, 12);
        box.addView(icon, new LinearLayout.LayoutParams(dp(23), dp(20)));
        TextView v = text(value, 15, true);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER);
        v.setPadding(0,0,0,0);
        TextView l = text(label, 11, false);
        l.setTextColor(MUTED);
        l.setGravity(Gravity.CENTER);
        l.setPadding(0,0,0,0);
        box.addView(v);
        box.addView(l);
        return box;
    }

    private View recentRepo(String name, String description, String stars, String updated) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10),dp(8),dp(10),dp(8));
        row.setBackground(round(CARD,16,BORDER,1));
        TextView avatar = text(name.substring(0,1).toUpperCase(Locale.ROOT), 17, true);
        avatar.setGravity(Gravity.CENTER);
        avatar.setTextColor(Color.WHITE);
        int avatarColor = name.startsWith("A") ? tileColors[1] : GREEN;
        avatar.setBackground(round(avatarColor, 15, avatarColor, 0));
        row.addView(avatar,new LinearLayout.LayoutParams(dp(38),dp(38)));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(10),0,0,0);
        TextView n = text(name,14,true); n.setPadding(0,0,0,0);
        TextView d = text(description,11,false); d.setTextColor(MUTED); d.setPadding(0,0,0,0);
        info.addView(n); info.addView(d);
        row.addView(info,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.setGravity(Gravity.END);
        TextView st = text(stars,11,true); st.setTextColor(Color.rgb(222,171,25)); st.setGravity(Gravity.END); st.setPadding(0,0,0,0);
        TextView up = text(updated,9,false); up.setTextColor(MUTED); up.setGravity(Gravity.END); up.setPadding(0,0,0,0);
        meta.addView(st); meta.addView(up);
        row.addView(meta);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,dp(58));
        p.setMargins(0,0,0,dp(7));
        row.setLayoutParams(p);
        row.setOnClickListener(v -> navigate(() -> showRepository("octocat/Hello-World")));
        return row;
    }

    private void addTile(GridLayout grid, int iconRes, String title, String count, int color, Runnable action) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.START);
        tile.setPadding(dp(8),dp(7),dp(7),dp(7));
        int tintedCard = blend(CARD, color, "dark".equals(activeTheme) ? 0.14f : 0.075f);
        tile.setBackground(round(tintedCard, 17, blend(BORDER,color,0.12f), 1));
        tile.setElevation(dp(1));
        ImageView art = new ImageView(this);
        art.setImageResource(iconRes);
        art.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        tile.addView(art, new LinearLayout.LayoutParams(dp(43),dp(43)));
        TextView t = text(title, 10, true);
        t.setPadding(0,dp(3),0,0);
        t.setMaxLines(1);
        tile.addView(t);
        TextView s = text(count, 10, true);
        s.setTextColor(MUTED);
        s.setPadding(0,0,0,0);
        tile.addView(s);
        tile.setOnClickListener(v -> navigate(action));
        GridLayout.LayoutParams p = new GridLayout.LayoutParams();
        p.width = 0;
        p.height = dp(91);
        p.columnSpec = GridLayout.spec(GridLayout.UNDEFINED,1f);
        p.setMargins(dp(3),dp(3),dp(3),dp(3));
        grid.addView(tile,p);
    }

    private View smallQuick(String title, String action, Runnable run) {
        LinearLayout b = new LinearLayout(this); b.setOrientation(LinearLayout.VERTICAL); b.setPadding(dp(10),dp(12),dp(10),dp(12)); b.setGravity(Gravity.CENTER);
        b.setBackground(round(CARD, 16, BORDER, 1));
        TextView t=text(title,15,true); TextView a=text(action,12,false); a.setTextColor(BLUE); b.addView(t);b.addView(a);b.setOnClickListener(v->navigate(run));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,dp(82),1); p.setMargins(dp(4),0,dp(4),0); b.setLayoutParams(p); return b;
    }

    private void showSearch() {
        rememberScreen("search",null);
        setCurrentScreen(this::showSearch);
        shell("Поиск", false);
        HomeScreen.Palette palette = new HomeScreen.Palette(
                BG, CARD, SUBTLE, TEXT, MUTED, GREEN, BORDER, tileColors, "dark".equals(activeTheme));
        new SearchScreen(this, content, palette, new SearchScreen.Actions() {
            @Override public void search(String query, boolean smart) { navigate(() -> searchRepos(query, smart)); }
            @Override public void dictate(EditText target, ImageView microphone) { toggleVoiceInput(target,microphone); }
            @Override public void chooseDictationLanguage() { showVoiceLanguageChooser(); }
            @Override public void searchAdvanced(String query, boolean smart, SearchMode mode, SearchFilters filters) {
                navigate(() -> MainActivity.this.searchAdvanced(query, smart, mode, filters));
            }
        }).render();

    }

    private void addChip(GridLayout g,String label,Runnable r){ TextView v=text(label,14,true);v.setTextColor(BLUE);v.setGravity(Gravity.CENTER);v.setBackground(round(CARD,14,BORDER,1));v.setOnClickListener(x->navigate(r));GridLayout.LayoutParams p=new GridLayout.LayoutParams();p.width=0;p.height=dp(48);p.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);p.setMargins(dp(4),dp(4),dp(4),dp(4));g.addView(v,p); }

    private void showExplore(){
        setCurrentScreen(this::showExplore);
        shell("Обзор",true);
        heroBanner("Открывайте новые проекты", "Подборки по технологиям, популярности и активности", R.drawable.ic_explore);
        sectionTitle("Популярные направления",null);
        addDirectionRow(R.drawable.ic_android,"Android","Мобильные приложения, библиотеки и инструменты",tileColors[1],()->searchRepos("android stars:>10000"));
        addDirectionRow(R.drawable.ic_kotlin,"Kotlin","Современные JVM- и Android-проекты",tileColors[2],()->searchRepos("language:kotlin stars:>5000"));
        addDirectionRow(R.drawable.ic_ai,"Искусственный интеллект","Модели, агенты и инструменты машинного обучения",tileColors[4],()->searchRepos("topic:artificial-intelligence"));
        addDirectionRow(R.drawable.ic_tools,"Инструменты разработчика","CLI, IDE, автоматизация и DevOps",tileColors[6],()->searchRepos("topic:developer-tools"));
    }
    private void showNotifications(){
        setCurrentScreen(this::showNotifications);
        shell("Активность",false);
        if(!isSignedIn()){
            app.githubble.mobile.ui.ActivityScreen guest =
                    new app.githubble.mobile.ui.ActivityScreen(this,content,
                            activityActions(),CARD,TEXT,MUTED,GREEN,BORDER,SUBTLE,
                            "dark".equals(activeTheme));
            guest.render(new ArrayList<>(),"Подключите GitHub, чтобы видеть события аккаунта.");
            accountRequiredCard("Войдите в GitHub, чтобы загрузить задачи, Pull Requests и публичную активность.");
            return;
        }
        final long generation=screenGeneration.get();
        final String account=currentAccountId();
        List<JSONObject> cached=readCachedActivity(account);
        RepositoryStore.Snapshot initialRepos=repositoryStore.read(account);
        app.githubble.mobile.ui.ActivityScreen screen =
                new app.githubble.mobile.ui.ActivityScreen(this,content,activityActions(),
                        CARD,TEXT,MUTED,GREEN,BORDER,SUBTLE,"dark".equals(activeTheme));
        screen.render(cached,"Загружаем коммиты и изменения. Private-репозитории проверяются через GitHub API…");
        screen.setRankedRepositories(RepositoryActivityFeed.rank(initialRepos.repositories,
                cached,prefs,account));
        io.execute(()->{
            try{
                RepositoryStore.Snapshot repositories=repositoryStore.read(account);
                String repositoryWarning="";
                if(repositories.lastSyncedAt==0L
                        ||System.currentTimeMillis()-repositories.lastSyncedAt>120000L){
                    try{
                        RepositoryAccessController.SyncResult synced=repositoryAccess.sync(true);
                        if(!account.equals(currentAccountId()))return;
                        repositories=repositoryStore.saveSuccess(account,synced.repositories);
                        repositoryWarning=synced.warning;
                    }catch(Exception syncError){
                        repositoryWarning="Список проектов обновить не удалось; используем сохранённый. "
                                +syncError.getMessage();
                    }
                }
                RepositoryActivityFeed.CommitResult commits=
                        RepositoryActivityFeed.loadCommits(api,repositories.repositories,prefs,account);
                List<JSONObject> related=new ArrayList<>();
                String relatedWarning="";
                try{
                    related=loadSupportedActivityFeed();
                }catch(Exception relatedError){
                    relatedWarning="Задачи/PR/публичные события не загрузились: "
                            +relatedError.getMessage();
                }
                List<JSONObject> loaded=mergeActivityEvents(commits.events,related);
                if(!account.equals(currentAccountId()))return;
                JSONArray cache=new JSONArray();
                for(JSONObject event:loaded)cache.put(event);
                prefs.edit().putString("activity_feed_cache_"+account,cache.toString())
                        .putLong("activity_feed_refresh_"+account,System.currentTimeMillis())
                        .putLong("activity_last_seen_epoch",System.currentTimeMillis())
                        .putInt("activity_new_count",0).apply();
                List<JSONObject> ranking=RepositoryActivityFeed.rank(
                        repositories.repositories,loaded,prefs,account);
                String coverage=commits.coverageLabel();
                if(!relatedWarning.isEmpty())coverage+=". "+relatedWarning;
                if(!repositoryWarning.isEmpty())coverage+=". "+repositoryWarning;
                if(repositories.repositories.length()==0)
                    coverage+=". Список репозиториев пуст: проверьте установку и Contents: Read у GitHub App.";
                final String finalCoverage=coverage;
                runOnUiThread(()->{
                    if(generation!=screenGeneration.get()||
                            !account.equals(currentAccountId())||
                            !"Активность".equals(currentTitle))return;
                    screen.setRankedRepositories(ranking);
                    screen.setFeed(loaded);
                    screen.setStatus(finalCoverage);
                });
            }catch(Exception error){
                runOnUiThread(()->{
                    if(generation!=screenGeneration.get()||
                            !account.equals(currentAccountId())||
                            !"Активность".equals(currentTitle))return;
                    screen.setStatus(cached.isEmpty()
                            ?"Не удалось загрузить события: "+error.getMessage()
                            :"Новые события временно недоступны. Показана сохранённая лента: "
                                +error.getMessage());
                });
            }
        });
    }

    private app.githubble.mobile.ui.ActivityScreen.Actions activityActions(){
        return new app.githubble.mobile.ui.ActivityScreen.Actions(){
            @Override public void openEvent(JSONObject event){
                String repo=event.optString("repo","");
                String kind=event.optString("kind","Event");
                String eventType=event.optString("event_type","");
                int number=event.optInt("number",0);
                if(!repo.contains("/"))return;
                String sha=event.optString("sha","");
                if("Commit".equals(kind)&&CommitDetailController.validSha(sha)){
                    navigate(()->showCommitDetail(repo,sha));
                    return;
                }
                if(("Issue".equals(kind)||"IssuesEvent".equals(eventType))&&number>0)
                    navigate(()->showIssueDetail(repo,number));
                else if(("PullRequest".equals(kind)||eventType.startsWith("PullRequest"))&&number>0)
                    navigate(()->showPullDetail(repo,number));
                else if(eventType.contains("Discussion")&&number>0)
                    navigate(()->showDiscussionDetail(repo,number));
                else navigate(()->showRepository(repo));
            }
            @Override public void refresh(){showNotifications();}
            @Override public void openGitHubNotifications(){
                navigate(MainActivity.this::showGitHubInbox);
            }
            @Override public void openBuilds(){navigate(MainActivity.this::showBuildCenter);}
            @Override public void openRepository(String repository){
                if(repository!=null&&repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))
                    navigate(()->showRepository(repository));
            }
        };
    }

    private List<JSONObject> readCachedActivity(String account){
        List<JSONObject> cached=new ArrayList<>();
        if(account==null||account.isEmpty())return cached;
        try{
            JSONArray data=new JSONArray(prefs.getString("activity_feed_cache_"+account,"[]"));
            for(int i=0;i<Math.min(RepositoryActivityFeed.MAX_TIMELINE,data.length());i++){
                JSONObject event=data.optJSONObject(i);
                if(event!=null)cached.add(event);
            }
        }catch(Exception ignored){}
        return cached;
    }

    /**
     * Real, authenticated commits are merged with issues/PR and public GitHub
     * events. PushEvent is omitted for repos whose actual individual commits
     * were retrieved, preventing duplicate, summary-only cards.
     */
    private List<JSONObject> mergeActivityEvents(List<JSONObject> commits,
                                                  List<JSONObject> other){
        List<JSONObject> combined=new ArrayList<>(commits);
        Set<String> repositoriesWithCommits=new LinkedHashSet<>();
        for(JSONObject item:commits)repositoriesWithCommits.add(item.optString("repo",""));
        for(JSONObject item:other){
            if("PushEvent".equals(item.optString("event_type",""))
                    &&repositoriesWithCommits.contains(item.optString("repo","")))continue;
            combined.add(item);
        }
        combined.sort((a,b)->Long.compare(
                RepositoryActivityFeed.epoch(b.optString("updated_at","")),
                RepositoryActivityFeed.epoch(a.optString("updated_at",""))));
        List<JSONObject> output=new ArrayList<>();
        Set<String> seen=new LinkedHashSet<>();
        for(JSONObject item:combined){
            String id=item.optString("event_id","");
            if(id.isEmpty())id=item.optString("repo","")+"|"+item.optString("kind","")
                    +"|"+item.optString("updated_at","")+"|"+item.optString("title","");
            if(seen.add(id))output.add(item);
            if(output.size()>=RepositoryActivityFeed.MAX_TIMELINE)break;
        }
        return output;
    }

    private List<JSONObject> loadSupportedActivityFeed() throws Exception {
        List<JSONObject> feed = new ArrayList<>();
        Exception firstError = null;
        try {
            JSONArray issues=getArray("https://api.github.com/issues?filter=all&state=open&sort=updated&per_page=35");
            for(int i=0;i<issues.length();i++){
                JSONObject source=issues.optJSONObject(i);if(source==null||source.has("pull_request"))continue;
                JSONObject item=new JSONObject();
                item.put("kind","Issue");item.put("title",source.optString("title","Issue"));
                item.put("repo",repoFromApiUrl(source.optString("repository_url","")));
                item.put("number",source.optInt("number"));item.put("updated_at",source.optString("updated_at",""));
                JSONObject user=source.optJSONObject("user");item.put("author",user==null?"":user.optString("login",""));
                feed.add(item);
            }
        } catch(Exception e){firstError=e;}

        String login=getProfileLogin();
        if(!login.isEmpty()){
            try{
                String q="is:pr is:open involves:"+login;
                JSONObject result=getJson("https://api.github.com/search/issues?q="+URLEncoder.encode(q,"UTF-8")+"&sort=updated&per_page=35");
                JSONArray prs=result.optJSONArray("items");if(prs!=null)for(int i=0;i<prs.length();i++){
                    JSONObject source=prs.optJSONObject(i);if(source==null)continue;
                    JSONObject item=new JSONObject();
                    item.put("kind","PullRequest");item.put("title",source.optString("title","Pull Request"));
                    item.put("repo",repoFromApiUrl(source.optString("repository_url","")));
                    item.put("number",source.optInt("number"));item.put("updated_at",source.optString("updated_at",""));
                    JSONObject user=source.optJSONObject("user");item.put("author",user==null?"":user.optString("login",""));
                    feed.add(item);
                }
            }catch(Exception e){if(firstError==null)firstError=e;}
            try{
                // /users/{login}/events contains public events only, not a private Inbox.
                JSONArray events=getArray("https://api.github.com/users/"+
                        URLEncoder.encode(login,"UTF-8")+"/events?per_page=35");
                for(int i=0;i<events.length();i++){
                    JSONObject source=events.optJSONObject(i);
                    if(source==null)continue;
                    JSONObject repository=source.optJSONObject("repo");
                    JSONObject payload=source.optJSONObject("payload");
                    String type=source.optString("type","Event");
                    JSONObject item=new JSONObject();
                    item.put("kind","Event");
                    item.put("event_type",type);
                    item.put("title",eventTypeLabel(type));
                    item.put("repo",repository==null?"":repository.optString("name",""));
                    item.put("updated_at",source.optString("created_at",""));
                    item.put("event_id",source.optString("id",""));
                    if(payload!=null){
                        String action=payload.optString("action","");
                        JSONObject subject;
                        if("PushEvent".equals(type)){
                            item.put("kind","Commit");
                            String branch=payload.optString("ref","").replace("refs/heads/","");
                            item.put("title",branch.isEmpty()?"Отправлен коммит":
                                    "Новый коммит в ветке "+branch);
                            JSONArray commits=payload.optJSONArray("commits");
                            if(commits!=null&&commits.length()>0){
                                JSONObject commit=commits.optJSONObject(0);
                                if(commit!=null)item.put("summary",commit.optString("message",""));
                            }
                        }else if("ReleaseEvent".equals(type)){
                            item.put("kind","Release");
                            subject=payload.optJSONObject("release");
                            if(subject!=null)item.put("title","Опубликован релиз "+
                                    subject.optString("tag_name",""));
                            item.put("summary",action);
                        }else if("IssuesEvent".equals(type)||"IssueCommentEvent".equals(type)){
                            subject=payload.optJSONObject("issue");
                            if(subject!=null){
                                item.put("kind",subject.has("pull_request")?"PullRequest":"Issue");
                                item.put("number",subject.optInt("number",0));
                                item.put("title",("closed".equals(action)?"Закрыта задача":
                                        "opened".equals(action)?"Открыта задача":
                                        "IssueCommentEvent".equals(type)?"Новый комментарий":"Обновлена задача")
                                        +" #"+subject.optInt("number",0));
                                item.put("summary",subject.optString("title",""));
                            }
                        }else if(type.startsWith("PullRequest")){
                            item.put("kind","PullRequest");
                            subject=payload.optJSONObject("pull_request");
                            if(subject!=null){
                                item.put("number",subject.optInt("number",payload.optInt("number",0)));
                                item.put("title",("closed".equals(action)?"Закрыт Pull Request":
                                        "opened".equals(action)?"Открыт Pull Request":
                                        "Обновлён Pull Request")+" #"+item.optInt("number"));
                                item.put("summary",subject.optString("title",""));
                            }
                        }else if(type.contains("Discussion")){
                            item.put("kind","Discussion");
                            subject=payload.optJSONObject("discussion");
                            if(subject!=null){
                                item.put("number",subject.optInt("number",0));
                                item.put("summary",subject.optString("title",""));
                            }
                        }
                    }
                    feed.add(item);
                }
            }catch(Exception e){if(firstError==null)firstError=e;}
        }
        if(feed.isEmpty()&&firstError!=null)throw firstError;
        feed.sort((a,b)->Long.compare(parseIsoEpoch(b.optString("updated_at","")),parseIsoEpoch(a.optString("updated_at",""))));
        List<JSONObject> unique=new ArrayList<>();
        Set<String> seenEvents=new LinkedHashSet<>();
        for(JSONObject item:feed){
            String id=item.optString("event_id","");
            String key=id.isEmpty()?
                    item.optString("kind","")+"|"+item.optString("repo","")+"|"+
                    item.optInt("number",0)+"|"+item.optString("updated_at",""):
                    "github-event:"+id;
            if(seenEvents.add(key))unique.add(item);
            if(unique.size()>=60)break;
        }
        return unique;
    }

    private void renderSupportedActivityCard(JSONObject item){
        String kind=item.optString("kind","Event");
        String title=item.optString("title","Событие GitHub");
        String repo=item.optString("repo","GitHub");
        String author=item.optString("author","");
        String updated=formatGitHubDate(item.optString("updated_at",""));
        int number=item.optInt("number",0);
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon=text("Issue".equals(kind)?"●":"PullRequest".equals(kind)?"◆":"•",18,true);
        icon.setTextColor("Issue".equals(kind)?tileColors[0]:"PullRequest".equals(kind)?tileColors[1]:GREEN);
        icon.setGravity(Gravity.CENTER);head.addView(icon,new LinearLayout.LayoutParams(dp(30),dp(34)));
        TextView heading=text((number>0?"#"+number+"  ":"")+title,14,true);heading.setMaxLines(3);head.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        TextView label=text("PullRequest".equals(kind)?"PR":"Issue".equals(kind)?"Issue":"Событие",10,true);label.setTextColor(BLUE);head.addView(label);
        row.addView(head);
        String meta=repo+(author.isEmpty()?"":" · "+author)+(updated.isEmpty()?"":" · "+updated);
        TextView m=text(meta,11,false);m.setTextColor(MUTED);m.setPadding(dp(30),dp(3),0,0);row.addView(m);
        row.setOnClickListener(v->{if("Issue".equals(kind)&&number>0)navigate(()->showIssueDetail(repo,number));else if("PullRequest".equals(kind)&&number>0)navigate(()->showPullDetail(repo,number));else if(repo.contains("/"))navigate(()->showRepository(repo));});
        card(row);
    }

    private int getUnreadNotificationCount() {
        return prefs.getInt("activity_new_count",0) + (trackingDb == null ? 0 : trackingDb.unreadCount());
    }

    private void refreshNotificationCount(){
        if(!isSignedIn()||!notificationRefreshInFlight.compareAndSet(false,true))return;
        io.execute(() -> {
            try{
                long seen=prefs.getLong("activity_last_seen_epoch",0L);
                List<JSONObject> feed=loadSupportedActivityFeed();
                int fresh=0;for(JSONObject item:feed)if(parseIsoEpoch(item.optString("updated_at",""))>seen)fresh++;
                prefs.edit().putInt("activity_new_count",fresh).apply();
            }catch(Exception ignored){}finally{notificationRefreshInFlight.set(false);}
        });
    }

    private void showQuickSearchDialog() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Репозиторий, пользователь или технология");
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setPadding(dp(14), 0, dp(56), 0);
        input.setBackground(round(CARD, 14, BORDER, 1));
        FrameLayout holder = new FrameLayout(this);
        holder.setPadding(dp(18), dp(4), dp(18), 0);
        holder.addView(input, new FrameLayout.LayoutParams(-1, dp(54)));
        ImageView quickMic=new ImageView(this);
        quickMic.setImageResource(R.drawable.ic_mic);
        quickMic.setContentDescription("Голосовой поиск; долгое нажатие — выбор языка");
        quickMic.setPadding(dp(12),dp(12),dp(12),dp(12));
        quickMic.setOnClickListener(v->toggleVoiceInput(input,quickMic));
        quickMic.setOnLongClickListener(v->{showVoiceLanguageChooser();return true;});
        FrameLayout.LayoutParams micP=new FrameLayout.LayoutParams(dp(50),dp(54),Gravity.END|Gravity.TOP);
        micP.setMargins(0,dp(4),dp(18),0);holder.addView(quickMic,micP);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Быстрый поиск")
                .setMessage("Для истории, фильтров и подборок откройте нижний раздел «Поиск».")
                .setView(holder)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Найти", (dialog, which) -> {
                    String query = input.getText().toString().trim();
                    if (!query.isEmpty()) {
                        if (query.startsWith("@") || query.contains("github.com/")) navigate(() -> showDeveloperProfile(query));
                        else navigate(() -> searchRepos(query));
                    }
                })
                .show();
    }


    private void showVoiceLanguageChooser(){
        final String[] labels={"Русский (ru-RU)","English (en-US)"};
        String lang=prefs.getString("voice_search_language",
                "ru".equalsIgnoreCase(Locale.getDefault().getLanguage())?"ru-RU":"en-US");
        new AlertDialog.Builder(this).setTitle("Язык голосового ввода")
                .setSingleChoiceItems(labels,"en-US".equals(lang)?1:0,(dialog,index)->{
                    prefs.edit().putString("voice_search_language",index==0?"ru-RU":"en-US").apply();
                    dialog.dismiss();
                    toast(index==0?"Голосовой ввод: русский":"Voice input: English");
                })
                .setNegativeButton("Отмена",null).show();
    }

    private void toggleVoiceInput(EditText target,ImageView microphone){
        if(voiceRecognizer!=null){
            if(voiceTarget==target&&voiceRecording){
                voiceRecording=false;voiceWaiting=true;
                microphone.setImageResource(R.drawable.ic_mic);
                microphone.setContentDescription("Распознаём речь…");
                try{voiceRecognizer.stopListening();}catch(Exception e){finishVoiceInput("Не удалось остановить запись");}
            }else if(voiceWaiting){toast("Распознаём запись, подождите…");}
            else{cancelVoiceInput();}
            return;
        }
        voiceTarget=target;voiceButton=microphone;
        if(android.os.Build.VERSION.SDK_INT>=23
                &&checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQUEST_VOICE_AUDIO);
            return;
        }
        beginVoiceInput();
    }

    private void beginVoiceInput(){
        final EditText target=voiceTarget;
        final ImageView button=voiceButton;
        if(target==null||button==null||!target.isAttachedToWindow())return;
        if(!SpeechRecognizer.isRecognitionAvailable(this)){
            toast("На устройстве нет службы распознавания речи. Установите или включите системный голосовой ввод.");
            finishVoiceInput(null);return;
        }
        try{
            voiceRecognizer=SpeechRecognizer.createSpeechRecognizer(this);
            voiceRecognizer.setRecognitionListener(new RecognitionListener(){
                @Override public void onReadyForSpeech(Bundle params){toast("Говорите; нажмите красную кнопку, чтобы остановить");}
                @Override public void onBeginningOfSpeech(){}
                @Override public void onRmsChanged(float rmsdB){}
                @Override public void onBufferReceived(byte[] buffer){}
                @Override public void onEndOfSpeech(){
                    voiceRecording=false;voiceWaiting=true;
                    if(voiceButton==button&&button.isAttachedToWindow()){
                        button.setImageResource(R.drawable.ic_mic);
                        button.setContentDescription("Распознаём речь…");
                    }
                }
                @Override public void onError(int error){
                    String message=error==SpeechRecognizer.ERROR_NO_MATCH?"Речь не распознана — попробуйте ещё раз":
                            error==SpeechRecognizer.ERROR_SPEECH_TIMEOUT?"Речь не обнаружена":
                            error==SpeechRecognizer.ERROR_NETWORK||error==SpeechRecognizer.ERROR_NETWORK_TIMEOUT?
                                    "Ошибка сети голосового распознавания":
                            error==SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS?"Нет доступа к микрофону":
                            error==SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED||error==SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE?
                                    "Язык недоступен; удерживайте микрофон и выберите другой":
                                    "Голосовой ввод недоступен (код "+error+")";
                    finishVoiceInput(message);
                }
                @Override public void onResults(Bundle results){
                    ArrayList<String> matches=results==null?null:
                            results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if(matches!=null&&!matches.isEmpty()&&target.isAttachedToWindow()
                            &&voiceTarget==target){
                        String recognized=matches.get(0).trim();
                        if(!recognized.isEmpty()){
                            target.setText(recognized);target.setSelection(recognized.length());
                            toast("Распознано — проверьте запрос и нажмите «Найти»");
                        }
                    }else toast("Ничего не распознано");
                    finishVoiceInput(null);
                }
                @Override public void onPartialResults(Bundle partialResults){}
                @Override public void onEvent(int eventType,Bundle params){}
            });
            Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            String language=prefs.getString("voice_search_language",
                    "ru".equalsIgnoreCase(Locale.getDefault().getLanguage())?"ru-RU":"en-US");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,language);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,language);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,3);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false);
            if(Build.VERSION.SDK_INT>=34){
                intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION,true);
                intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH,
                        RecognizerIntent.LANGUAGE_SWITCH_BALANCED);
                intent.putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                        new ArrayList<>(Arrays.asList("ru-RU","en-US")));
            }
            voiceRecording=true;voiceWaiting=false;
            button.setImageResource(R.drawable.ic_mic_stop);
            button.setContentDescription("Остановить запись речи");
            voiceRecognizer.startListening(intent);
        }catch(Exception e){finishVoiceInput("Не удалось запустить голосовой ввод: "+e.getMessage());}
    }

    private void finishVoiceInput(String message){
        if(voiceRecognizer!=null){try{voiceRecognizer.destroy();}catch(Exception ignored){}}
        voiceRecognizer=null;voiceRecording=false;voiceWaiting=false;
        if(voiceButton!=null){
            voiceButton.setImageResource(R.drawable.ic_mic);
            voiceButton.setContentDescription("Начать голосовой ввод; удерживайте для выбора языка");
        }
        voiceTarget=null;voiceButton=null;
        if(message!=null&&!message.isEmpty())toast(message);
    }

    private void cancelVoiceInput(){finishVoiceInput(null);}

    private void addDirectionRow(int iconRes, String title, String subtitle, int accent, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(round(CARD, 17, blend(BORDER, accent, 0.12f), 1));
        ImageView icon = imageIcon(iconRes, accent, 16);
        icon.setBackground(round(blend(CARD, accent, "dark".equals(activeTheme) ? 0.22f : 0.10f), 14, Color.TRANSPARENT, 0));
        row.addView(icon, new LinearLayout.LayoutParams(dp(50), dp(50)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(12), 0, dp(8), 0);
        TextView heading = text(title, 15, true);
        heading.setPadding(0,0,0,0);
        TextView description = text(subtitle, 12, false);
        description.setTextColor(MUTED);
        description.setPadding(0,dp(2),0,0);
        labels.addView(heading);
        labels.addView(description);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = text("›", 28, false);
        arrow.setTextColor(MUTED);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(24), dp(48)));
        row.setOnClickListener(v -> navigate(action));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(76));
        params.setMargins(0, 0, 0, dp(7));
        content.addView(row, params);
    }

    private void heroBanner(String title, String subtitle, int iconRes){
        LinearLayout box=new LinearLayout(this); box.setGravity(Gravity.CENTER_VERTICAL); box.setPadding(dp(16),dp(14),dp(16),dp(14));
        box.setBackground(round(SUBTLE,20,BORDER,1));
        ImageView icon=imageIcon(iconRes, Color.WHITE, 12); icon.setBackground(round(GREEN,16,GREEN,0));
        box.addView(icon,new LinearLayout.LayoutParams(dp(52),dp(52)));
        LinearLayout words=new LinearLayout(this); words.setOrientation(LinearLayout.VERTICAL); words.setPadding(dp(13),0,0,0);
        TextView h=text(title,19,true); h.setPadding(0,0,0,0); words.addView(h);
        TextView d=text(subtitle,12,false); d.setTextColor(MUTED); d.setPadding(0,dp(3),0,0); words.addView(d);
        box.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,0,0,dp(12)); content.addView(box,p);
    }

    private void eventCard(String title,String source,String time,int color){
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(12),dp(10),dp(12),dp(10)); row.setBackground(round(CARD,17,BORDER,1));
        TextView dot=text("•",28,true); dot.setTextColor(color); dot.setGravity(Gravity.CENTER); row.addView(dot,new LinearLayout.LayoutParams(dp(38),dp(46)));
        LinearLayout info=new LinearLayout(this); info.setOrientation(LinearLayout.VERTICAL);
        TextView h=text(title,14,true); h.setPadding(0,0,0,0); info.addView(h);
        TextView s=text(source,11,false); s.setTextColor(MUTED); s.setPadding(0,dp(2),0,0); info.addView(s);
        row.addView(info,new LinearLayout.LayoutParams(0,-2,1));
        TextView tm=text(time,10,false); tm.setTextColor(MUTED); tm.setGravity(Gravity.END); row.addView(tm);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(68)); p.setMargins(0,0,0,dp(7)); content.addView(row,p);
    }

    private void showIssues(){showIssuesPage(1,true);}
    private void showIssuesPage(int page,boolean first){
        if(first){setCurrentScreen(this::showIssues);shell("Задачи",true);heroBanner("Мои задачи","Issues из всех доступных вам репозиториев",R.drawable.ic_issue);if(!isSignedIn()){accountRequiredCard("Для персонального списка задач подключите GitHub.");return;}}
        ProgressBar pb=new ProgressBar(this);content.addView(pb);io.execute(()->{try{JSONArray items=getArray("https://api.github.com/issues?filter=all&state=open&sort=updated&per_page=50&page="+page);runOnUiThread(()->{if(!"Задачи".equals(currentTitle))return;content.removeView(pb);int count=0;for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i);if(item!=null&&!item.has("pull_request"))count++;}if(first)sectionTitle("Открытые задачи",null);if(count==0&&first){card(text("Открытых задач для вашего аккаунта не найдено.",14,false));return;}for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i);if(item==null||item.has("pull_request"))continue;renderGlobalIssueCard(item,false);}if(items.length()==50)addLoadMoreButton("Показать ещё задачи",()->showIssuesPage(page+1,false));});}catch(Exception e){runOnUiThread(()->{content.removeView(pb);errorCard("Не удалось загрузить задачи",e.getMessage());});}});
    }

    private void showPullRequests(){showPullRequestsPage(1,true);}
    private void showPullRequestsPage(int page,boolean first){
        if(first){setCurrentScreen(this::showPullRequests);shell("Pull Requests",true);heroBanner("Мои Pull Requests","Открытые PR, в которых вы участвуете",R.drawable.ic_pull_request);if(!isSignedIn()){accountRequiredCard("Для персонального списка Pull Requests подключите GitHub.");return;}}
        String login=getProfileLogin();if(login.isEmpty()){if(first){card(text("Обновляем профиль GitHub…",13,false));refreshProfileData(true);}return;}ProgressBar pb=new ProgressBar(this);content.addView(pb);
        io.execute(()->{try{String q="is:pr is:open involves:"+login;JSONObject root=getJson("https://api.github.com/search/issues?q="+URLEncoder.encode(q,"UTF-8")+"&sort=updated&per_page=50&page="+page);JSONArray items=root.optJSONArray("items");if(items==null)items=new JSONArray();JSONArray finalItems=items;runOnUiThread(()->{if(!"Pull Requests".equals(currentTitle))return;content.removeView(pb);if(first)sectionTitle("Открытые Pull Requests",null);if(finalItems.length()==0&&first){card(text("Открытых Pull Requests с вашим участием не найдено.",14,false));return;}for(int i=0;i<finalItems.length();i++)renderGlobalIssueCard(finalItems.optJSONObject(i),true);if(finalItems.length()==50)addLoadMoreButton("Показать ещё Pull Requests",()->showPullRequestsPage(page+1,false));});}catch(Exception e){runOnUiThread(()->{content.removeView(pb);errorCard("Не удалось загрузить Pull Requests",e.getMessage());});}});
    }

    private void renderGlobalIssueCard(JSONObject item,boolean pullRequest){
        if(item==null)return;
        String full=repoFromApiUrl(item.optString("repository_url",""));int number=item.optInt("number");String title=item.optString("title","Без названия");
        JSONObject user=item.optJSONObject("user");String author=user==null?"":user.optString("login","");String updated=formatGitHubDate(item.optString("updated_at",""));
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);
        TextView heading=text("#"+number+"  "+title,15,true);heading.setMaxLines(3);row.addView(heading);
        TextView meta=text(full+" · "+author+(updated.isEmpty()?"":" · "+updated),11,false);meta.setTextColor(MUTED);row.addView(meta);
        row.setOnClickListener(v->{if(pullRequest)navigate(() -> showPullDetail(full,number));else navigate(() -> showIssueDetail(full,number));});card(row);
    }

    private String repoFromApiUrl(String url){String marker="/repos/";int i=url.indexOf(marker);return i>=0?url.substring(i+marker.length()):"";}



    /** Native GitHub notifications; requires Notifications: Read on the current token. */
    private void showGitHubInbox(){
        setCurrentScreen(this::showGitHubInbox);
        shell("Уведомления GitHub",true);
        heroBanner("GitHub Inbox","Непрочитанные уведомления",R.drawable.ic_bell);
        if(!isSignedIn()){accountRequiredCard("Для просмотра уведомлений войдите в GitHub.");return;}
        final long generation=screenGeneration.get();
        final String account=currentAccountId();
        final LinearLayout destination=content;
        Button refresh=compactButton("Обновить");
        refresh.setOnClickListener(v->showGitHubInbox());
        destination.addView(refresh,new LinearLayout.LayoutParams(-1,dp(48)));
        ProgressBar loading=new ProgressBar(this);destination.addView(loading);
        io.execute(()->{
            try{
                JSONArray notifications=getArray("https://api.github.com/notifications?all=false&per_page=50");
                runOnUiThread(()->{
                    if(generation!=screenGeneration.get()||destination!=content
                            ||!account.equals(currentAccountId()))return;
                    destination.removeView(loading);
                    if(notifications.length()==0){card(text("Непрочитанных уведомлений нет.",13,false));return;}
                    sectionTitle("Непрочитанные · "+notifications.length(),null);
                    for(int i=0;i<notifications.length();i++){
                        JSONObject item=notifications.optJSONObject(i);if(item==null)continue;
                        JSONObject repository=item.optJSONObject("repository");
                        JSONObject subject=item.optJSONObject("subject");
                        if(repository==null||subject==null)continue;
                        String full=repository.optString("full_name","");
                        String type=subject.optString("type","");
                        String url=subject.optString("url","");
                        String thread=item.optString("id","");
                        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);
                        row.addView(text(subject.optString("title","Уведомление GitHub"),14,true));
                        TextView meta=text(full+" · "+type+" · "+formatGitHubDate(item.optString("updated_at","")),11,false);
                        meta.setTextColor(MUTED);row.addView(meta);
                        row.setOnClickListener(v->{
                            int marker=url.lastIndexOf("/"+("PullRequest".equals(type)?"pulls/":"issues/"));
                            int number=0;
                            if(marker>=0)try{
                                number=Integer.parseInt(url.substring(url.lastIndexOf('/')+1));
                            }catch(Exception ignored){}
                            if(CommitDetailController.validRepository(full)){
                                final int selected=number;
                                if(selected>0&&"PullRequest".equals(type))navigate(()->showPullDetail(full,selected));
                                else if(selected>0&&"Issue".equals(type))navigate(()->showIssueDetail(full,selected));
                                else navigate(()->showRepository(full));
                            }
                            if(thread.matches("[0-9]+"))io.execute(()->{
                                try{api.executeJson("PATCH","https://api.github.com/notifications/threads/"+thread,
                                        new JSONObject(),true);}catch(Exception ignored){}
                            });
                        });
                        card(row);
                    }
                });
            }catch(Exception error){
                runOnUiThread(()->{
                    if(generation!=screenGeneration.get()||destination!=content
                            ||!account.equals(currentAccountId()))return;
                    destination.removeView(loading);
                    errorCard("Не удалось загрузить уведомления",error.getMessage());
                    card(text("Проверьте разрешение Notifications: Read для GitHub App.",12,false));
                });
            }
        });
    }

    /** Native Actions run and job details; browser is optional for raw log files. */
    private void showBuildRunDetail(String full,long runId){
        if(!CommitDetailController.validRepository(full)||runId<=0){
            errorDialog("Запуск","Некорректный репозиторий или номер запуска.");return;
        }
        setCurrentScreen(()->showBuildRunDetail(full,runId));
        shell("Запуск Actions",false);
        heroBanner(full,"GitHub Actions · #"+runId,R.drawable.ic_android);
        if(!isSignedIn()){accountRequiredCard("Подключите GitHub.");return;}
        final long generation=screenGeneration.get();
        final String account=currentAccountId();
        final LinearLayout destination=content;
        ProgressBar loading=new ProgressBar(this);destination.addView(loading);
        io.execute(()->{
            try{
                app.githubble.mobile.build.BuildRunDetailController.Detail details=
                        new app.githubble.mobile.build.BuildRunDetailController(api).load(full,runId);
                runOnUiThread(()->{
                    if(generation!=screenGeneration.get()||destination!=content
                            ||!account.equals(currentAccountId()))return;
                    destination.removeView(loading);
                    JSONObject run=details.run;
                    card(text(run.optString("name","GitHub Actions"),17,true));
                    card(text("Статус: "+run.optString("status","")+" · "+
                            run.optString("conclusion","")+"\nВетка: "+
                            run.optString("head_branch","")+" · "+
                            shortSha(run.optString("head_sha",""))+"\nЗапуск: "+
                            formatGitHubDate(run.optString("created_at","")),12,false));
                    sectionTitle("Задания · "+details.jobs.length(),null);
                    for(int i=0;i<details.jobs.length();i++){
                        JSONObject job=details.jobs.optJSONObject(i);if(job==null)continue;
                        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);
                        row.addView(text(job.optString("name","Job")+" · "+
                                job.optString("conclusion",job.optString("status","")),14,true));
                        JSONArray steps=job.optJSONArray("steps");
                        if(steps!=null)for(int k=0;k<steps.length();k++){
                            JSONObject step=steps.optJSONObject(k);if(step==null)continue;
                            TextView line=text("  "+(k+1)+". "+step.optString("name","")+" · "+
                                    step.optString("conclusion",step.optString("status","")),11,false);
                            line.setTextColor(MUTED);row.addView(line);
                        }
                        card(row);
                    }
                    sectionTitle("Артефакты · "+details.artifacts.length(),null);
                    for(int i=0;i<details.artifacts.length();i++){
                        JSONObject artifact=details.artifacts.optJSONObject(i);if(artifact==null)continue;
                        Button download=compactButton("↓ "+artifact.optString("name","Артефакт"));
                        download.setEnabled(!artifact.optBoolean("expired",false));
                        download.setOnClickListener(v->startArtifactDownload(full,artifact));
                        destination.addView(download,new LinearLayout.LayoutParams(-1,dp(48)));
                    }
                    if(details.partial)card(text("Список заданий или артефактов неполный.",12,false));
                    Button web=compactButton("Открыть полный журнал GitHub");
                    web.setOnClickListener(v->openWeb(run.optString("html_url",
                            "https://github.com/"+full+"/actions/runs/"+runId)));
                    destination.addView(web,new LinearLayout.LayoutParams(-1,dp(48)));
                });
            }catch(Exception error){
                runOnUiThread(()->{
                    if(generation!=screenGeneration.get()||destination!=content
                            ||!account.equals(currentAccountId()))return;
                    destination.removeView(loading);
                    errorCard("Не удалось загрузить запуск",error.getMessage());
                });
            }
        });
    }

    private void showBuildCenter(){showBuildCenter("");}
    private void showBuildCenter(String focus){
        setCurrentScreen(()->showBuildCenter(focus));
        shell("Центр сборок",false);
        if(!focus.isEmpty())repoSectionHeader(focus,"Сборки и артефакты","GitHub Actions",R.drawable.ic_android);
        TextView buildIntro=text("GitHub Actions · APK, ZIP и история запусков",13,false);
        buildIntro.setTextColor(MUTED);
        content.addView(buildIntro);
        if(!isSignedIn()){
            accountRequiredCard("Подключите GitHub, чтобы увидеть приватные сборки и артефакты.");
            return;
        }
        final String account=BuildMonitor.account(this);
        final long generation=screenGeneration.get();
        Set<String> watched=BuildMonitor.watched(this);
        if(!focus.isEmpty()&&!watched.contains(focus)){
            Button add=button("+  Отслеживать "+focus);
            add.setOnClickListener(v->{
                BuildMonitor.setWatched(this,focus,true);
                requestNotificationPermissionIfNeeded();
                showBuildCenter(focus);
            });
            content.addView(add,new LinearLayout.LayoutParams(-1,dp(50)));
        }
        LinearLayout toolbar=new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        TextView count=text("Проектов: "+watched.size(),12,true);
        count.setTextColor(GREEN);
        toolbar.addView(count,new LinearLayout.LayoutParams(0,-2,1));
        Button refresh=button("Обновить");
        refresh.setTextSize(12);
        refresh.setOnClickListener(v->showBuildCenter(focus));
        LinearLayout.LayoutParams refreshParams=new LinearLayout.LayoutParams(dp(102),dp(44));
        refreshParams.setMargins(dp(6),0,0,0);
        toolbar.addView(refresh,refreshParams);
        content.addView(toolbar,new LinearLayout.LayoutParams(-1,dp(52)));
        if(watched.isEmpty()){
            TextView empty=text("Нет отслеживаемых проектов. Выберите репозитории в настройках мониторинга.",12,false);
            empty.setTextColor(MUTED);
            card(empty);
        }
        TextView status=text("",11,false);
        status.setTextColor(MUTED);
        content.addView(status,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout rows=new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        content.addView(rows);
        renderBuildRows(rows,BuildMonitor.recent(this),focus);
        if(watched.isEmpty())return;
        status.setText("Проверяем GitHub Actions…");
        io.execute(()->{
            try{
                BuildMonitor.scan(getApplicationContext(),false);
                runOnUiThread(()->{
                    if(generation!=screenGeneration.get()||
                            !account.equals(BuildMonitor.account(this)))return;
                    status.setText("Синхронизировано с GitHub · "+watched.size()+" проектов");
                    renderBuildRows(rows,BuildMonitor.recent(this),focus);
                });
            }catch(Exception error){
                runOnUiThread(()->{
                    if(generation!=screenGeneration.get())return;
                    status.setText("Не удалось обновить: "+error.getMessage());
                });
            }
        });
    }

    private void renderBuildRows(LinearLayout host,JSONArray source,String focus){
        host.removeAllViews();
        int completed=0,working=0,failed=0,visible=0;
        for(int i=0;i<source.length();i++){
            JSONObject run=source.optJSONObject(i);
            if(run==null||(!focus.isEmpty()&&!focus.equals(run.optString("repository",""))))continue;
            String status=run.optString("status","");
            if(!"completed".equals(status))working++;
            else if("success".equals(run.optString("conclusion","")))completed++;
            else failed++;
            visible++;
        }
        TextView counts=text("✓ "+completed+" успешных   ◷ "+working+" в работе   ⚠ "+failed+" прочих",11,true);
        counts.setTextColor(MUTED);
        counts.setPadding(0,dp(7),0,dp(6));
        host.addView(counts);
        HorizontalScrollView filterScroll=new HorizontalScrollView(this);
        filterScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips=new LinearLayout(this);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        final String[] filterIds={BuildRunFilter.ALL,BuildRunFilter.RUNNING,
                BuildRunFilter.SUCCESS,BuildRunFilter.OTHER};
        final String[] filterLabels={"Все","В работе","Успех","Прочие"};
        for(int i=0;i<filterIds.length;i++){
            final String filterId=filterIds[i];
            TextView chip=text(filterLabels[i],12,filterId.equals(buildRunFilter));
            chip.setTextColor(filterId.equals(buildRunFilter)?Color.WHITE:TEXT);
            chip.setGravity(Gravity.CENTER);
            chip.setBackground(round(filterId.equals(buildRunFilter)?GREEN:CARD,14,BORDER,1));
            chip.setOnClickListener(v->{
                buildRunFilter=filterId;
                buildVisibleLimit=12;
                renderBuildRows(host,source,focus);
            });
            LinearLayout.LayoutParams chipParams=new LinearLayout.LayoutParams(-2,dp(38));
            chipParams.setMargins(0,0,dp(6),0);
            chip.setPadding(dp(12),0,dp(12),0);
            chips.addView(chip,chipParams);
        }
        filterScroll.addView(chips);
        host.addView(filterScroll,new LinearLayout.LayoutParams(-1,dp(44)));
        if(visible==0){
            TextView empty=text("В доступных запусках пока нет сборок. "+
                    "Проверьте доступ GitHub App: Actions → Read.",12,false);
            empty.setTextColor(MUTED);cardBuild(host,empty);return;
        }
        int shown=0;
        int totalFiltered=0;
        for(int i=0;i<source.length();i++){
            JSONObject candidate=source.optJSONObject(i);
            if(candidate==null||(!focus.isEmpty()
                    &&!focus.equals(candidate.optString("repository",""))))continue;
            if(BuildRunFilter.accepts(buildRunFilter,candidate.optString("status",""),
                    candidate.optString("conclusion","")))totalFiltered++;
        }
        for(int i=0;i<source.length()&&shown<buildVisibleLimit;i++){
            JSONObject run=source.optJSONObject(i);
            if(run==null||(!focus.isEmpty()&&!focus.equals(run.optString("repository",""))))continue;
            if(!BuildRunFilter.accepts(buildRunFilter,run.optString("status",""),
                    run.optString("conclusion","")))continue;
            shown++;
            String repo=run.optString("repository","");
            long runId=run.optLong("id",0);
            boolean ready=run.optJSONArray("artifacts")!=null&&
                    run.optJSONArray("artifacts").length()>0;
            String state=!"completed".equals(run.optString("status",""))?
                    "◷ Выполняется":"success".equals(run.optString("conclusion",""))?
                    (ready?"✓ Артефакты готовы":"✓ Сборка успешна"):
                    "⚠ "+run.optString("conclusion","Завершена");
            LinearLayout card=new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12),dp(10),dp(12),dp(10));
            card.setBackground(round(CARD,15,BORDER,1));
            TextView badge=text(state,12,true);
            badge.setTextColor(ready?GREEN:"completed".equals(run.optString("status",""))?
                    Color.rgb(200,78,76):BLUE);
            card.addView(badge);
            TextView name=text(repo+" · "+run.optString("name","GitHub Actions"),14,true);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(name);
            String sha=run.optString("head_sha","");
            if(sha.length()>9)sha=sha.substring(0,9);
            TextView detail=text(run.optString("head_branch","")+"  ·  "+sha,11,false);
            detail.setTextColor(MUTED);
            detail.setMaxLines(2);
            card.addView(detail);
            // GitHub's run_started_at is the actual workflow start; created_at is
            // used only when older API/cached records do not expose it.
            String startedAt=run.optString("run_started_at","");
            if(startedAt.isEmpty()||"null".equals(startedAt))
                startedAt=run.optString("created_at","");
            TextView startTime=text("Запуск: "+buildTimeOrPending(startedAt),11,false);
            startTime.setTextColor(MUTED);
            card.addView(startTime);
            JSONArray artifacts=run.optJSONArray("artifacts");
            String publishedAt="";
            if(artifacts!=null)for(int k=0;k<artifacts.length();k++){
                JSONObject file=artifacts.optJSONObject(k);
                if(file==null)continue;
                String published=file.optString("created_at","");
                if(publishedAt.compareTo(published)<0)publishedAt=published;
            }
            String finishLabel;
            if(!publishedAt.isEmpty())finishLabel=
                    "Артефакты опубликованы: "+buildTimeOrPending(publishedAt);
            else if(!"completed".equals(run.optString("status","")))finishLabel=
                    "Публикация: ожидается";
            else if(artifacts!=null&&artifacts.length()>0)finishLabel=
                    "Артефакты готовы · время публикации неизвестно";
            else finishLabel="Артефакты не опубликованы";
            TextView finishedTime=text(finishLabel,11,false);
            finishedTime.setTextColor(ready?GREEN:MUTED);
            finishedTime.setMaxLines(2);
            card.addView(finishedTime);
            if(artifacts!=null)for(int k=0;k<artifacts.length();k++){
                JSONObject artifact=artifacts.optJSONObject(k);
                if(artifact==null)continue;
                long size=artifact.optLong("size_in_bytes",0);
                LinearLayout row=new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0,dp(5),0,0);
                ImageView icon=imageIcon(R.drawable.ic_repository,GREEN,5);
                row.addView(icon,new LinearLayout.LayoutParams(dp(27),dp(27)));
                TextView label=text(artifact.optString("name","Артефакт")+
                        " · "+formatAccountBytes(size),11,true);
                label.setSingleLine(true);label.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(label,new LinearLayout.LayoutParams(0,-2,1));
                TextView download=text("↓ ZIP",12,true);
                download.setTextColor(GREEN);
                download.setGravity(Gravity.CENTER);
                download.setContentDescription("Скачать "+artifact.optString("name","артефакт"));
                download.setOnClickListener(v->startArtifactDownload(repo,artifact));
                row.addView(download,new LinearLayout.LayoutParams(dp(61),dp(42)));
                card.addView(row);
            }
            TextView open=text("Открыть запуск GitHub  ›",11,true);
            open.setTextColor(GREEN);
            open.setText("Детали запуска  ›");
            open.setOnClickListener(v->navigate(()->showBuildRunDetail(repo,runId)));
            card.addView(open);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
            p.setMargins(0,0,0,dp(9));
            host.addView(card,p);
        }
        if(totalFiltered==0&&visible>0){
            TextView empty=text("Для этого фильтра запусков пока нет.",12,false);
            empty.setTextColor(MUTED);
            host.addView(empty);
        }
        if(totalFiltered>shown){
            TextView more=text("Показать ещё · "+(totalFiltered-shown)+" запусков  ›",13,true);
            more.setTextColor(GREEN);
            more.setGravity(Gravity.CENTER);
            more.setOnClickListener(v->{
                buildVisibleLimit+=12;
                renderBuildRows(host,source,focus);
            });
            host.addView(more,new LinearLayout.LayoutParams(-1,dp(48)));
        }
    }

    private String buildTimeOrPending(String iso){
        if(iso==null||iso.isEmpty()||"null".equals(iso))return "нет данных";
        return formatGitHubDate(iso);
    }

    private void cardBuild(LinearLayout host,View child){
        LinearLayout wrap=new LinearLayout(this);
        wrap.setPadding(dp(11),dp(10),dp(11),dp(10));
        wrap.setBackground(round(CARD,15,BORDER,1));
        wrap.addView(child);
        host.addView(wrap);
    }

    private void startArtifactDownload(String repo,JSONObject artifact){
        if(artifact.optBoolean("expired",false)){toast("Срок хранения артефакта истёк");return;}
        io.execute(()->{
            try{
                long id=BuildArtifactDownloads.enqueue(getApplicationContext(),repo,artifact);
                runOnUiThread(()->toast("Загрузка началась: №"+id+
                        ". Архив — в Загрузках/GitHubble"));
            }catch(Exception error){
                runOnUiThread(()->errorDialog("Не удалось скачать артефакт",
                        error.getMessage()));
            }
        });
    }

    private void showBuildMonitorSettings(){
        setCurrentScreen(this::showBuildMonitorSettings);
        shell("Мониторинг сборок",false);
        settingsPanelHeader("Мониторинг GitHub Actions",
                "Выбирайте проекты, получайте уведомления и сохраняйте старые APK",
                R.drawable.ic_repository);
        if(!isSignedIn()){
            accountRequiredCard("Подключите GitHub для мониторинга сборок.");return;
        }
        Set<String> repos=BuildMonitor.watched(this);
        settingsRow("Отслеживаемые проекты",repos.size()+" из 30");
        settingsRow("Проверки в фоне","Android JobScheduler · примерно раз в 15 минут");
        settingsRow("Системные уведомления","Нужен доступ к уведомлениям Android");
        settingsActionRow("Выбрать репозитории","Из доступных вашему аккаунту",
                this::chooseBuildRepositories);
        settingsActionRow("Добавить по имени","В формате владелец/репозиторий",
                this::addBuildRepository);
        settingsToggleRow("Уведомления о сборках",
                "Новые запуски, результаты Actions и готовые артефакты",
                "build_notifications",true);
        settingsToggleRow("Только последняя успешная сборка · Wi-Fi",
                "Автоскачивание одного APK из новейшей успешной сборки каждого проекта; старые версии — только вручную",
                "build_auto_apk",false);
        settingsActionRow("Центр сборок","Посмотреть запуски и скачать APK",
                ()->navigate(this::showBuildCenter));
        TextView hint=text("Мониторинг всех сборок и уведомления работают отдельно от автоскачивания. "+
                "При включении автосохранения первая синхронизация устанавливает исходную точку без загрузки прошлых сборок. "+
                "Последующие изменения отслеживаются. "+
                "Фоновые проверки Android могут задерживаться; мгновенные FCM-push "+
                "требуют отдельного серверного подключения.",11,false);
        hint.setTextColor(MUTED);card(hint);
        if(!repos.isEmpty()){
            sectionTitle("Отслеживаемые",null);
            for(String full:repos){
                settingsActionRow(full,"Нажмите, чтобы открыть сборки",
                        ()->navigate(()->showBuildCenter(full)));
            }
        }
    }

    private void addBuildRepository(){
        EditText field=new EditText(this);
        field.setSingleLine(true);
        field.setHint("mallstorm/GitHubble");
        new AlertDialog.Builder(this).setTitle("Добавить репозиторий")
                .setView(field)
                .setPositiveButton("Следить",(dialog,which)->{
                    String full=field.getText().toString().trim();
                    if(!full.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")){
                        toast("Нужен формат владелец/репозиторий");return;
                    }
                    if(BuildMonitor.watched(this).size()>=30){
                        toast("Можно отслеживать до 30 репозиториев");return;
                    }
                    BuildMonitor.setWatched(this,full,true);
                    requestNotificationPermissionIfNeeded();
                    showBuildMonitorSettings();
                }).setNegativeButton("Отмена",null).show();
    }

    private void chooseBuildRepositories(){
        RepositoryStore.Snapshot snapshot=repositoryStore.read(currentAccountId());
        List<String> values=new ArrayList<>();
        for(int i=0;i<snapshot.repositories.length();i++){
            JSONObject repo=snapshot.repositories.optJSONObject(i);
            if(repo!=null){
                String full=repo.optString("full_name","");
                if(full.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")&&!values.contains(full))
                    values.add(full);
            }
        }
        if(values.isEmpty()){
            new AlertDialog.Builder(this).setTitle("Нет синхронизированных репозиториев")
                    .setMessage("Сначала обновите список репозиториев или добавьте проект по имени.")
                    .setPositiveButton("Обновить", (d,w)->navigate(()->showMyRepositories(true)))
                    .setNegativeButton("Отмена",null).show();
            return;
        }
        String[] names=values.toArray(new String[0]);
        Set<String> chosen=new LinkedHashSet<>(BuildMonitor.watched(this));
        boolean[] checked=new boolean[names.length];
        for(int i=0;i<names.length;i++)checked[i]=chosen.contains(names[i]);
        new AlertDialog.Builder(this).setTitle("Мониторинг проектов · до 30")
                .setMultiChoiceItems(names,checked,(dialog,index,on)->{
                    if(on&&chosen.size()>=30&&!chosen.contains(names[index])){
                        ((AlertDialog)dialog).getListView().setItemChecked(index,false);
                        toast("Ограничение: 30 проектов");
                    }else if(on)chosen.add(names[index]);
                    else chosen.remove(names[index]);
                }).setPositiveButton("Сохранить",(dialog,which)->{
                    Set<String> before=BuildMonitor.watched(this);
                    for(String old:before)if(!chosen.contains(old))BuildMonitor.setWatched(this,old,false);
                    for(String selected:chosen)if(!before.contains(selected))
                        BuildMonitor.setWatched(this,selected,true);
                    requestNotificationPermissionIfNeeded();
                    showBuildMonitorSettings();
                }).setNegativeButton("Отмена",null).show();
    }

    private void showMyRepositories(){showMyRepositories(false);}

    private void showMyRepositories(boolean forceNetwork){
        setCurrentScreen(this::showMyRepositories);
        shell("Репозитории",false);
        if(!isSignedIn()){
            accountRequiredCard("Войдите в GitHub, чтобы открыть свои репозитории.");
            return;
        }
        final long generation=screenGeneration.get();
        final String accountId=currentAccountId();
        repositoryStore.migrateLegacy(accountId);
        RepositoryStore.Snapshot cached=repositoryStore.read(accountId);
        app.githubble.mobile.ui.RepositoriesScreen screen =
                new app.githubble.mobile.ui.RepositoriesScreen(this,content,
                        new app.githubble.mobile.ui.RepositoriesScreen.Actions() {
                            @Override public void create(){ showCreateRepositoryDialog(); }
                            @Override public void refresh(){ showMyRepositories(true); }
                            @Override public void sort(){ showRepositorySortDialog(); }
                            @Override public void toggleLayout(boolean grid){
                                repositoriesGrid=grid;
                                prefs.edit().putBoolean("repositories_grid",grid).apply();
                            }
                            @Override public void open(String full){
                                navigate(() -> showRepository(full));
                            }
                            @Override public void menu(JSONObject repo){
                                String full=repo.optString("full_name","");
                                String[] items={"Открыть","Управление","Открыть на GitHub","Обновить репозитории"};
                                new AlertDialog.Builder(MainActivity.this)
                                        .setTitle(full).setItems(items,(dialog,index)->{
                                            if(index==0)navigate(() -> showRepository(full));
                                            if(index==1)navigate(() -> showRepositoryAdmin(full,repo));
                                            if(index==2)openWeb("https://github.com/"+full);
                                            if(index==3)showMyRepositories(true);
                                        }).show();
                            }
                        },CARD,TEXT,MUTED,GREEN,BORDER,SUBTLE,
                        new LinkedHashSet<>(prefs.getStringSet("favorites",new LinkedHashSet<>())),
                        repositoriesGrid);
        screen.render(sortedRepositories(cached.repositories),repositorySortLabel(),
                cached.lastSyncedAt>0?"Синхронизация: "+formatEpoch(cached.lastSyncedAt)
                        :"Синхронизация ещё не выполнялась");
        if(cached.status==RepositoryStore.Status.ERROR || cached.status==RepositoryStore.Status.STALE)
            screen.setStatus("Последняя синхронизация завершилась ошибкой. Сохранённые данные доступны.");
        Button buildCenter=button("◈  Центр сборок · APK и артефакты");
        buildCenter.setContentDescription("Открыть центр сборок GitHub Actions");
        buildCenter.setOnClickListener(v->navigate(this::showBuildCenter));
        LinearLayout.LayoutParams buildsP=new LinearLayout.LayoutParams(-1,dp(48));
        buildsP.setMargins(0,0,0,dp(10));
        content.addView(buildCenter,0,buildsP);
        final boolean needsNetwork=forceNetwork || cached.lastSyncedAt==0L
                || System.currentTimeMillis()-cached.lastSyncedAt>120000L;
        if(!needsNetwork)return;
        screen.setStatus("Обновляем данные GitHub…");
        io.execute(()->{
            try{
                RepositoryAccessController.SyncResult result=repositoryAccess.sync(forceNetwork);
                RepositoryStore.Snapshot fresh=repositoryStore.saveSuccess(accountId,result.repositories);
                prefs.edit().putString("repository_access_settings_url",result.bestSettingsUrl())
                        .putLong("repository_last_sync",System.currentTimeMillis()).apply();
                runOnUiThread(()->{
                    if(generation!=screenGeneration.get()||!accountId.equals(currentAccountId()))return;
                    screen.updateData(sortedRepositories(fresh.repositories));
                    screen.setStatus("Синхронизировано: "+formatEpoch(fresh.lastSyncedAt));
                    if(fresh.invalid>0)
                        errorCard("Часть данных GitHub не распознана",
                                "Не удалось прочитать "+fresh.invalid+" записей.");
                    if(!result.hasInstallations()&&fresh.repositories.length()==0)repositoryAccessHelp();
                });
            }catch(Exception ex){
                RepositoryStore.Snapshot stale=repositoryStore.saveError(accountId,ex);
                runOnUiThread(()->{
                    if(generation!=screenGeneration.get()||!accountId.equals(currentAccountId()))return;
                    screen.setStatus(stale.hasData()
                            ?"Сеть недоступна: показаны сохранённые репозитории"
                            :"Ошибка загрузки: "+ex.getMessage());
                    if(!stale.hasData())repositoryAccessHelp();
                });
            }
        });
    }

    private void repositoryAccessHelp(){
        TextView note=text("Доступных репозиториев пока нет. Проверьте установку GiHubble и доступ к нужным private-репозиториям.",13,false);
        note.setTextColor(MUTED);card(note);
        Button access=button("Настроить доступ");access.setOnClickListener(v->openPrivateRepositoryAccess());
        content.addView(access,new LinearLayout.LayoutParams(-1,dp(48)));
    }

    private String repositorySortLabel(){
        if("name".equals(repositoriesSort))return "название";
        if("stars".equals(repositoriesSort))return "звёзды";
        return "обновление";
    }

    private void showRepositorySortDialog(){
        final String[] values={"updated","name","stars"};
        final String[] labels={"Недавно обновлённые","По названию","По звёздам"};
        int checked=0;for(int i=0;i<values.length;i++)if(values[i].equals(repositoriesSort))checked=i;
        new AlertDialog.Builder(this).setTitle("Сортировка репозиториев")
                .setSingleChoiceItems(labels,checked,(dialog,which)->{
                    repositoriesSort=values[which];
                    prefs.edit().putString("repositories_sort",repositoriesSort).apply();
                    dialog.dismiss();showMyRepositories(false);
                }).setNegativeButton("Отмена",null).show();
    }

    private JSONArray sortedRepositories(JSONArray source){
        List<JSONObject> entries=new ArrayList<>();
        for(int i=0;i<source.length();i++){JSONObject repository=source.optJSONObject(i);if(repository!=null)entries.add(repository);}
        Collections.sort(entries,(left,right)->{
            if("name".equals(repositoriesSort))return left.optString("full_name","").compareToIgnoreCase(right.optString("full_name", ""));
            if("stars".equals(repositoriesSort))return Integer.compare(right.optInt("stargazers_count",0),left.optInt("stargazers_count",0));
            return right.optString("updated_at",right.optString("pushed_at","")).compareTo(left.optString("updated_at",left.optString("pushed_at", "")));
        });
        JSONArray sorted=new JSONArray();for(JSONObject repository:entries)sorted.put(repository);return sorted;
    }

    private void renderRepositoryAccessSummary(RepositoryAccessController.SyncResult result){
        sectionTitle("Доступ GitHub App",null);
        if(!result.hasInstallations()){
            TextView warning=text(result.repositories.length()==0
                    ?"Вход выполнен, но GitHub не вернул доступные репозитории. Проверьте установку GiHubble и разрешение доступа к private-репозиториям."
                    :"Репозитории загружены через GitHub API; информация об установках GiHubble недоступна или отсутствует. Доступ к остальным репозиториям зависит от разрешений приложения.",13,false);
            warning.setTextColor(MUTED);
            card(warning);
            Button install=button("Установить / настроить GiHubble");
            install.setOnClickListener(v->openPrivateRepositoryAccess());
            content.addView(install,new LinearLayout.LayoutParams(-1,dp(50)));
            if(!result.warning.isEmpty())errorCard("Диагностика доступа",result.warning);
            return;
        }

        settingsRow("Установок GiHubble",String.valueOf(result.installations.size()));
        settingsRow("Приватных доступно",String.valueOf(result.privateCount));
        settingsRow("Публичных доступно",String.valueOf(result.publicCount));

        for(RepositoryAccessController.Installation installation:result.installations){
            String selection="all".equalsIgnoreCase(installation.repositorySelection)
                    ?"Все репозитории"
                    :"selected".equalsIgnoreCase(installation.repositorySelection)
                    ?"Выбранные репозитории"
                    :installation.repositorySelection;
            String detail=selection+" · "+installation.privateCount+" private · "+installation.publicCount+" public";
            settingsActionRow(
                    installation.account.isEmpty()?"GitHub installation":installation.account,
                    detail,
                    () -> {
                        prefs.edit().putString("repository_access_settings_url",installation.settingsUrl()).apply();
                        openPrivateRepositoryAccess();
                    }
            );
            if(!installation.error.isEmpty()){
                TextView err=text("Ошибка доступа: "+installation.error,11,false);
                err.setTextColor(MUTED);
                card(err);
            }
        }

        if(result.privateCount==0){
            TextView hint=text("Если у аккаунта есть приватные репозитории, но здесь 0, откройте настройку GiHubble выше и выберите «All repositories» либо отметьте нужные private-репозитории. После возврата список обновится автоматически.",12,false);
            hint.setTextColor(MUTED);
            card(hint);
        }
        if(!result.warning.isEmpty()){
            TextView warning=text(result.warning,11,false);
            warning.setTextColor(MUTED);
            card(warning);
        }
    }

    private void renderRepositoryArray(String expectedTitle,ProgressBar pb,JSONArray repos){if(!expectedTitle.equals(currentTitle))return;content.removeView(pb);sectionTitle("Доступно: "+repos.length(),null);renderRepositoryItems(repos);}
    private void renderRepositoryItems(JSONArray repos){
        List<JSONObject> shownRepositories=new ArrayList<>();
        for(int i=0;i<repos.length();i++){
            JSONObject repository=repos.optJSONObject(i);
            if(repository==null || (repositoriesPrivateOnly && !repository.optBoolean("private",false)) || (repositoriesPublicOnly && repository.optBoolean("private",false)))continue;
            shownRepositories.add(repository);
        }
        if(shownRepositories.isEmpty()){card(text(repositoriesPrivateOnly?"Приватные репозитории не найдены среди доступных проектов.":repositoriesPublicOnly?"Публичные репозитории не найдены среди доступных проектов.":"Репозитории не найдены.",14,false));return;}
        if(repositoriesGrid){
            GridLayout grid=new GridLayout(this);grid.setColumnCount(adaptiveColumns(2));
            for(JSONObject repository:shownRepositories)try{addRepoTile(grid,repository);}catch(Exception error){/* Invalid items are reported by sync diagnostics. */}
            content.addView(grid,new LinearLayout.LayoutParams(-1,-2));
        }else for(JSONObject repository:shownRepositories){
            try{addRepoCard(repository);}catch(Exception error){TextView invalid=text("Не удалось показать карточку: "+repository.optString("full_name","без имени"),12,false);invalid.setTextColor(MUTED);card(invalid);}
        }
    }

    private void showOrganizations(){showOrganizationsPage(1,true);}
    private void showOrganizationsPage(int page,boolean first){
        if(first){setCurrentScreen(this::showOrganizations);shell("Организации",true);heroBanner("Организации GitHub","Публичные и доступные вашему аккаунту организации",R.drawable.ic_organization);if(!isSignedIn()){accountRequiredCard("Подключите GitHub для загрузки организаций.");return;}}
        ProgressBar pb=new ProgressBar(this);content.addView(pb);io.execute(()->{try{JSONArray orgs=getArray("https://api.github.com/user/orgs?per_page=50&page="+page);runOnUiThread(()->{content.removeView(pb);if(first)sectionTitle("Организации",null);if(orgs.length()==0&&first){card(text("Организации не найдены. Для закрытых организаций доступ зависит от установки GiHubble и политики организации.",13,false));return;}for(int i=0;i<orgs.length();i++){JSONObject org=orgs.optJSONObject(i);if(org==null)continue;String login=org.optString("login","");LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);ImageView icon=imageIcon(R.drawable.ic_organization,GREEN,12);row.addView(icon,new LinearLayout.LayoutParams(dp(48),dp(48)));LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);TextView name=text(login,16,true);name.setTextColor(BLUE);labels.addView(name);TextView desc=text(org.optString("description","Организация GitHub"),12,false);desc.setTextColor(MUTED);labels.addView(desc);row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));row.setOnClickListener(v->openWeb("https://github.com/"+login));card(row);}if(orgs.length()==50)addLoadMoreButton("Показать ещё организации",()->showOrganizationsPage(page+1,false));});}catch(Exception e){runOnUiThread(()->{content.removeView(pb);errorCard("Не удалось загрузить организации",e.getMessage());});}});
    }

    private void showProjects(){showProjectsPage("",true);}
    private void showProjectsPage(String cursor,boolean first){
        if(first){setCurrentScreen(this::showProjects);shell("Проекты",false);heroBanner("GitHub Projects","Доски планирования вашего аккаунта",R.drawable.ic_projects);if(!isSignedIn()){accountRequiredCard("Подключите GitHub для загрузки проектов.");return;}}
        ProgressBar pb=new ProgressBar(this);content.addView(pb);io.execute(()->{try{JSONObject vars=new JSONObject();vars.put("after",cursor.isEmpty()?JSONObject.NULL:cursor);String query="query ViewerProjects($after:String){ viewer { login projectsV2(first:30,after:$after,orderBy:{field:UPDATED_AT,direction:DESC}){ totalCount pageInfo{hasNextPage endCursor} nodes{id number title shortDescription closed updatedAt url items{totalCount}} } } }";JSONObject data=graphql.execute(query,vars);JSONObject viewer=data.optJSONObject("viewer");JSONObject connection=viewer==null?null:viewer.optJSONObject("projectsV2");JSONArray projects=connection==null?new JSONArray():connection.optJSONArray("nodes");if(projects==null)projects=new JSONArray();JSONObject pageInfo=connection==null?null:connection.optJSONObject("pageInfo");boolean hasNext=pageInfo!=null&&pageInfo.optBoolean("hasNextPage",false);String nextCursor=pageInfo==null?"":pageInfo.optString("endCursor","");int total=connection==null?projects.length():connection.optInt("totalCount",projects.length());JSONArray finalProjects=projects;runOnUiThread(()->{content.removeView(pb);if(first)sectionTitle("Проектов: "+total,null);if(finalProjects.length()==0&&first){card(text("Проекты не найдены. Проверьте разрешение Projects у GiHubble.",13,false));return;}for(int i=0;i<finalProjects.length();i++)renderProjectCard(finalProjects.optJSONObject(i));if(hasNext&&!nextCursor.isEmpty())addLoadMoreButton("Показать ещё проекты",()->showProjectsPage(nextCursor,false));else if(first){Button open=button("Открыть проекты на GitHub");String login=getProfileLogin();open.setOnClickListener(v->openWeb("https://github.com/users/"+login+"/projects"));content.addView(open,new LinearLayout.LayoutParams(-1,dp(52)));}});}catch(Exception e){runOnUiThread(()->{content.removeView(pb);errorCard("Не удалось загрузить GitHub Projects",e.getMessage());});}});
    }

    private void renderProjectCard(JSONObject project){
        if(project==null)return;
        String title=project.optString("title","Проект без названия");String description=project.optString("shortDescription","");
        String url=project.optString("url","");boolean closed=project.optBoolean("closed",false);int items=project.optJSONObject("items")==null?0:project.optJSONObject("items").optInt("totalCount",0);
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);
        TextView name=text(title,16,true);head.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        TextView status=text(closed?"Закрыт":"Активен",11,true);status.setTextColor(closed?MUTED:GREEN);head.addView(status);row.addView(head);
        if(!description.isEmpty()){TextView d=text(description,12,false);d.setTextColor(MUTED);row.addView(d);}
        TextView meta=text("Элементов: "+items+" · обновлён "+formatGitHubDate(project.optString("updatedAt","")),11,false);meta.setTextColor(MUTED);row.addView(meta);
        if(!url.isEmpty())row.setOnClickListener(v->openWeb(url));card(row);
    }

    private void showAccountActivity(){
        setCurrentScreen(this::showAccountActivity);shell("История",false);heroBanner("История аккаунта","Последние публичные события профиля GitHub",R.drawable.ic_history);
        if(!isSignedIn()){accountRequiredCard("Подключите GitHub для загрузки истории аккаунта.");return;}
        String login=getProfileLogin();if(login.isEmpty()){refreshProfileData(true);return;}
        ProgressBar pb=new ProgressBar(this);content.addView(pb);
        io.execute(() -> {try{JSONArray events=getArray("https://api.github.com/users/"+URLEncoder.encode(login,"UTF-8")+"/events?per_page=50");runOnUiThread(() -> {if(!"История".equals(currentTitle))return;content.removeView(pb);sectionTitle("Последние события",null);if(events.length()==0){card(text("Публичная история пока пуста.",14,false));return;}for(int i=0;i<events.length();i++)renderAccountEvent(events.optJSONObject(i));});}catch(Exception e){runOnUiThread(() -> {if("История".equals(currentTitle)){content.removeView(pb);errorCard("Не удалось загрузить историю",e.getMessage());}});}});
    }

    private void renderAccountEvent(JSONObject event){if(event==null)return;String type=event.optString("type","");JSONObject repo=event.optJSONObject("repo");String full=repo==null?"GitHub":repo.optString("name","GitHub");String date=formatGitHubDate(event.optString("created_at",""));LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);row.addView(text(eventTypeLabel(type),14,true));TextView meta=text(full+(date.isEmpty()?"":" · "+date),11,false);meta.setTextColor(MUTED);row.addView(meta);if(full.contains("/"))row.setOnClickListener(v->navigate(() -> showRepository(full)));card(row);}
    private String eventTypeLabel(String type){switch(type){case "PushEvent":return "Отправлены изменения";case "PullRequestEvent":return "Изменён Pull Request";case "IssuesEvent":return "Изменена задача";case "IssueCommentEvent":return "Добавлен комментарий";case "CreateEvent":return "Создана ветка или тег";case "ReleaseEvent":return "Опубликован релиз";case "WatchEvent":return "Добавлена звезда";case "ForkEvent":return "Создан форк";default:return type.isEmpty()?"Событие GitHub":type.replace("Event","");}}

    private void showPeopleList(String title,String endpoint){showPeopleListPage(title,endpoint,1,true);}
    private void showPeopleListPage(String title,String endpoint,int page,boolean first){
        if(first){setCurrentScreen(()->showPeopleList(title,endpoint));shell(title,false);}ProgressBar pb=new ProgressBar(this);content.addView(pb);
        io.execute(()->{try{String separator=endpoint.contains("?")?"&":"?";JSONArray users=getArray(endpoint+separator+"per_page=50&page="+page);runOnUiThread(()->{content.removeView(pb);if(first)sectionTitle("Пользователи",null);if(users.length()==0&&first){card(text("Список пуст.",14,false));return;}for(int i=0;i<users.length();i++){JSONObject user=users.optJSONObject(i);if(user==null)continue;String login=user.optString("login","");LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);ImageView icon=imageIcon(R.drawable.ic_profile,GREEN,13);row.addView(icon,new LinearLayout.LayoutParams(dp(46),dp(46)));TextView name=text("@"+login,15,true);row.addView(name,new LinearLayout.LayoutParams(0,-2,1));row.setOnClickListener(v->navigate(()->showDeveloperProfile(login)));card(row);}if(users.length()==50)addLoadMoreButton("Показать ещё",()->showPeopleListPage(title,endpoint,page+1,false));});}catch(Exception e){runOnUiThread(()->{content.removeView(pb);errorCard("Не удалось загрузить список",e.getMessage());});}});
    }

    private void showDiscussions(){showDiscussionsPage("",true);}
    private void showDiscussionsPage(String cursor,boolean first){
        if(first){setCurrentScreen(this::showDiscussions);shell("Обсуждения",true);heroBanner("GitHub Discussions","Темы из доступных вашему аккаунту репозиториев",R.drawable.ic_discussion);if(!isSignedIn()){accountRequiredCard("Подключите GitHub для загрузки Discussions.");return;}}
        ProgressBar pb=new ProgressBar(this);content.addView(pb);io.execute(()->{try{JSONObject vars=new JSONObject();vars.put("after",cursor.isEmpty()?JSONObject.NULL:cursor);String query="query ViewerDiscussions($after:String){ viewer { repositories(first:15,after:$after,affiliations:[OWNER,COLLABORATOR,ORGANIZATION_MEMBER],orderBy:{field:UPDATED_AT,direction:DESC}){ pageInfo{hasNextPage endCursor} nodes{nameWithOwner hasDiscussionsEnabled discussions(first:8,orderBy:{field:UPDATED_AT,direction:DESC}){nodes{id number title url createdAt updatedAt answerChosenAt author{login} category{name emoji} comments{totalCount}}}}}}}";JSONObject data=graphql.execute(query,vars);JSONObject viewer=data.optJSONObject("viewer");JSONObject repos=viewer==null?null:viewer.optJSONObject("repositories");JSONArray nodes=repos==null?new JSONArray():repos.optJSONArray("nodes");if(nodes==null)nodes=new JSONArray();List<JSONObject> discussions=new ArrayList<>();for(int i=0;i<nodes.length();i++){JSONObject repo=nodes.optJSONObject(i);if(repo==null||!repo.optBoolean("hasDiscussionsEnabled",false))continue;String full=repo.optString("nameWithOwner","");JSONObject dc=repo.optJSONObject("discussions");JSONArray ds=dc==null?null:dc.optJSONArray("nodes");if(ds==null)continue;for(int j=0;j<ds.length();j++){JSONObject d=ds.optJSONObject(j);if(d==null)continue;d.put("repository",full);discussions.add(d);}}discussions.sort((a,b)->Long.compare(parseIsoEpoch(b.optString("updatedAt","")),parseIsoEpoch(a.optString("updatedAt",""))));JSONObject pi=repos==null?null:repos.optJSONObject("pageInfo");boolean hasNext=pi!=null&&pi.optBoolean("hasNextPage",false);String next=pi==null?"":pi.optString("endCursor","");runOnUiThread(()->{content.removeView(pb);if(first)sectionTitle("Последние темы",null);if(discussions.isEmpty()&&first){TextView empty=text("Темы не найдены. Проверьте установку GiHubble и разрешение Discussions.",13,false);empty.setTextColor(MUTED);card(empty);}for(JSONObject d:discussions)renderDiscussionGraphQlCard(d);if(hasNext&&!next.isEmpty())addLoadMoreButton("Показать ещё репозитории с темами",()->showDiscussionsPage(next,false));});}catch(Exception e){runOnUiThread(()->{content.removeView(pb);errorCard("Не удалось загрузить Discussions",e.getMessage());});}});
    }

    private void renderDiscussionGraphQlCard(JSONObject d){
        if(d==null)return;String full=d.optString("repository","");int number=d.optInt("number");String title=d.optString("title","Без названия");
        JSONObject category=d.optJSONObject("category");String categoryName=category==null?"Тема":category.optString("name","Тема");String emoji=category==null?"":category.optString("emoji","");
        JSONObject author=d.optJSONObject("author");String login=author==null?"":author.optString("login","");int comments=d.optJSONObject("comments")==null?0:d.optJSONObject("comments").optInt("totalCount",0);
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);row.addView(text((emoji.isEmpty()?"":emoji+"  ")+title,15,true));
        TextView meta=text(full+" · "+categoryName+(login.isEmpty()?"":" · "+login)+" · "+comments+" ответов",11,false);meta.setTextColor(MUTED);row.addView(meta);
        row.setOnClickListener(v->navigate(() -> showDiscussionDetail(full,number)));card(row);
    }

    private void showRepoDiscussions(String full){showRepoDiscussionsPage(full,"",true);}
    private void showRepoDiscussionsPage(String full,String cursor,boolean first){
        if(first){setCurrentScreen(()->showRepoDiscussions(full));shell("Обсуждения",true);repoSectionHeader(full,"Обсуждения","GitHub Discussions",R.drawable.ic_discussion);}String[] parts=full.split("/",2);if(parts.length!=2){errorCard("Неверный репозиторий",full);return;}ProgressBar pb=new ProgressBar(this);content.addView(pb);
        io.execute(()->{try{JSONObject vars=new JSONObject();vars.put("owner",parts[0]);vars.put("name",parts[1]);vars.put("after",cursor.isEmpty()?JSONObject.NULL:cursor);String query="query RepoDiscussions($owner:String!,$name:String!,$after:String){repository(owner:$owner,name:$name){hasDiscussionsEnabled discussions(first:30,after:$after,orderBy:{field:UPDATED_AT,direction:DESC}){totalCount pageInfo{hasNextPage endCursor} nodes{id number title url createdAt updatedAt answerChosenAt author{login} category{name emoji} comments{totalCount}}}}}";JSONObject data=graphql.execute(query,vars);JSONObject repo=data.optJSONObject("repository");JSONObject conn=repo==null?null:repo.optJSONObject("discussions");JSONArray ds=conn==null?new JSONArray():conn.optJSONArray("nodes");if(ds==null)ds=new JSONArray();for(int i=0;i<ds.length();i++){JSONObject d=ds.optJSONObject(i);if(d!=null)d.put("repository",full);}JSONObject pi=conn==null?null:conn.optJSONObject("pageInfo");boolean hasNext=pi!=null&&pi.optBoolean("hasNextPage",false);String next=pi==null?"":pi.optString("endCursor","");int total=conn==null?ds.length():conn.optInt("totalCount",ds.length());JSONArray finalDs=ds;runOnUiThread(()->{content.removeView(pb);if(first)sectionTitle("Тем: "+total,null);if(finalDs.length()==0&&first)card(text("В этом репозитории нет Discussions или они отключены.",13,false));for(int i=0;i<finalDs.length();i++)renderDiscussionGraphQlCard(finalDs.optJSONObject(i));if(hasNext&&!next.isEmpty())addLoadMoreButton("Показать ещё темы",()->showRepoDiscussionsPage(full,next,false));});}catch(Exception e){runOnUiThread(()->{content.removeView(pb);errorCard("Не удалось загрузить Discussions",e.getMessage());});}});
    }

    private void showDiscussionDetail(String full,int number){
        setCurrentScreen(() -> showDiscussionDetail(full,number));shell("Тема #"+number,true);ProgressBar pb=new ProgressBar(this);content.addView(pb);String[] parts=full.split("/",2);if(parts.length!=2){content.removeView(pb);return;}
        io.execute(() -> {try{JSONObject vars=new JSONObject();vars.put("owner",parts[0]);vars.put("name",parts[1]);vars.put("number",number);String query="query DiscussionDetail($owner:String!,$name:String!,$number:Int!){ repository(owner:$owner,name:$name){ discussion(number:$number){ id title body url createdAt updatedAt answerChosenAt author{login} category{name emoji} comments(first:100){ totalCount nodes{id body createdAt updatedAt author{login} reactions{totalCount}} } } } }";JSONObject data=graphql.execute(query,vars);JSONObject repo=data.optJSONObject("repository");JSONObject d=repo==null?null:repo.optJSONObject("discussion");if(d==null)throw new IOException("Тема не найдена или недоступна");runOnUiThread(() -> {content.removeView(pb);heroBanner(d.optString("title","Тема"),full,R.drawable.ic_discussion);addTranslatableMarkdownCard("discussion:"+full+"#"+number,d.optString("body","Без описания"),d.optString("updatedAt",""));JSONObject author=d.optJSONObject("author");TextView meta=text((author==null?"":author.optString("login",""))+" · "+formatGitHubDate(d.optString("createdAt","")),11,false);meta.setTextColor(MUTED);card(meta);Button reply=button("Ответить");reply.setOnClickListener(v->showDiscussionCommentDialog(full,number,d.optString("id","")));content.addView(reply,new LinearLayout.LayoutParams(-1,dp(50)));JSONObject comments=d.optJSONObject("comments");JSONArray nodes=comments==null?new JSONArray():comments.optJSONArray("nodes");if(nodes==null)nodes=new JSONArray();sectionTitle("Ответы: "+nodes.length(),null);for(int i=0;i<nodes.length();i++){JSONObject c=nodes.optJSONObject(i);if(c==null)continue;LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);JSONObject u=c.optJSONObject("author");box.addView(text(u==null?"GitHub":u.optString("login","GitHub"),13,true));box.addView(translatableMarkdownPanel("discussion-comment:"+c.optString("id",""),c.optString("body",""),c.optString("updatedAt","")));card(box);}});}catch(Exception e){runOnUiThread(() -> {content.removeView(pb);errorCard("Не удалось открыть Discussion",e.getMessage());});}});
    }

    private void showDiscussionCommentDialog(String full,int number,String discussionId){
        if(discussionId.isEmpty()){toast("Не удалось определить Discussion ID");return;}final EditText body=new EditText(this);body.setMinLines(4);body.setGravity(Gravity.TOP);body.setHint("Ваш ответ в Markdown");body.setTextColor(TEXT);body.setHintTextColor(MUTED);
        new AlertDialog.Builder(this).setTitle("Ответить в Discussion").setView(body).setPositiveButton("Отправить",(d,w)->{String value=body.getText().toString().trim();if(value.isEmpty())return;ProgressDialog progress=ProgressDialog.show(this,"Discussion","Отправляем ответ…",true,false);io.execute(() -> {try{JSONObject vars=new JSONObject();vars.put("discussionId",discussionId);vars.put("body",value);String mutation="mutation AddDiscussionComment($discussionId:ID!,$body:String!){ addDiscussionComment(input:{discussionId:$discussionId,body:$body}){ comment{id} } }";graphql.execute(mutation,vars);runOnUiThread(() -> {progress.dismiss();toast("Ответ добавлен");showDiscussionDetail(full,number);});}catch(Exception e){runOnUiThread(() -> {progress.dismiss();toast("Не удалось отправить: "+e.getMessage());});}});}).setNegativeButton("Отмена",null).show();
    }

    private void showReleases(){
        setCurrentScreen(this::showReleases);
        shell("Релизы",true); heroBanner("Релизы", "Версии и файлы, опубликованные разработчиком", R.drawable.ic_release);
        TextView note=text("Откройте репозиторий, чтобы просмотреть его реальные Releases и assets.",14,false); note.setTextColor(MUTED); card(note);
        Button search=button("Найти репозиторий"); search.setOnClickListener(v->navigate(this::showSearch)); content.addView(search,new LinearLayout.LayoutParams(-1,dp(50)));
    }

    private void addMiniStat(GridLayout g,String value,String label,int color){
        LinearLayout b=new LinearLayout(this); b.setOrientation(LinearLayout.VERTICAL); b.setGravity(Gravity.CENTER); b.setPadding(dp(8),dp(8),dp(8),dp(8)); b.setBackground(round(CARD,15,BORDER,1));
        TextView v=text(value,20,true); v.setTextColor(color); v.setGravity(Gravity.CENTER); v.setPadding(0,0,0,0); b.addView(v);
        TextView l=text(label,10,false); l.setTextColor(MUTED); l.setGravity(Gravity.CENTER); l.setPadding(0,0,0,0); b.addView(l);
        GridLayout.LayoutParams p=new GridLayout.LayoutParams(); p.width=0;p.height=dp(70);p.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);p.setMargins(dp(3),dp(3),dp(3),dp(3));g.addView(b,p);
    }

    private void issueCard(String number,String title,String labels,int color){
        LinearLayout b=new LinearLayout(this); b.setOrientation(LinearLayout.VERTICAL);
        LinearLayout h=new LinearLayout(this); h.setGravity(Gravity.CENTER_VERTICAL);
        TextView n=text(number,12,true); n.setTextColor(color); n.setBackground(round(SUBTLE,10,Color.TRANSPARENT,0)); n.setGravity(Gravity.CENTER); h.addView(n,new LinearLayout.LayoutParams(dp(52),dp(28)));
        TextView t=text(title,14,true); t.setPadding(dp(10),0,0,0); h.addView(t,new LinearLayout.LayoutParams(0,-2,1)); b.addView(h);
        TextView m=text(labels,11,false); m.setTextColor(MUTED); m.setPadding(dp(62),dp(7),0,0); b.addView(m); card(b);
    }

    private void pullCard(String number,String title,String status,boolean passed){
        LinearLayout b=new LinearLayout(this); b.setOrientation(LinearLayout.VERTICAL);
        TextView t=text(number+"  "+title,14,true); b.addView(t);
        TextView s=text((passed?"✓ ":"◷ ")+status,11,true); s.setTextColor(passed?GREEN:tileColors[3]); s.setPadding(dp(4),dp(8),0,0); b.addView(s); card(b);
    }

    private void discussionCard(String title,String meta,int color){
        LinearLayout b=new LinearLayout(this); b.setOrientation(LinearLayout.VERTICAL);
        TextView t=text(title,15,true); b.addView(t); TextView m=text(meta,11,false);m.setTextColor(color);m.setPadding(dp(4),dp(8),0,0);b.addView(m);card(b);
    }

    private void releaseCard(String title,String subtitle,String time,int color){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        TextView i=text("↓",20,true);i.setTextColor(Color.WHITE);i.setGravity(Gravity.CENTER);i.setBackground(round(color,14,color,0));row.addView(i,new LinearLayout.LayoutParams(dp(46),dp(46)));
        LinearLayout info=new LinearLayout(this);info.setOrientation(LinearLayout.VERTICAL);info.setPadding(dp(12),0,0,0);TextView t=text(title,15,true);t.setPadding(0,0,0,0);info.addView(t);TextView s=text(subtitle,11,false);s.setTextColor(MUTED);s.setPadding(0,dp(2),0,0);info.addView(s);row.addView(info,new LinearLayout.LayoutParams(0,-2,1));TextView tm=text(time,10,false);tm.setTextColor(MUTED);row.addView(tm);card(row);
    }

    private void searchAdvanced(String q, boolean smart, SearchMode mode, SearchFilters filters){
        String value=q==null?"":q.trim();
        if(value.isEmpty()){toast("Введите запрос");return;}
        SearchMode selected=mode==null?SearchMode.REPOSITORIES:mode;
        SearchFilters safeFilters=filters==null?new SearchFilters():filters.copy();
        new SmartSearchHistory(prefs).remember(value,smart);
        AdvancedSearchPlanner.Plan plan=AdvancedSearchPlanner.plan(value,smart,selected,safeFilters);
        if(plan.variants.isEmpty()){
            toast("После применения фильтров запрос пуст");
            return;
        }
        searchAdvancedPage(plan,1,true);
    }

    private void searchAdvancedPage(AdvancedSearchPlanner.Plan plan,int page,boolean first){
        if(plan==null||plan.variants.isEmpty()){toast("Введите запрос");return;}
        if(first){
            smartSearchRendered.clear();
            SearchFilters restoreFilters=plan.filters.copy();
            setCurrentScreen(()->searchAdvanced(plan.original,plan.semantic.smartRequested,plan.mode,restoreFilters));
            shell(advancedSearchTitle(plan.mode),true);
        }
        ProgressBar pb=new ProgressBar(this);content.addView(pb);
        io.execute(()->{
            List<JSONArray> batches=new ArrayList<>();
            long reported=0;int failed=0;boolean mayHaveMore=false;Exception lastError=null;
            for(String variant:plan.variants){
                try{
                    String url=advancedSearchUrl(plan.mode,variant,page);
                    JSONObject root=getJson(url);
                    JSONArray items=root.optJSONArray("items");if(items==null)items=new JSONArray();
                    batches.add(items);reported+=Math.max(0,root.optInt("total_count",items.length()));
                    if(items.length()==20)mayHaveMore=true;
                }catch(Exception e){failed++;lastError=e;}
            }
            if(batches.isEmpty()){
                Exception error=lastError==null?new IOException("GitHub не вернул результаты поиска"):lastError;
                runOnUiThread(()->{content.removeView(pb);errorCard("Ошибка поиска",friendlyAdvancedSearchError(plan.mode,error));});
                return;
            }
            List<JSONObject> results;
            if(plan.mode==SearchMode.REPOSITORIES){
                List<RepositorySearchRanker.Hit> ranked=RepositorySearchRanker.merge(batches,plan.semantic);
                results=new ArrayList<>();
                for(RepositorySearchRanker.Hit hit:ranked){try{hit.repository.put("_githubble_matches",hit.matchedVariants);}catch(Exception ignored){}results.add(hit.repository);}
            }else results=mergeAdvancedBatches(batches,plan.mode);
            long finalReported=reported;int finalFailed=failed;boolean finalMayHaveMore=mayHaveMore;
            runOnUiThread(()->{
                content.removeView(pb);
                if(first)renderAdvancedSearchExplanation(plan,results.size(),finalReported,finalFailed);
                int added=0;
                for(JSONObject item:results){
                    if(item==null)continue;
                    String key=advancedSearchKey(plan.mode,item);
                    if(key.isEmpty()||!smartSearchRendered.add(key))continue;
                    int matches=Math.max(1,item.optInt("_githubble_matches",1));
                    try{renderAdvancedSearchItem(plan.mode,item,matches);}catch(Exception ignored){}
                    added++;
                }
                if(first&&added==0){TextView empty=text("Совпадений не найдено. Измените формулировку, фильтры или отключите RU ✦ для точного запроса.",13,false);empty.setTextColor(MUTED);card(empty);}
                if(finalMayHaveMore&&page<5)addLoadMoreButton("Показать ещё результаты",()->searchAdvancedPage(plan,page+1,false));
            });
        });
    }

    private String advancedSearchUrl(SearchMode mode,String query,int page)throws Exception{
        String encoded=URLEncoder.encode(query,"UTF-8");
        switch(mode){
            case ISSUES_PRS:return "https://api.github.com/search/issues?q="+encoded+"&sort=updated&order=desc&per_page=20&page="+page;
            case CODE:return "https://api.github.com/search/code?q="+encoded+"&per_page=20&page="+page;
            case PEOPLE:return "https://api.github.com/search/users?q="+encoded+"&per_page=20&page="+page;
            case REPOSITORIES:
            default:return "https://api.github.com/search/repositories?q="+encoded+"&sort=stars&order=desc&per_page=20&page="+page;
        }
    }

    private List<JSONObject> mergeAdvancedBatches(List<JSONArray> batches,SearchMode mode){
        LinkedHashMap<String,JSONObject> unique=new LinkedHashMap<>();
        for(JSONArray batch:batches){
            for(int i=0;i<batch.length();i++){
                JSONObject item=batch.optJSONObject(i);if(item==null)continue;
                String key=advancedSearchKey(mode,item);if(key.isEmpty())continue;
                JSONObject existing=unique.get(key);
                if(existing==null){try{item.put("_githubble_matches",1);}catch(Exception ignored){}unique.put(key,item);}
                else try{existing.put("_githubble_matches",existing.optInt("_githubble_matches",1)+1);}catch(Exception ignored){}
            }
        }
        List<JSONObject> out=new ArrayList<>(unique.values());
        out.sort((a,b)->{
            int match= Integer.compare(b.optInt("_githubble_matches",1),a.optInt("_githubble_matches",1));
            if(match!=0)return match;
            int score=Double.compare(b.optDouble("score",0),a.optDouble("score",0));
            if(score!=0)return score;
            if(mode==SearchMode.ISSUES_PRS)return b.optString("updated_at","").compareTo(a.optString("updated_at",""));
            return advancedSearchKey(mode,a).compareToIgnoreCase(advancedSearchKey(mode,b));
        });
        return out;
    }

    private String advancedSearchKey(SearchMode mode,JSONObject item){
        if(item==null)return "";
        switch(mode){
            case ISSUES_PRS:return item.optString("html_url",String.valueOf(item.optLong("id",0))).toLowerCase(Locale.ROOT);
            case CODE:{JSONObject repo=item.optJSONObject("repository");String full=repo==null?"":repo.optString("full_name","");return (full+"/"+item.optString("path",item.optString("name",""))).toLowerCase(Locale.ROOT);}
            case PEOPLE:return item.optString("login","").toLowerCase(Locale.ROOT);
            case REPOSITORIES:
            default:return item.optString("full_name","").toLowerCase(Locale.ROOT);
        }
    }

    private void renderAdvancedSearchItem(SearchMode mode,JSONObject item,int matches)throws Exception{
        switch(mode){
            case ISSUES_PRS:renderAdvancedIssueCard(item,matches);break;
            case CODE:renderCodeSearchCard(item,matches);break;
            case PEOPLE:renderPeopleSearchCard(item,matches);break;
            case REPOSITORIES:
            default:addRepoCard(item,matches);break;
        }
    }

    private void renderAdvancedIssueCard(JSONObject item,int matches){
        boolean pr=item.has("pull_request");String full=repoFromApiUrl(item.optString("repository_url",""));int number=item.optInt("number");String title=item.optString("title","Без названия");
        JSONObject user=item.optJSONObject("user");String author=user==null?"":user.optString("login","");String updated=formatGitHubDate(item.optString("updated_at",""));
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);
        TextView kind=pill(pr?"PR":"Issue");kind.setTextColor(pr?tileColors[2]:GREEN);head.addView(kind);
        TextView heading=text("#"+number+"  "+title,15,true);heading.setMaxLines(3);heading.setPadding(dp(8),0,0,0);head.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        if(matches>1){TextView badge=pill("RU ✦ ×"+matches);badge.setTextColor(GREEN);head.addView(badge);}row.addView(head);
        TextView meta=text(full+" · "+author+(updated.isEmpty()?"":" · "+updated),11,false);meta.setTextColor(MUTED);row.addView(meta);
        row.setOnClickListener(v->{if(pr)navigate(()->showPullDetail(full,number));else navigate(()->showIssueDetail(full,number));});card(row);
    }

    private void renderCodeSearchCard(JSONObject item,int matches){
        JSONObject repo=item.optJSONObject("repository");String full=repo==null?"":repo.optString("full_name","");String name=item.optString("name","Файл");String path=item.optString("path",name);String html=item.optString("html_url","");
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon=text("</>",13,true);icon.setTextColor(BLUE);icon.setGravity(Gravity.CENTER);icon.setBackground(round(SUBTLE,10,BORDER,1));head.addView(icon,new LinearLayout.LayoutParams(dp(42),dp(32)));
        TextView title=text(name,15,true);title.setTextColor(BLUE);title.setPadding(dp(9),0,0,0);head.addView(title,new LinearLayout.LayoutParams(0,-2,1));if(matches>1)head.addView(pill("RU ✦ ×"+matches));box.addView(head);
        TextView meta=text(full+"\n"+path,11,false);meta.setTextColor(MUTED);box.addView(meta);
        box.setOnClickListener(v->{if(!html.isEmpty())openWeb(html);else if(!full.isEmpty())navigate(()->showRepository(full));});card(box);
    }

    private void renderPeopleSearchCard(JSONObject item,int matches){
        String login=item.optString("login","");String type=item.optString("type","User");boolean org="Organization".equalsIgnoreCase(type);String html=item.optString("html_url","https://github.com/"+login);
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon=imageIcon(org?R.drawable.ic_organization:R.drawable.ic_profile,org?tileColors[2]:GREEN,13);row.addView(icon,new LinearLayout.LayoutParams(dp(46),dp(46)));
        LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);TextView name=text("@"+login,15,true);name.setTextColor(BLUE);labels.addView(name);TextView meta=text(org?"Организация GitHub":"Пользователь GitHub",11,false);meta.setTextColor(MUTED);labels.addView(meta);row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        if(matches>1)row.addView(pill("RU ✦ ×"+matches));
        row.setOnClickListener(v->{if(org)openWeb(html);else navigate(()->showDeveloperProfile(login));});card(row);
    }

    private void renderAdvancedSearchExplanation(AdvancedSearchPlanner.Plan plan,int uniqueLoaded,long reported,int failed){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
        TextView title=text(plan.expanded()?"RU ✦  Интеллектуальный поиск":"GitHub Search",15,true);title.setTextColor(plan.semantic.smartRequested?GREEN:TEXT);box.addView(title);
        TextView scope=text("Раздел: "+advancedSearchTitle(plan.mode)+" · "+plan.filters.summary(plan.mode),11,false);scope.setTextColor(MUTED);box.addView(scope);
        if(plan.variants.size()>1){TextView variants=text("Искали также:\n• "+String.join("\n• ",plan.variants),11,false);variants.setTextColor(MUTED);variants.setPadding(dp(4),dp(7),dp(4),0);box.addView(variants);}
        if(!plan.rejectedQualifiers.isEmpty()){TextView rejected=text("Не применены несовместимые фильтры: "+String.join(", ",plan.rejectedQualifiers),11,false);rejected.setTextColor(tileColors[3]);rejected.setPadding(dp(4),dp(6),dp(4),0);box.addView(rejected);}
        String stats="Объединено без дублей: "+uniqueLoaded+" · вариантов: "+plan.variants.size();if(reported>0)stats+=" · GitHub сообщил до "+reported;if(failed>0)stats+=" · ошибок вариантов: "+failed;
        TextView meta=text(stats,11,false);meta.setTextColor(MUTED);meta.setPadding(dp(4),dp(7),dp(4),0);box.addView(meta);card(box);
    }

    private String advancedSearchTitle(SearchMode mode){
        if(mode==null)return "Результаты";
        switch(mode){case ISSUES_PRS:return "Issues / Pull Requests";case CODE:return "Поиск кода";case PEOPLE:return "Люди и организации";default:return "Репозитории";}
    }

    private String friendlyAdvancedSearchError(SearchMode mode,Exception error){
        String message=error==null?"Неизвестная ошибка":String.valueOf(error.getMessage());
        if(mode==SearchMode.CODE&&(message.contains("401")||message.contains("403")))return "GitHub ограничил поиск кода для текущей сессии. Подключите GitHub или проверьте доступ приложения GiHubble.\n\n"+message;
        return message;
    }

    private void searchRepos(String q){searchReposPage(q,1,true);}

    private void searchRepos(String q, boolean smart){
        String value=q==null?"":q.trim();
        if(value.isEmpty()){toast("Введите запрос");return;}
        new SmartSearchHistory(prefs).remember(value,smart);
        if(!smart){searchRepos(value);return;}
        SmartSearchPlanner.Plan plan=SmartSearchPlanner.plan(value,true);
        searchSmartReposPage(plan,1,true);
    }

    private void searchReposPage(String q,int page,boolean first){
        if(q.isEmpty()){toast("Введите запрос");return;}if(first){setCurrentScreen(()->searchRepos(q));shell("Результаты",true);}ProgressBar pb=new ProgressBar(this);content.addView(pb);
        io.execute(()->{try{JSONObject root=getJson("https://api.github.com/search/repositories?q="+URLEncoder.encode(q,"UTF-8")+"&per_page=30&page="+page);JSONArray items=root.optJSONArray("items");if(items==null)items=new JSONArray();int total=root.optInt("total_count",items.length());JSONArray finalItems=items;runOnUiThread(()->{content.removeView(pb);if(first)sectionTitle("Найдено: "+total,null);for(int i=0;i<finalItems.length();i++){try{addRepoCard(finalItems.getJSONObject(i));}catch(Exception ignored){}}if(finalItems.length()==30&&page*30<Math.min(total,1000))addLoadMoreButton("Показать ещё результаты",()->searchReposPage(q,page+1,false));});}catch(Exception e){runOnUiThread(()->{content.removeView(pb);errorCard("Ошибка сети",e.getMessage());});}});
    }

    private void searchSmartReposPage(SmartSearchPlanner.Plan plan,int page,boolean first){
        if(plan==null||plan.original.isEmpty()){toast("Введите запрос");return;}
        if(first){
            smartSearchRendered.clear();
            setCurrentScreen(()->searchRepos(plan.original,true));
            shell("Результаты",true);
        }
        ProgressBar pb=new ProgressBar(this);content.addView(pb);
        io.execute(()->{
            List<JSONArray> batches=new ArrayList<>();
            long reported=0;
            int failed=0;
            boolean mayHaveMore=false;
            Exception lastError=null;
            for(String variant:plan.variants){
                try{
                    String url="https://api.github.com/search/repositories?q="+URLEncoder.encode(variant,"UTF-8")+"&per_page=20&page="+page;
                    JSONObject root=getJson(url);
                    JSONArray items=root.optJSONArray("items");if(items==null)items=new JSONArray();
                    batches.add(items);
                    reported+=Math.max(0,root.optInt("total_count",items.length()));
                    if(items.length()==20)mayHaveMore=true;
                }catch(Exception error){failed++;lastError=error;}
            }
            if(batches.isEmpty()){
                Exception error=lastError==null?new IOException("GitHub не вернул результаты поиска"):lastError;
                runOnUiThread(()->{content.removeView(pb);errorCard("Ошибка интеллектуального поиска",error.getMessage());});
                return;
            }
            List<RepositorySearchRanker.Hit> hits=RepositorySearchRanker.merge(batches,plan);
            long finalReported=reported;int finalFailed=failed;boolean finalMayHaveMore=mayHaveMore;
            runOnUiThread(()->{
                content.removeView(pb);
                if(first)renderSmartSearchExplanation(plan,hits.size(),finalReported,finalFailed);
                int added=0;
                for(RepositorySearchRanker.Hit hit:hits){
                    String key=hit.repository.optString("full_name","").toLowerCase(Locale.ROOT);
                    if(key.isEmpty()||!smartSearchRendered.add(key))continue;
                    try{addRepoCard(hit.repository,hit.matchedVariants);}catch(Exception ignored){}
                    added++;
                }
                if(first&&added==0){TextView empty=text("Совпадений не найдено. Попробуйте убрать часть слов или отключить RU ✦ для точного запроса.",13,false);empty.setTextColor(MUTED);card(empty);}
                if(finalMayHaveMore&&page<5)addLoadMoreButton("Показать ещё интеллектуальные результаты",()->searchSmartReposPage(plan,page+1,false));
            });
        });
    }

    private void renderSmartSearchExplanation(SmartSearchPlanner.Plan plan,int uniqueLoaded,long reported,int failed){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
        TextView title=text(plan.expanded()?"RU ✦  Интеллектуальный русский поиск":"RU ✦  Умный режим",15,true);title.setTextColor(GREEN);box.addView(title);
        TextView summary=text(plan.expanded()?"Русский запрос преобразован в технические варианты. Квалификаторы GitHub сохранены без изменений.":"Кириллица не обнаружена — запрос отправлен без перевода.",12,false);summary.setTextColor(MUTED);box.addView(summary);
        if(plan.variants.size()>1){
            TextView variants=text("Искали также:\n• "+String.join("\n• ",plan.variants),11,false);variants.setTextColor(MUTED);variants.setPadding(dp(4),dp(7),dp(4),0);box.addView(variants);
        }
        String stats="Объединено без дублей: "+uniqueLoaded+" · вариантов: "+plan.variants.size();
        if(reported>0)stats+=" · GitHub сообщил до "+reported+" совпадений";
        if(failed>0)stats+=" · не загрузилось вариантов: "+failed;
        TextView meta=text(stats,11,false);meta.setTextColor(MUTED);meta.setPadding(dp(4),dp(7),dp(4),0);box.addView(meta);
        card(box);
    }

    private void addRepoCard(JSONObject r)throws Exception {addRepoCard(r,0);}
    private void addRepoCard(JSONObject r,int smartMatches)throws Exception {
        String full=r.getString("full_name"), desc=r.optString("description","Без описания"), lang=r.optString("language","—"); int stars=r.optInt("stargazers_count");
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark=text("◆",20,true);mark.setTextColor(BLUE);head.addView(mark,new LinearLayout.LayoutParams(dp(38),dp(38)));
        TextView title=text(full,17,true);title.setTextColor(BLUE);head.addView(title,new LinearLayout.LayoutParams(0,-2,1));box.addView(head);
        TextView body=text(desc,14,false);body.setTextColor(MUTED);box.addView(body);
        LinearLayout meta=new LinearLayout(this);meta.setPadding(0,dp(10),0,0);meta.addView(pill("★ "+stars));meta.addView(pill(lang));if(smartMatches>1)meta.addView(pill("RU ✦ ×"+smartMatches));box.addView(meta);
        box.setOnClickListener(v->navigate(() -> showRepository(full))); card(box);
    }

    private void addRepoTile(GridLayout grid,JSONObject repository)throws Exception{
        String full=repository.getString("full_name");
        String shortName=full.contains("/")?full.substring(full.indexOf('/')+1):full;
        LinearLayout tile=new LinearLayout(this);tile.setOrientation(LinearLayout.VERTICAL);tile.setPadding(dp(12),dp(12),dp(12),dp(10));
        tile.setBackground(round(CARD,17,BORDER,1));tile.setElevation(dp(1));
        TextView access=text(repository.optBoolean("private",false)?"PRIVATE":"PUBLIC",10,true);access.setTextColor(repository.optBoolean("private",false)?GREEN:MUTED);access.setPadding(0,0,0,0);tile.addView(access);
        TextView name=text(shortName,16,true);name.setTextColor(BLUE);name.setMaxLines(2);name.setPadding(0,dp(4),0,0);tile.addView(name);
        TextView owner=text(full.contains("/")?full.substring(0,full.indexOf('/')):"",11,false);owner.setTextColor(MUTED);owner.setPadding(0,0,0,0);tile.addView(owner);
        TextView description=text(repository.optString("description","Без описания"),11,false);description.setTextColor(MUTED);description.setMaxLines(2);description.setPadding(0,dp(7),0,0);tile.addView(description,new LinearLayout.LayoutParams(-1,0,1));
        TextView meta=text("★ "+repository.optInt("stargazers_count",0)+" · "+repository.optString("language","—"),11,true);meta.setTextColor(MUTED);meta.setPadding(0,dp(6),0,0);tile.addView(meta);
        tile.setOnClickListener(v->navigate(()->showRepository(full)));
        GridLayout.LayoutParams p=new GridLayout.LayoutParams();p.width=0;p.height=dp(162);p.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);p.setMargins(dp(4),dp(4),dp(4),dp(4));grid.addView(tile,p);
    }

    private TextView pill(String s){TextView t=text(s,12,true);t.setTextColor(MUTED);t.setGravity(Gravity.CENTER);t.setBackground(round(SUBTLE,12,Color.TRANSPARENT,0));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(30));p.setMargins(0,0,dp(8),0);t.setLayoutParams(p);return t;}


    private void showRepositoryOverviewHero(JSONObject repo,String full,String description,
            int stars,int forks,int issues,String branch,String language,String license){
        String name=full.contains("/")?full.substring(full.indexOf('/')+1):full;
        String owner=full.contains("/")?full.substring(0,full.indexOf('/')):full;
        LinearLayout hero=new LinearLayout(this);hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(15),dp(16),dp(15),dp(16));
        GradientDrawable bg=new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{blend(CARD,GREEN,0.07f),CARD});
        bg.setCornerRadius(dp(22));bg.setStroke(dp(1),BORDER);
        hero.setBackground(bg);hero.setElevation(dp(2));
        LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark=imageIcon(R.drawable.ic_repository,GREEN,12);
        mark.setBackground(round(blend(CARD,GREEN,0.10f),18,Color.TRANSPARENT,0));
        head.addView(mark,new LinearLayout.LayoutParams(dp(56),dp(56)));
        LinearLayout names=new LinearLayout(this);names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(9),0,dp(3),0);
        TextView ownerText=text(owner+"/",13,false);ownerText.setTextColor(MUTED);
        TextView title=text(name,22,true);title.setMaxLines(2);
        names.addView(ownerText);names.addView(title);
        head.addView(names,new LinearLayout.LayoutParams(0,-2,1));
        TextView access=text(repo.optBoolean("private",false)?"Private":"Public",10,true);
        access.setTextColor(GREEN);access.setGravity(Gravity.CENTER);
        access.setPadding(dp(5),dp(5),dp(5),dp(5));
        access.setBackground(round(blend(CARD,GREEN,0.09f),12,Color.TRANSPARENT,0));
        head.addView(access);hero.addView(head);
        if(description!=null&&!description.trim().isEmpty()){
            TextView body=text(description,13,false);body.setTextColor(MUTED);
            LinearLayout.LayoutParams descP=new LinearLayout.LayoutParams(-1,-2);
            descP.setMargins(0,dp(8),0,dp(7));hero.addView(body,descP);
        }
        LinearLayout stats=new LinearLayout(this);stats.setPadding(0,dp(7),0,0);
        repoMetric(stats,"★  "+stars,"Stars");repoMetric(stats,"⑂  "+forks,"Forks");
        repoMetric(stats,"!  "+issues,"Issues");hero.addView(stats);
        LinearLayout details=new LinearLayout(this);details.setPadding(0,dp(10),0,0);
        repoInfoPill(details,language==null||language.isEmpty()?"—":language);
        repoInfoPill(details,"Лицензия "+license);
        repoInfoPill(details,"Ветка: "+branch);hero.addView(details);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,0,0,dp(10));content.addView(hero,p);
    }

    private void repoMetric(LinearLayout row,String value,String label){
        LinearLayout chip=new LinearLayout(this);chip.setOrientation(LinearLayout.VERTICAL);
        chip.setGravity(Gravity.CENTER);chip.setBackground(round(CARD,13,BORDER,1));
        TextView v=text(value,13,true);v.setTextColor(GREEN);v.setGravity(Gravity.CENTER);
        TextView l=text(label,10,false);l.setTextColor(MUTED);l.setGravity(Gravity.CENTER);
        chip.addView(v);chip.addView(l);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(57),1);
        p.setMargins(dp(2),0,dp(2),0);row.addView(chip,p);
    }

    private void repoInfoPill(LinearLayout row,String value){
        TextView p=text(value,10,true);p.setTextColor(MUTED);p.setGravity(Gravity.CENTER);
        p.setMaxLines(2);p.setBackground(round(SUBTLE,13,Color.TRANSPARENT,0));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(39),1);
        lp.setMargins(dp(2),0,dp(2),0);row.addView(p,lp);
    }

    private void repoActionCard(GridLayout grid,int icon,String title,String subtitle,Runnable action){
        LinearLayout tile=new LinearLayout(this);tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);tile.setPadding(dp(9),dp(7),dp(7),dp(7));
        tile.setBackground(round(CARD,17,BORDER,1));tile.setElevation(dp(1));
        ImageView image=imageIcon(icon,GREEN,10);
        image.setBackground(round(blend(CARD,GREEN,0.09f),11,Color.TRANSPARENT,0));
        tile.addView(image,new LinearLayout.LayoutParams(dp(29),dp(29)));
        TextView name=text(title,12,true);name.setMaxLines(2);
        name.setPadding(0,dp(3),0,0);tile.addView(name);
        TextView detail=text(subtitle,10,false);detail.setTextColor(MUTED);
        detail.setMaxLines(2);detail.setPadding(0,0,0,0);tile.addView(detail);
        tile.setContentDescription(title+", "+subtitle);tile.setOnClickListener(v->action.run());
        GridLayout.LayoutParams p=new GridLayout.LayoutParams();
        p.width=0;p.height=dp(isLargeText()?118:91);
        p.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);
        p.setMargins(dp(3),dp(3),dp(3),dp(5));grid.addView(tile,p);
    }

    private void repoSectionHeader(String full,String heading,String details,int icon){
        LinearLayout panel=new LinearLayout(this);panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(11),dp(10),dp(11),dp(10));
        GradientDrawable bg=new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{blend(CARD,GREEN,0.06f),CARD});
        bg.setCornerRadius(dp(18));bg.setStroke(dp(1),BORDER);panel.setBackground(bg);
        ImageView mark=imageIcon(icon,GREEN,10);
        mark.setBackground(round(blend(CARD,GREEN,0.10f),12,Color.TRANSPARENT,0));
        panel.addView(mark,new LinearLayout.LayoutParams(dp(43),dp(43)));
        LinearLayout label=new LinearLayout(this);label.setOrientation(LinearLayout.VERTICAL);
        label.setPadding(dp(8),0,0,0);
        TextView t=text(heading,17,true);t.setPadding(0,0,0,0);
        TextView sub=text(full+(details.isEmpty()?"":" · "+details),11,false);
        sub.setTextColor(MUTED);sub.setMaxLines(2);sub.setPadding(0,0,0,0);
        label.addView(t);label.addView(sub);
        panel.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        if(full.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")){
            TextView back=text("‹",23,true);back.setTextColor(GREEN);
            back.setGravity(Gravity.CENTER);back.setContentDescription("Вернуться к репозиторию");
            back.setOnClickListener(v->navigate(()->showRepository(full)));
            panel.addView(back,new LinearLayout.LayoutParams(dp(34),dp(48)));
        }
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
        params.setMargins(0,0,0,dp(12));content.addView(panel,params);
    }

    private void repoReadmePreview(String full,String readme){
        if(readme==null||readme.trim().isEmpty())return;
        sectionTitle("README",null);
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
        String preview=readme.trim();
        if(preview.length()>260)preview=preview.substring(0,260).trim()+"…";
        TextView body=text(preview,13,false);body.setTextColor(MUTED);box.addView(body);
        TextView open=text("Открыть полностью  ›",13,true);open.setTextColor(GREEN);
        open.setGravity(Gravity.END);box.addView(open);
        box.setOnClickListener(v->navigate(()->showReadme(full,readme)));
        card(box);
    }

    private void showRepository(String full){
        if(isSignedIn()&&full.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))
            RepositoryActivityFeed.noteVisit(prefs,currentAccountId(),full,
                    System.currentTimeMillis());
        rememberScreen("repository",routeArgs("full",full));
        setCurrentScreen(() -> showRepository(full));
        shell(full,true);
        final long repoGeneration=screenGeneration.get();
        final String repoAccount=currentAccountId();
        ProgressBar pb=new ProgressBar(this); content.addView(pb);
        io.execute(()->{
            try{
                RepositoryController.Overview overview=repositoryController.loadOverview(full);
                JSONObject r=overview.repository;
                String desc=r.optString("description","Без описания");
                int stars=r.optInt("stargazers_count"),forks=r.optInt("forks_count"),issues=r.optInt("open_issues_count");
                String branch=r.optString("default_branch","main"), language=r.optString("language","—");
                String license=r.optJSONObject("license")!=null?r.optJSONObject("license").optString("spdx_id","—"):"—";
                String readme=overview.readme;
                Boolean starred=isSignedIn()?repositoryController.isStarred(full):null;
                prefs.edit().putString("cache_repo_"+full,r.toString()).putString("cache_readme_"+full,readme).putString("cache_repo_updated_"+full,r.optString("updated_at","")).apply();
                String finalReadme=readme;
                runOnUiThread(()->{
                    if(repoGeneration!=screenGeneration.get()
                            ||!repoAccount.equals(currentAccountId())
                            ||!full.equals(currentTitle))return;
                    content.removeView(pb);
                    showRepositoryOverviewHero(r,full,desc,stars,forks,issues,branch,language,license);
                    Button buildShortcut=button("⬡  Сборки и артефакты  ›");
                    buildShortcut.setOnClickListener(v->navigate(()->showBuildCenter(full)));
                    LinearLayout.LayoutParams buildP=new LinearLayout.LayoutParams(-1,dp(54));
                    buildP.setMargins(0,dp(4),0,dp(10));content.addView(buildShortcut,buildP);
                    sectionTitle("Основные разделы",null);
                    GridLayout tabs=new GridLayout(this);tabs.setColumnCount(adaptiveColumns(3));
                    repoActionCard(tabs,R.drawable.ic_home,"Обзор","О проекте",()->navigate(()->showRepository(full)));
                    repoActionCard(tabs,R.drawable.ic_repository,"Файлы","Код и структура",()->navigate(()->showRepositoryFiles(full,branch,"")));
                    repoActionCard(tabs,R.drawable.ic_repository,"README","Документация",()->navigate(()->showReadme(full,finalReadme)));
                    repoActionCard(tabs,R.drawable.ic_issue,"Issues",issues+" открыто",()->navigate(()->showRepoIssues(full)));
                    repoActionCard(tabs,R.drawable.ic_pull_request,"Pull Requests","Изменения",()->navigate(()->showRepoPulls(full)));
                    repoActionCard(tabs,R.drawable.ic_discussion,"Обсуждения","Сообщество",()->navigate(()->showRepoDiscussions(full)));
                    repoActionCard(tabs,R.drawable.ic_release,"Релизы","Публикации",()->navigate(()->showRepoReleases(full)));
                    repoActionCard(tabs,R.drawable.ic_copilot,"Copilot","ИИ-помощник",()->navigate(()->showCopilotRepository(full)));
                    content.addView(tabs);
                    sectionTitle("Дополнительные действия",null);
                    String owner=full.contains("/")?full.substring(0,full.indexOf('/')):full;
                    GridLayout actions=new GridLayout(this);actions.setColumnCount(adaptiveColumns(2));
                    repoActionCard(actions,R.drawable.ic_repository,"Ветка: "+branch,"Выбрать ветку",()->showBranchChooser(full,branch));
                    repoActionCard(actions,R.drawable.ic_profile,"Профиль @"+owner,"Автор проекта",()->navigate(()->showDeveloperProfile(owner)));
                    repoActionCard(actions,R.drawable.ic_history,trackingDb.isTracked(owner)?"Не отслеживать":"Отслеживать","Активность автора",()->toggleDeveloperTracking(owner));
                    if(isSignedIn()){
                        if(starred!=null)repoActionCard(actions,R.drawable.ic_star,starred?"★ Убрать звезду":"☆ Поставить звезду","GitHub Stars",()->toggleRepositoryStar(full,starred));
                        else repoActionCard(actions,R.drawable.ic_star,"Звезда недоступна","Проверьте права",()->toast("Проверьте сеть и разрешения GitHub"));
                        repoActionCard(actions,R.drawable.ic_settings,"Управление","Настройки проекта",()->navigate(()->showRepositoryAdmin(full,r)));
                    }
                    repoActionCard(actions,R.drawable.ic_star,"Избранное","Сохранить проект",()->{saveFavorite(full);toast("Добавлено в избранное");});
                    repoActionCard(actions,R.drawable.ic_globe,"Открыть GitHub","В браузере",()->openRepoUrl(full,""));
                    repoActionCard(actions,R.drawable.ic_history,"Центр наблюдения","Ваша активность",()->navigate(this::showTracking));
                    content.addView(actions);
                    repoReadmePreview(full,finalReadme);
                });
            }catch(Exception e){
                String cached=prefs.getString("cache_repo_"+full,"");
                String cachedReadme=prefs.getString("cache_readme_"+full,"");
                runOnUiThread(()->{
                    if(repoGeneration!=screenGeneration.get()
                            ||!repoAccount.equals(currentAccountId())
                            ||!full.equals(currentTitle))return;
                    content.removeView(pb);
                    if(!cached.isEmpty()){
                        toast("Офлайн: показана сохранённая версия");
                        showCachedRepository(full,cached,cachedReadme);
                    }else errorCard("Не удалось открыть репозиторий",e.getMessage());
                });
            }
        });
    }

    private void showCopilotRepository(String full){
        stopCopilotForegroundPolling();
        rememberScreen("copilot_repo",routeArgs("full",full));
        setCurrentScreen(() -> showCopilotRepository(full));
        shell("Copilot",true);
        repoSectionHeader(full,"Copilot","Cloud Agent",R.drawable.ic_copilot);
        if(!isSignedIn()){accountRequiredCard("Подключите GitHub, чтобы запускать задачи Copilot.");return;}
        TextView note=text("Copilot работает на стороне GitHub: создаёт ветку, изменяет код и при необходимости открывает Pull Request. Перед запуском GitHubble выполняет безопасную проверку разрешений без изменения репозитория.",12,false);
        note.setTextColor(MUTED);card(note);
        ProgressBar pb=new ProgressBar(this);content.addView(pb);
        io.execute(() -> {
            JSONObject repo=null;JSONObject tasksRoot=null;String taskError="";String branch="main";CopilotPermissionReport report;
            try{repo=getJson("https://api.github.com/repos/"+full);branch=repo.optString("default_branch","main");}catch(Exception e){taskError=e.getMessage();}
            report=features.copilot.checkPermissionMatrix(full);
            try{tasksRoot=features.copilot.listRepositoryTasks(full,1,"",false);}catch(Exception e){taskError=e.getMessage();}
            final JSONObject finalTasksRoot=tasksRoot;final String finalTaskError=taskError;final String finalBranch=branch;final CopilotPermissionReport finalReport=report;
            runOnUiThread(() -> {
                if(!"Copilot".equals(currentTitle))return;
                content.removeView(pb);
                LinearLayout availability=new LinearLayout(this);availability.setOrientation(LinearLayout.VERTICAL);
                TextView status=text(finalReport.readyForSafeAttempt()?"Copilot API готов к безопасной попытке запуска":"Нужна настройка доступа Copilot",15,true);
                status.setTextColor(finalReport.readyForSafeAttempt()?GREEN:Color.rgb(200,120,40));availability.addView(status);
                TextView details=text("Проверено: "+finalReport.passedCount()+" из "+finalReport.totalCount()+" пунктов. Право Agent tasks: write окончательно подтверждается GitHub только при создании задачи.",12,false);details.setTextColor(MUTED);availability.addView(details);card(availability);

                GridLayout tools=new GridLayout(this);tools.setColumnCount(adaptiveColumns(2));
                addAction(tools,"Матрица разрешений",()->navigate(()->showCopilotPermissionMatrix(full)));
                addAction(tools,"Custom agents",()->navigate(()->showCopilotCustomAgents(full,finalBranch)));
                content.addView(tools);

                Button create=button("Новая задача Copilot");
                create.setEnabled(finalReport.readyForSafeAttempt());
                create.setAlpha(finalReport.readyForSafeAttempt()?1f:.55f);
                create.setOnClickListener(v->showNewCopilotTaskDialog(full,finalBranch,""));
                content.addView(create,new LinearLayout.LayoutParams(-1,dp(52)));

                sectionTitle("Быстрые задачи",null);
                GridLayout quick=new GridLayout(this);quick.setColumnCount(adaptiveColumns(2));
                addAction(quick,"Исправить ошибку",()->showNewCopilotTaskDialog(full,finalBranch,"Найди и исправь описанную ошибку. Проанализируй причины, внеси минимальные безопасные изменения и добавь или обнови тесты."));
                addAction(quick,"Реализовать функцию",()->showNewCopilotTaskDialog(full,finalBranch,"Реализуй новую функцию по описанию. Соблюдай архитектуру проекта, обнови документацию и добавь тесты."));
                addAction(quick,"Создать тесты",()->showNewCopilotTaskDialog(full,finalBranch,"Проанализируй текущий код и добавь недостающие unit- и интеграционные тесты для критичных сценариев."));
                addAction(quick,"Обновить документацию",()->showNewCopilotTaskDialog(full,finalBranch,"Обнови README и техническую документацию так, чтобы они соответствовали текущей реализации проекта."));
                content.addView(quick);

                sectionTitle("Задачи репозитория",null);
                if(finalTasksRoot!=null){
                    JSONArray tasks=CopilotAgentController.tasks(finalTasksRoot);
                    if(tasks.length()==0)card(text("Задач Copilot для этого репозитория пока нет.",13,false));
                    for(int i=0;i<tasks.length();i++)renderCopilotTaskCard(tasks.optJSONObject(i),full);
                    if(tasks.length()==30)addLoadMoreButton("Показать ещё задачи",()->showCopilotRepositoryTasksPage(full,2,false));
                }else renderCopilotPermissionProblem(finalTaskError);

                LinearLayout webActions=new LinearLayout(this);
                Button settings=button("Настройки Copilot");settings.setOnClickListener(v->openWeb("https://github.com/settings/copilot"));
                Button installations=button("Разрешения GiHubble");installations.setOnClickListener(v->openWeb("https://github.com/settings/installations"));
                webActions.addView(settings,new LinearLayout.LayoutParams(0,dp(48),1));LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(0,dp(48),1);ip.setMargins(dp(7),0,0,0);webActions.addView(installations,ip);content.addView(webActions);
            });
        });
    }

    private void showCopilotRepositoryTasksPage(String full,int page,boolean first){
        ProgressBar pb=new ProgressBar(this);content.addView(pb);
        io.execute(()->{try{JSONObject root=features.copilot.listRepositoryTasks(full,page,"",false);JSONArray tasks=CopilotAgentController.tasks(root);runOnUiThread(()->{content.removeView(pb);for(int i=0;i<tasks.length();i++)renderCopilotTaskCard(tasks.optJSONObject(i),full);if(tasks.length()==30)addLoadMoreButton("Показать ещё задачи",()->showCopilotRepositoryTasksPage(full,page+1,false));});}catch(Exception e){runOnUiThread(()->{content.removeView(pb);renderCopilotPermissionProblem(e.getMessage());});}});
    }

    private void showNewCopilotTaskDialog(String full,String defaultBranch,String template){showNewCopilotTaskDialog(full,defaultBranch,template,"");}

    private void showNewCopilotTaskDialog(String full,String defaultBranch,String template,String preselectedAgent){
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(18),dp(4),dp(18),0);
        EditText prompt=new EditText(this);prompt.setHint("Опишите задачу для Copilot");prompt.setMinLines(6);prompt.setGravity(Gravity.TOP);prompt.setText(template);prompt.setTextColor(TEXT);prompt.setHintTextColor(MUTED);form.addView(prompt);
        EditText base=new EditText(this);base.setHint("Базовая ветка");base.setSingleLine(true);base.setText(defaultBranch==null||defaultBranch.isEmpty()?"main":defaultBranch);form.addView(base);
        EditText head=new EditText(this);head.setHint("Существующая рабочая ветка (необязательно)");head.setSingleLine(true);form.addView(head);
        TextView modelLabel=text("Модель",12,true);modelLabel.setPadding(0,dp(8),0,0);form.addView(modelLabel);
        Spinner model=new Spinner(this);model.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,copilotModelLabels()));form.addView(model,new LinearLayout.LayoutParams(-1,dp(48)));
        EditText customAgent=new EditText(this);customAgent.setHint("Custom agent (необязательно)");customAgent.setSingleLine(true);customAgent.setText(preselectedAgent);form.addView(customAgent);
        Button pickAgent=button("Выбрать из .github/agents");pickAgent.setOnClickListener(v->loadCustomAgentPicker(full,base.getText().toString(),customAgent));form.addView(pickAgent,new LinearLayout.LayoutParams(-1,dp(46)));
        CheckBox createPr=new CheckBox(this);createPr.setText("Создать Pull Request");createPr.setChecked(true);form.addView(createPr);
        ScrollView scroll=new ScrollView(this);scroll.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Новая задача Copilot").setView(scroll).setPositiveButton("Запустить",null).setNegativeButton("Отмена",null).create();
        dialog.setOnShowListener(x->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String taskPrompt=prompt.getText().toString().trim();if(taskPrompt.isEmpty()){prompt.setError("Опишите задачу");return;}
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            io.execute(()->{try{
                CopilotPermissionReport matrix=features.copilot.checkPermissionMatrix(full);
                if(!matrix.readyForSafeAttempt())throw new IllegalStateException("Безопасная проверка разрешений не пройдена. Откройте матрицу разрешений Copilot.");
                JSONObject task=features.copilot.startTask(full,taskPrompt,copilotModelValue(model.getSelectedItemPosition()),customAgent.getText().toString(),createPr.isChecked(),base.getText().toString(),head.getText().toString());
                String id=task.optString("id","");CopilotTaskMonitorJobService.runNow(this);
                runOnUiThread(()->{dialog.dismiss();toast("Задача Copilot запущена");if(!id.isEmpty())navigate(()->showCopilotTaskDetail(full,id));else navigate(()->showCopilotRepository(full));});
            }catch(Exception e){runOnUiThread(()->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);showCopilotApiError("Не удалось запустить Copilot",e);});}});
        }));dialog.show();
    }

    private void loadCustomAgentPicker(String full,String branch,EditText target){
        ProgressDialog progress=ProgressDialog.show(this,"Custom agents","Загружаем профили…",true,false);
        io.execute(()->{try{JSONArray agents=features.copilot.listCustomAgents(full,branch);runOnUiThread(()->{progress.dismiss();if(agents.length()==0){new AlertDialog.Builder(this).setTitle("Custom agents не найдены").setMessage("В выбранной ветке нет файлов .github/agents/*.agent.md").setPositiveButton("ОК",null).show();return;}String[] labels=new String[agents.length()+1];labels[0]="Без custom agent";for(int i=0;i<agents.length();i++){JSONObject a=agents.optJSONObject(i);labels[i+1]=a.optString("id",a.optString("name","Agent"));}new AlertDialog.Builder(this).setTitle("Выберите custom agent").setItems(labels,(d,w)->target.setText(w==0?"":labels[w])).setNegativeButton("Отмена",null).show();});}catch(Exception e){runOnUiThread(()->{progress.dismiss();showCopilotApiError("Не удалось загрузить custom agents",e);});}});
    }

    private void showCopilotCustomAgents(String full,String branch){
        stopCopilotForegroundPolling();rememberScreen("copilot_agents",routeArgs("full",full,"branch",branch));setCurrentScreen(()->showCopilotCustomAgents(full,branch));shell("Custom agents",true);heroBanner("Профили Copilot",full+" · "+branch,R.drawable.ic_copilot);
        TextView info=text("GitHubble читает профили из .github/agents/*.agent.md. При запуске API передаётся имя файла без расширения.",12,false);info.setTextColor(MUTED);card(info);
        ProgressBar pb=new ProgressBar(this);content.addView(pb);
        io.execute(()->{try{JSONArray agents=features.copilot.listCustomAgents(full,branch);runOnUiThread(()->{content.removeView(pb);if(agents.length()==0){card(text("Custom agents не найдены в выбранной ветке.",13,false));Button create=button("Создать профиль на GitHub");create.setOnClickListener(v->openWeb("https://github.com/"+full+"/new/"+branch+"?filename=.github/agents/my-agent.agent.md"));content.addView(create);return;}sectionTitle("Найдено: "+agents.length(),null);for(int i=0;i<agents.length();i++){JSONObject agent=agents.optJSONObject(i);if(agent==null)continue;LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.addView(text(agent.optString("id","Agent"),15,true));TextView path=text(agent.optString("path",""),11,false);path.setTextColor(MUTED);box.addView(path);GridLayout actions=new GridLayout(this);actions.setColumnCount(adaptiveColumns(2));String id=agent.optString("id","");String url=agent.optString("html_url","");addAction(actions,"Использовать",()->showNewCopilotTaskDialog(full,branch,"",id));if(!url.isEmpty())addAction(actions,"Открыть файл",()->openWeb(url));box.addView(actions);card(box);}}); }catch(Exception e){runOnUiThread(()->{content.removeView(pb);showCopilotApiError("Не удалось загрузить custom agents",e);});}});
    }

    private void showCopilotPermissionMatrix(String full){
        stopCopilotForegroundPolling();rememberScreen("copilot_permissions",routeArgs("full",full));setCurrentScreen(()->showCopilotPermissionMatrix(full));shell("Разрешения Copilot",true);heroBanner("Диагностика подключения",full,R.drawable.ic_copilot);
        TextView info=text("Проверка выполняет только чтение: никаких Issue, веток, коммитов или задач не создаётся. Запись Agent tasks проверяется только при реальном запуске задачи.",12,false);info.setTextColor(MUTED);card(info);
        ProgressBar pb=new ProgressBar(this);content.addView(pb);
        io.execute(()->{CopilotPermissionReport report=features.copilot.checkPermissionMatrix(full);runOnUiThread(()->{content.removeView(pb);renderCopilotPermissionReport(report);GridLayout actions=new GridLayout(this);actions.setColumnCount(adaptiveColumns(2));addAction(actions,"Проверить снова",()->showCopilotPermissionMatrix(full));addAction(actions,"Разрешения GiHubble",()->openWeb("https://github.com/settings/installations"));addAction(actions,"Настройки Copilot",()->openWeb("https://github.com/settings/copilot"));addAction(actions,"Назад к Copilot",()->navigate(()->showCopilotRepository(full)));content.addView(actions);});});
    }

    private void renderCopilotPermissionReport(CopilotPermissionReport report){
        TextView summary=text((report.readyForSafeAttempt()?"Базовая готовность подтверждена":"Найдены ограничения")+" · "+report.passedCount()+"/"+report.totalCount(),16,true);summary.setTextColor(report.readyForSafeAttempt()?GREEN:Color.rgb(210,120,35));card(summary);
        JSONArray checks=report.checks();for(int i=0;i<checks.length();i++){JSONObject check=checks.optJSONObject(i);if(check==null)continue;LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);TextView title=text((check.optBoolean("passed")?"✓ ":"○ ")+check.optString("title","Проверка"),14,true);title.setTextColor(check.optBoolean("passed")?GREEN:(check.optBoolean("required")?Color.rgb(210,90,70):MUTED));row.addView(title);TextView detail=text(check.optString("detail",""),11,false);detail.setTextColor(MUTED);row.addView(detail);card(row);}
    }

    private void showCopilotTasks(){showCopilotTasksPage(1,true,"",false);}
    private void showCopilotTasksPage(int page,boolean first,String states){showCopilotTasksPage(page,first,states,false);}

    private void showCopilotTasksPage(int page,boolean first,String states,boolean archived){
        if(first){
            stopCopilotForegroundPolling();rememberScreen("copilot_tasks",routeArgs("states",states,"archived",archived));setCurrentScreen(()->showCopilotTasksPage(1,true,states,archived));shell("Задачи Copilot",true);heroBanner("Copilot Cloud Agent",archived?"Архив задач":"Все задачи вашего аккаунта",R.drawable.ic_copilot);
            if(!isSignedIn()){accountRequiredCard("Подключите GitHub, чтобы увидеть задачи Copilot.");return;}
            renderCopilotMonitorCard();
            GridLayout filters=new GridLayout(this);filters.setColumnCount(adaptiveColumns(2));addAction(filters,"Все",()->showCopilotTasksPage(1,true,"",false));addAction(filters,"Активные",()->showCopilotTasksPage(1,true,"queued,in_progress,idle,waiting_for_user",false));addAction(filters,"Завершённые",()->showCopilotTasksPage(1,true,"completed,failed,timed_out,cancelled",false));addAction(filters,"Архив",()->showCopilotTasksPage(1,true,"",true));content.addView(filters);
        }
        ProgressBar pb=new ProgressBar(this);content.addView(pb);
        io.execute(()->{try{JSONObject root=features.copilot.listUserTasks(page,states,archived);JSONArray tasks=CopilotAgentController.tasks(root);int active=0;for(int i=0;i<tasks.length();i++){String state=tasks.optJSONObject(i)==null?"":tasks.optJSONObject(i).optString("state","");if(CopilotAgentController.isActiveState(state))active++;}int finalActive=active;runOnUiThread(()->{content.removeView(pb);if(first){prefs.edit().putInt("copilot_task_count",tasks.length()).putInt("copilot_active_count",finalActive).apply();sectionTitle(tasks.length()==0?"Задач нет":(archived?"Архив: ":"Задачи: ")+tasks.length(),null);}if(tasks.length()==0&&first){card(text(archived?"Архив задач пуст.":"GitHub не вернул задач Copilot. Создайте задачу из карточки репозитория.",13,false));return;}for(int i=0;i<tasks.length();i++)renderCopilotTaskCard(tasks.optJSONObject(i),"");if(tasks.length()==30)addLoadMoreButton("Показать ещё задачи",()->showCopilotTasksPage(page+1,false,states,archived));});}catch(Exception e){runOnUiThread(()->{content.removeView(pb);renderCopilotPermissionProblem(e.getMessage());});}});
    }

    private void renderCopilotMonitorCard(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);TextView title=text("Фоновый мониторинг",15,true);top.addView(title,new LinearLayout.LayoutParams(0,-2,1));Switch enabled=new Switch(this);enabled.setChecked(prefs.getBoolean("copilot_monitor_enabled",false));top.addView(enabled);box.addView(top);
        int interval=prefs.getInt("copilot_monitor_interval_minutes",60);long last=prefs.getLong("copilot_monitor_last_success",0L);String status=prefs.getString("copilot_monitor_last_result","Проверка ещё не запускалась");TextView meta=text("Интервал: "+copilotIntervalLabel(interval)+(last>0?" · последняя: "+android.text.format.DateFormat.format("dd.MM HH:mm",last):"")+"\n"+status,11,false);meta.setTextColor(MUTED);box.addView(meta);
        GridLayout actions=new GridLayout(this);actions.setColumnCount(adaptiveColumns(2));addAction(actions,"Изменить интервал",this::chooseCopilotMonitorInterval);addAction(actions,"Проверить сейчас",()->{CopilotTaskMonitorJobService.runNow(this);toast("Проверка Copilot поставлена в очередь");});box.addView(actions);
        enabled.setOnCheckedChangeListener((b,checked)->{prefs.edit().putBoolean("copilot_monitor_enabled",checked).apply();if(checked){CopilotTaskMonitorJobService.schedule(this,prefs.getInt("copilot_monitor_interval_minutes",60));CopilotTaskMonitorJobService.runNow(this);requestNotificationPermissionIfNeeded();toast("Мониторинг Copilot включён");}else{CopilotTaskMonitorJobService.cancel(this);toast("Мониторинг Copilot выключен");}});card(box);
    }

    private void chooseCopilotMonitorInterval(){
        String[] labels={"15 минут","30 минут","1 час","3 часа","6 часов","12 часов"};int[] values={15,30,60,180,360,720};int current=prefs.getInt("copilot_monitor_interval_minutes",60);int selected=2;for(int i=0;i<values.length;i++)if(values[i]==current)selected=i;final int initial=selected;
        new AlertDialog.Builder(this).setTitle("Частота фоновой проверки").setSingleChoiceItems(labels,selected,null).setPositiveButton("Сохранить",(d,w)->{int index=((AlertDialog)d).getListView().getCheckedItemPosition();if(index<0)index=initial;int value=values[index];prefs.edit().putInt("copilot_monitor_interval_minutes",value).apply();if(prefs.getBoolean("copilot_monitor_enabled",false))CopilotTaskMonitorJobService.schedule(this,value);showCopilotTasks();}).setNegativeButton("Отмена",null).show();
    }

    private String copilotIntervalLabel(int minutes){if(minutes<60)return minutes+" мин";if(minutes%60==0)return (minutes/60)+" ч";return minutes+" мин";}

    private void renderCopilotTaskCard(JSONObject task,String fallbackFull){
        if(task==null)return;String full=CopilotAgentController.repositoryFromTask(task);if(full.isEmpty())full=fallbackFull;String finalFull=full;String id=task.optString("id","");String state=task.optString("state","");
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);TextView name=text(task.optString("name","Задача Copilot"),15,true);name.setMaxLines(2);head.addView(name,new LinearLayout.LayoutParams(0,-2,1));TextView badge=text(CopilotAgentController.stateLabel(state),10,true);badge.setTextColor(copilotStateColor(state));badge.setGravity(Gravity.CENTER);badge.setBackground(round(SUBTLE,10,BORDER,1));badge.setPadding(dp(8),0,dp(8),0);head.addView(badge,new LinearLayout.LayoutParams(-2,dp(28)));row.addView(head);
        int pr=CopilotAgentController.pullNumber(task);boolean archived=!task.optString("archived_at","").isEmpty();String meta=(finalFull.isEmpty()?"":finalFull+" · ")+formatGitHubDate(task.optString("updated_at",""))+(pr>0?" · PR #"+pr:"")+(archived?" · архив":"");TextView m=text(meta,11,false);m.setTextColor(MUTED);row.addView(m);
        row.setOnClickListener(v->{if(!id.isEmpty())navigate(()->showCopilotTaskDetail(finalFull,id));else if(!task.optString("html_url","").isEmpty())openWeb(task.optString("html_url"));});card(row);
    }

    private void showCopilotTaskDetail(String full,String taskId){
        stopCopilotForegroundPolling();activeCopilotTaskId=taskId;
        rememberScreen("copilot_task",routeArgs("full",full,"task",taskId));setCurrentScreen(()->showCopilotTaskDetail(full,taskId));shell("Задача Copilot",true);ProgressBar pb=new ProgressBar(this);content.addView(pb);
        io.execute(()->{try{JSONObject task=full==null||full.isEmpty()?features.copilot.getTask(taskId):features.copilot.getTask(full,taskId);String resolved=CopilotAgentController.repositoryFromTask(task);if(resolved.isEmpty())resolved=full==null?"":full;String finalFull=resolved;runOnUiThread(()->{content.removeView(pb);heroBanner(task.optString("name","Задача Copilot"),finalFull.isEmpty()?"GitHub Copilot":finalFull,R.drawable.ic_copilot);String state=task.optString("state","");TextView status=text(CopilotAgentController.stateLabel(state),15,true);status.setTextColor(copilotStateColor(state));card(status);
            String archivedAt=task.optString("archived_at","");if(!archivedAt.isEmpty()){TextView archived=text("Архивирована · "+formatGitHubDate(archivedAt),12,true);archived.setTextColor(MUTED);card(archived);}
            JSONArray sessions=task.optJSONArray("sessions");if(sessions!=null&&sessions.length()>0){sectionTitle("Сеансы: "+sessions.length(),null);for(int i=0;i<sessions.length();i++){JSONObject session=sessions.optJSONObject(i);if(session==null)continue;LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.addView(text(session.optString("name","Сеанс Copilot"),14,true));String refs=session.optString("head_ref","")+(session.optString("base_ref","").isEmpty()?"":" → "+session.optString("base_ref",""));TextView si=text(CopilotAgentController.stateLabel(session.optString("state",""))+(session.optString("model","").isEmpty()?"":" · "+session.optString("model"))+(refs.isEmpty()?"":" · "+refs),11,false);si.setTextColor(MUTED);box.addView(si);String prompt=session.optString("prompt","");if(!prompt.isEmpty()){TextView p=text(prompt,12,false);p.setTextColor(MUTED);p.setMaxLines(8);box.addView(p);}String sessionUrl=CopilotAgentController.sessionUrl(session,task);if(!sessionUrl.isEmpty()){Button open=button("Открыть журнал сеанса");open.setOnClickListener(v->openWeb(sessionUrl));box.addView(open,new LinearLayout.LayoutParams(-1,dp(44)));}card(box);}}
            JSONArray artifacts=task.optJSONArray("artifacts");int pr=CopilotAgentController.pullNumber(task);if(artifacts!=null&&artifacts.length()>0){sectionTitle("Результаты: "+artifacts.length(),null);for(int i=0;i<artifacts.length();i++)renderCopilotArtifact(artifacts.optJSONObject(i),finalFull);}
            int foreground=prefs.getInt("copilot_foreground_refresh_seconds",30);TextView refresh=text("Автообновление экрана: "+(foreground<=0?"выключено":foreground+" сек"),11,false);refresh.setTextColor(MUTED);card(refresh);
            GridLayout actions=new GridLayout(this);actions.setColumnCount(adaptiveColumns(2));addAction(actions,"Обновить",()->showCopilotTaskDetail(finalFull,taskId));addAction(actions,"Частота обновления",()->chooseCopilotForegroundInterval(finalFull,taskId));String html=task.optString("html_url","");if(!html.isEmpty())addAction(actions,"Открыть на GitHub",()->openWeb(html));if(pr>0&&!finalFull.isEmpty())addAction(actions,"Открыть Pull Request",()->navigate(()->showPullDetail(finalFull,pr)));if(!finalFull.isEmpty())addAction(actions,"Центр Copilot",()->navigate(()->showCopilotRepository(finalFull)));addAction(actions,archivedAt.isEmpty()?"Архивировать":"Управление архивом",()->showCopilotArchiveInfo(html,archivedAt.isEmpty()));content.addView(actions);
            if(CopilotAgentController.isActiveState(state))scheduleCopilotForegroundPolling(finalFull,taskId);
        });}catch(Exception e){runOnUiThread(()->{content.removeView(pb);showCopilotApiError("Не удалось загрузить задачу",e);});}});
    }

    private void renderCopilotArtifact(JSONObject artifact,String full){
        if(artifact==null)return;LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.addView(text(CopilotAgentController.artifactLabel(artifact),14,true));String provider=artifact.optString("provider","");if(!provider.isEmpty()){TextView p=text("Источник: "+provider,11,false);p.setTextColor(MUTED);box.addView(p);}String type=artifact.optString("type","");JSONObject data=artifact.optJSONObject("data");int number=data==null?0:data.optInt("number",data.optInt("id",0));String url=CopilotAgentController.artifactUrl(artifact);if("pull".equals(type)&&number>0&&!full.isEmpty()){Button open=button("Открыть Pull Request");open.setOnClickListener(v->navigate(()->showPullDetail(full,number)));box.addView(open,new LinearLayout.LayoutParams(-1,dp(44)));}else if(!url.isEmpty()){Button open=button("Открыть результат");open.setOnClickListener(v->openWeb(url));box.addView(open,new LinearLayout.LayoutParams(-1,dp(44)));}card(box);
    }

    private void chooseCopilotForegroundInterval(String full,String taskId){
        String[] labels={"Выключено","15 секунд","30 секунд","1 минута","2 минуты"};int[] values={0,15,30,60,120};int current=prefs.getInt("copilot_foreground_refresh_seconds",30);int selected=2;for(int i=0;i<values.length;i++)if(values[i]==current)selected=i;final int initial=selected;
        new AlertDialog.Builder(this).setTitle("Автообновление задачи").setSingleChoiceItems(labels,selected,null).setPositiveButton("Сохранить",(d,w)->{int index=((AlertDialog)d).getListView().getCheckedItemPosition();if(index<0)index=initial;prefs.edit().putInt("copilot_foreground_refresh_seconds",values[index]).apply();showCopilotTaskDetail(full,taskId);}).setNegativeButton("Отмена",null).show();
    }

    private void scheduleCopilotForegroundPolling(String full,String taskId){
        stopCopilotForegroundPolling();int seconds=prefs.getInt("copilot_foreground_refresh_seconds",30);if(seconds<=0)return;activeCopilotTaskId=taskId;copilotPollRunnable=()->{if(taskId.equals(activeCopilotTaskId)&&"Задача Copilot".equals(currentTitle))showCopilotTaskDetail(full,taskId);};copilotPollHandler.postDelayed(copilotPollRunnable,seconds*1000L);
    }

    private void stopCopilotForegroundPolling(){if(copilotPollRunnable!=null)copilotPollHandler.removeCallbacks(copilotPollRunnable);copilotPollRunnable=null;activeCopilotTaskId="";}

    private void showCopilotArchiveInfo(String htmlUrl,boolean archive){
        String action=archive?"Архивировать":"Управлять архивом";new AlertDialog.Builder(this).setTitle(action+" на GitHub").setMessage("GitHub REST API позволяет читать архивные задачи через фильтр is_archived, но публичный endpoint для archive/unarchive пока не опубликован. GitHubble откроет официальный экран задачи, где доступно управление архивом.").setPositiveButton("Открыть GitHub",(d,w)->{if(htmlUrl!=null&&!htmlUrl.isEmpty())openWeb(htmlUrl);else openWeb("https://github.com/copilot/agents");}).setNegativeButton("Закрыть",null).show();
    }

    private void showAssignIssueToCopilot(String full,int number,JSONObject issue){
        ProgressDialog progress=ProgressDialog.show(this,"Copilot","Проверяем доступ и ветку…",true,false);
        io.execute(()->{try{JSONObject repo=getJson("https://api.github.com/repos/"+full);String branch=repo.optString("default_branch","main");CopilotPermissionReport matrix=features.copilot.checkPermissionMatrix(full);boolean available=matrix.copilotAssignable();runOnUiThread(()->{progress.dismiss();if(!available){new AlertDialog.Builder(this).setTitle("Copilot недоступен").setMessage("GitHub не показывает Copilot как доступного исполнителя. Откройте матрицу разрешений и проверьте подписку, политику репозитория и разрешения GiHubble.").setPositiveButton("Матрица разрешений",(d,w)->navigate(()->showCopilotPermissionMatrix(full))).setNegativeButton("Закрыть",null).show();return;}showAssignIssueCopilotDialog(full,number,issue,branch);});}catch(Exception e){runOnUiThread(()->{progress.dismiss();showCopilotApiError("Не удалось проверить Copilot",e);});}});
    }

    private void showAssignIssueCopilotDialog(String full,int number,JSONObject issue,String defaultBranch){
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(18),dp(4),dp(18),0);
        TextView issueInfo=text("Issue #"+number+" · "+issue.optString("title",""),13,true);issueInfo.setMaxLines(3);form.addView(issueInfo);
        EditText branch=new EditText(this);branch.setHint("Базовая ветка");branch.setSingleLine(true);branch.setText(defaultBranch);form.addView(branch);
        EditText instructions=new EditText(this);instructions.setHint("Дополнительные инструкции");instructions.setMinLines(4);instructions.setText("Проанализируй Issue #"+number+", реализуй исправление или функцию, добавь тесты и подготовь Pull Request.");form.addView(instructions);
        Spinner model=new Spinner(this);model.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,copilotModelLabels()));form.addView(model,new LinearLayout.LayoutParams(-1,dp(48)));
        EditText customAgent=new EditText(this);customAgent.setHint("Custom agent (необязательно)");customAgent.setSingleLine(true);form.addView(customAgent);Button pick=button("Выбрать из .github/agents");pick.setOnClickListener(v->loadCustomAgentPicker(full,branch.getText().toString(),customAgent));form.addView(pick,new LinearLayout.LayoutParams(-1,dp(46)));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Поручить Issue Copilot").setView(form).setPositiveButton("Поручить",null).setNegativeButton("Отмена",null).create();dialog.setOnShowListener(x->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);io.execute(()->{try{features.copilot.assignIssue(full,number,branch.getText().toString(),instructions.getText().toString(),copilotModelValue(model.getSelectedItemPosition()),customAgent.getText().toString());CopilotTaskMonitorJobService.runNow(this);runOnUiThread(()->{dialog.dismiss();toast("Issue поручена Copilot");navigate(()->showCopilotRepository(full));});}catch(Exception e){runOnUiThread(()->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);showCopilotApiError("Не удалось поручить Issue",e);});}});}));dialog.show();
    }

    private String[] copilotModelLabels(){return new String[]{"Автоматический выбор","Claude Sonnet 4.6","Claude Opus 4.6","GPT-5.2 Codex","GPT-5.3 Codex","GPT-5.4","Claude Sonnet 4.5","Claude Opus 4.5"};}
    private String copilotModelValue(int position){String[] values={"","claude-sonnet-4.6","claude-opus-4.6","gpt-5.2-codex","gpt-5.3-codex","gpt-5.4","claude-sonnet-4.5","claude-opus-4.5"};return position>=0&&position<values.length?values[position]:"";}
    private int copilotStateColor(String state){if("completed".equals(state))return GREEN;if("failed".equals(state)||"timed_out".equals(state)||"cancelled".equals(state))return Color.rgb(210,70,75);if("waiting_for_user".equals(state))return Color.rgb(220,145,35);return BLUE;}

    private void renderCopilotPermissionProblem(String message){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.addView(text("Copilot API недоступен",15,true));TextView detail=text((message==null||message.isEmpty()?"GitHub не разрешил запрос.":message)+"\n\nДля GiHubble требуется Agent tasks: Read and write. Пользователю также нужен поддерживаемый тариф Copilot, а cloud agent не должен быть запрещён политикой репозитория.",12,false);detail.setTextColor(MUTED);box.addView(detail);card(box);
    }

    private void showCopilotApiError(String title,Exception error){
        String message=error==null?"Неизвестная ошибка":error.getMessage();
        if(error instanceof GitHubApiClient.ApiException){int code=((GitHubApiClient.ApiException)error).statusCode;if(code==403)message="GitHub отклонил запрос. Проверьте Agent tasks (read/write), подписку Copilot и политику репозитория.";else if(code==422)message="GitHub не принял параметры задачи или Copilot недоступен для выбранного репозитория. "+message;}
        new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("Настройки Copilot",(d,w)->openWeb("https://github.com/settings/copilot")).setNeutralButton("Разрешения приложений",(d,w)->openWeb("https://github.com/settings/installations")).setNegativeButton("Закрыть",null).show();
    }


    private void requestNotificationPermissionIfNeeded(){
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},223);
    }

    private void showCachedRepository(String full,String json,String readme){
        try{JSONObject r=new JSONObject(json);TextView note=text("Офлайн-режим · сохранённая версия",13,true);note.setTextColor(GREEN);card(note);LinearLayout h=new LinearLayout(this);h.setOrientation(LinearLayout.VERTICAL);h.addView(text(full,22,true));TextView d=text(r.optString("description","Без описания"),14,false);d.setTextColor(MUTED);h.addView(d);card(h);if(!readme.isEmpty())showReadmeInline(full,readme);}catch(Exception e){errorCard("Ошибка офлайн-кэша",e.getMessage());}
    }

    private void toggleRepositoryStar(String full,boolean currentlyStarred){
        io.execute(()->{try{repositoryController.setStarred(full,!currentlyStarred);runOnUiThread(()->{toast(currentlyStarred?"Звезда удалена":"Репозиторий добавлен в звёзды GitHub");showRepository(full);});}catch(Exception e){runOnUiThread(()->errorDialog("Не удалось изменить звезду",e.getMessage()));}});
    }

    private void toggleGitHubFollow(String login,boolean currentlyFollowing){
        io.execute(()->{try{profileController.setFollowing(login,!currentlyFollowing);runOnUiThread(()->{toast(currentlyFollowing?"Подписка GitHub отменена":"Подписка GitHub оформлена");showDeveloperProfile(login);});}catch(Exception e){runOnUiThread(()->errorDialog("Не удалось изменить подписку",e.getMessage()));}});
    }

    private void showBranchChooser(String full, String current) {
        ProgressDialog dialog = ProgressDialog.show(this, "Ветки", "Загрузка…", true, false);
        io.execute(() -> {
            try {
                JSONArray branches = getArray("https://api.github.com/repos/"+full+"/branches?per_page=100");
                String[] names = new String[branches.length()];
                int checked = 0;
                for (int i=0;i<branches.length();i++) { names[i]=branches.optJSONObject(i).optString("name"); if(names[i].equals(current)) checked=i; }
                int finalChecked = checked;
                runOnUiThread(() -> { dialog.dismiss(); new AlertDialog.Builder(this).setTitle("Выберите ветку").setSingleChoiceItems(names, finalChecked, (d, which) -> { d.dismiss(); navigate(() -> showRepositoryFiles(full, names[which], "")); }).setNegativeButton("Отмена",null).show(); });
            } catch(Exception e) { runOnUiThread(() -> { dialog.dismiss(); toast("Не удалось загрузить ветки"); }); }
        });
    }

    private void showCreateRepositoryDialog(){
        if(!isSignedIn()){toast("Сначала подключите GitHub");return;}
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(18),0,dp(18),0);
        EditText name=new EditText(this);name.setHint("Название репозитория");name.setSingleLine(true);form.addView(name);
        EditText description=new EditText(this);description.setHint("Описание");description.setMinLines(2);form.addView(description);
        CheckBox privateRepo=new CheckBox(this);privateRepo.setText("Приватный репозиторий");form.addView(privateRepo);
        CheckBox readme=new CheckBox(this);readme.setText("Создать README");readme.setChecked(true);form.addView(readme);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Новый репозиторий").setView(form).setPositiveButton("Создать",null).setNegativeButton("Отмена",null).create();
        dialog.setOnShowListener(x->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String n=name.getText().toString().trim();if(n.isEmpty()){name.setError("Введите название");return;}dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);io.execute(()->{try{JSONObject repo=features.repositoryAdmin.createRepository(n,description.getText().toString().trim(),privateRepo.isChecked(),readme.isChecked());String full=repo.optString("full_name",n);runOnUiThread(()->{dialog.dismiss();toast("Репозиторий создан");navigate(()->showRepositoryAdmin(full,repo));});}catch(Exception e){runOnUiThread(()->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);errorDialog("Не удалось создать репозиторий",e.getMessage());});}});}));dialog.show();
    }

    private void showRepositoryAdmin(String full,JSONObject repo){
        featureState.lastAdminRepository=full;rememberScreen("repository_admin",routeArgs("full",full));setCurrentScreen(()->showRepositoryAdmin(full,repo));shell("Управление",true);repoSectionHeader(full,"Управление","Исходники и доступ",R.drawable.ic_settings);
        TextView note=text("Загрузка папки/ZIP создаёт Git blobs, единое дерево и один коммит. Структура каталогов сохраняется. Операции выполняются напрямую через GitHub API.",12,false);note.setTextColor(MUTED);card(note);
        String defaultBranch=repo==null?"main":repo.optString("default_branch","main");
        sectionTitle("Исходники",null);GridLayout upload=new GridLayout(this);upload.setColumnCount(adaptiveColumns(2));
        addAction(upload,"Загрузить папку",()->startRepositoryFolderImport(full,defaultBranch));
        addAction(upload,"Импортировать ZIP",()->startRepositoryZipImport(full,defaultBranch));
        addAction(upload,"Загрузить файл/ZIP",()->startRepositoryFileImport(full,defaultBranch));
        addAction(upload,"Открыть файлы",()->navigate(()->showRepositoryFiles(full,defaultBranch,"")));content.addView(upload);
        sectionTitle("Git и публикация",null);GridLayout actions=new GridLayout(this);actions.setColumnCount(adaptiveColumns(2));
        addAction(actions,"Создать fork",()->showForkDialog(full));addAction(actions,"Новая ветка",()->showCreateBranchDialog(full,defaultBranch));addAction(actions,"Новый тег",()->showCreateTagDialog(full,defaultBranch));addAction(actions,"Новый Release",()->showCreateReleaseDialog(full,defaultBranch));addAction(actions,"Actions / место",()->navigate(()->showActionsCleanup(full)));addAction(actions,"Коммиты "+defaultBranch,()->navigate(()->showRepositoryCommits(full,defaultBranch)));content.addView(actions);
        boolean isPrivate=repo!=null&&repo.optBoolean("private",false);boolean admin=repo==null||repo.optJSONObject("permissions")==null||repo.optJSONObject("permissions").optBoolean("admin",true);
        sectionTitle("Настройки репозитория",null);GridLayout settings=new GridLayout(this);settings.setColumnCount(adaptiveColumns(2));
        if(admin){addAction(settings,"Имя и описание",()->showEditRepositoryDialog(full,repo));addAction(settings,isPrivate?"Сделать публичным":"Сделать приватным",()->showRepositoryVisibilityDialog(full,isPrivate));addAction(settings,"Удалить репозиторий",()->showDeleteRepositoryDialog(full));}
        else{TextView ro=text("У вашего аккаунта нет административных прав на этот репозиторий. Загрузка кода и обычные Git-операции остаются доступны в пределах выданных разрешений.",12,false);ro.setTextColor(MUTED);settings.addView(ro,new GridLayout.LayoutParams());}
        content.addView(settings);
        TextView warning=text("Удаление и изменение видимости требуют права Administration: write. Загрузка исходников требует Contents: write; запись .github/workflows/* может потребовать Workflows: write.",11,false);warning.setTextColor(MUTED);card(warning);
    }

    private void startRepositoryFolderImport(String full,String branch){
        setPendingRepositoryImport(full,branch);Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(intent,REQUEST_REPOSITORY_FOLDER);
    }

    private void startRepositoryZipImport(String full,String branch){
        setPendingRepositoryImport(full,branch);Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("application/zip");intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/zip","application/x-zip-compressed","application/octet-stream"});intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(intent,REQUEST_REPOSITORY_ZIP);
    }

    private void startRepositoryFileImport(String full,String branch){
        setPendingRepositoryImport(full,branch);Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("*/*");intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(intent,REQUEST_REPOSITORY_FILE);
    }

    private void setPendingRepositoryImport(String full,String branch){
        pendingRepositoryImportFull=full==null?"":full;pendingRepositoryImportBranch=branch==null||branch.isEmpty()?"main":branch;prefs.edit().putString("pending_repo_import_full",pendingRepositoryImportFull).putString("pending_repo_import_branch",pendingRepositoryImportBranch).apply();
    }

    private void clearPendingRepositoryImport(){pendingRepositoryImportFull="";pendingRepositoryImportBranch="main";prefs.edit().remove("pending_repo_import_full").remove("pending_repo_import_branch").apply();}

    private void handleRepositoryImportUri(int requestCode,Uri uri){
        String full=pendingRepositoryImportFull.isEmpty()?prefs.getString("pending_repo_import_full",""):pendingRepositoryImportFull;String branch=pendingRepositoryImportBranch==null||pendingRepositoryImportBranch.isEmpty()?prefs.getString("pending_repo_import_branch","main"):pendingRepositoryImportBranch;
        if(full.isEmpty()){errorDialog("Импорт не запущен","Не удалось восстановить целевой репозиторий. Откройте «Управление» и выберите импорт ещё раз.");return;}
        try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
        ProgressDialog scan=ProgressDialog.show(this,"Импорт","Анализируем выбранные данные…",true,false);io.execute(()->{try{RepositoryImportController.Preview preview=requestCode==REQUEST_REPOSITORY_FOLDER?features.repositoryImport.previewFolder(uri):requestCode==REQUEST_REPOSITORY_ZIP?features.repositoryImport.previewZip(uri):features.repositoryImport.previewFile(uri);runOnUiThread(()->{scan.dismiss();showRepositoryImportPreview(requestCode,full,branch,uri,preview);});}catch(Exception e){runOnUiThread(()->{scan.dismiss();errorDialog("Не удалось прочитать выбранные данные",e.getMessage());});}});
    }

    private void showRepositoryImportPreview(int requestCode,String full,String branch,Uri uri,RepositoryImportController.Preview preview){
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(18),0,dp(18),0);
        StringBuilder summary=new StringBuilder();summary.append("Файлов: ").append(preview.files).append("\nОбъём: ").append(humanBytes(preview.totalBytes));if(preview.ignored>0)summary.append("\nПропущено служебных: ").append(preview.ignored);if(preview.hasWorkflow)summary.append("\n\n⚠ В составе есть .github/workflows — GitHub может потребовать Workflows: write.");
        if(!preview.samplePaths.isEmpty()){summary.append("\n\nБудут добавлены, например:\n");for(String path:preview.samplePaths)summary.append("• ").append(path).append('\n');}
        TextView info=text(summary.toString().trim(),12,false);info.setTextColor(MUTED);form.addView(info);
        EditText target=new EditText(this);if(requestCode==REQUEST_REPOSITORY_FILE){target.setHint("Путь в репозитории");target.setText(preview.sourceName);target.setSingleLine(true);form.addView(target);}
        EditText message=new EditText(this);message.setHint("Сообщение коммита");message.setText(requestCode==REQUEST_REPOSITORY_FOLDER?"Import source folder with GitHubble":requestCode==REQUEST_REPOSITORY_ZIP?"Import ZIP sources with GitHubble":"Upload file with GitHubble");message.setSingleLine(true);form.addView(message);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(requestCode==REQUEST_REPOSITORY_FOLDER?"Загрузить папку?":requestCode==REQUEST_REPOSITORY_ZIP?"Распаковать ZIP в репозиторий?":"Загрузить файл?").setView(form).setPositiveButton("Загрузить",null).setNegativeButton("Отмена",(d,w)->clearPendingRepositoryImport()).create();
        dialog.setOnShowListener(x->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String targetPath=target.getText().toString().trim();String commitMessage=message.getText().toString().trim();dialog.dismiss();runRepositoryImport(requestCode,full,branch,uri,targetPath,commitMessage);}));dialog.show();
    }

    private void runRepositoryImport(int requestCode,String full,String branch,Uri uri,String targetPath,String message){
        ProgressDialog progress=new ProgressDialog(this);progress.setTitle("Загрузка в GitHub");progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);progress.setIndeterminate(false);progress.setMax(100);progress.setProgress(0);progress.setMessage("Подготовка…");progress.setCancelable(false);progress.show();
        io.execute(()->{try{RepositoryImportController.ProgressListener listener=(done,total,path)->runOnUiThread(()->{int percent=total<=0?0:(int)Math.min(100,(done*100L)/total);progress.setProgress(percent);progress.setMessage(done+" / "+total+" · "+path);});RepositoryImportController.ImportResult result=requestCode==REQUEST_REPOSITORY_FOLDER?features.repositoryImport.importFolder(full,branch,uri,message,listener):requestCode==REQUEST_REPOSITORY_ZIP?features.repositoryImport.importZip(full,branch,uri,message,listener):features.repositoryImport.importFile(full,branch,uri,targetPath,message,listener);runOnUiThread(()->{progress.dismiss();clearPendingRepositoryImport();new AlertDialog.Builder(this).setTitle("Загрузка завершена").setMessage("Файлов: "+result.files+"\nВетка: "+result.branch+"\nКоммит: "+shortSha(result.commitSha)).setPositiveButton("Открыть файлы",(d,w)->navigate(()->showRepositoryFiles(full,result.branch,""))).setNegativeButton("Готово",null).show();});}catch(Exception e){runOnUiThread(()->{progress.dismiss();String msg=e.getMessage();if(e instanceof GitHubApiClient.ApiException&&((GitHubApiClient.ApiException)e).statusCode==403)msg=(msg==null?"GitHub вернул 403":msg)+"\n\nПроверьте Contents: write, Administration: write и Workflows: write в настройках GiHubble.";errorDialog("Не удалось загрузить исходники",msg);});}});
    }

    private void showEditRepositoryDialog(String full,JSONObject repo){
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(18),0,dp(18),0);EditText name=new EditText(this);name.setHint("Название");name.setText(repo==null?(full.contains("/")?full.substring(full.indexOf('/')+1):full):repo.optString("name",""));name.setSingleLine(true);form.addView(name);EditText description=new EditText(this);description.setHint("Описание");description.setText(repo==null?"":repo.optString("description",""));description.setMinLines(2);form.addView(description);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Имя и описание").setView(form).setPositiveButton("Сохранить",null).setNegativeButton("Отмена",null).create();dialog.setOnShowListener(x->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String n=name.getText().toString().trim();if(n.isEmpty()){name.setError("Введите название");return;}dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);io.execute(()->{try{JSONObject updated=features.repositoryAdmin.updateRepository(full,n,description.getText().toString());String newFull=updated.optString("full_name",full);runOnUiThread(()->{dialog.dismiss();toast("Репозиторий обновлён");showRepositoryAdmin(newFull,updated);});}catch(Exception e){runOnUiThread(()->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);errorDialog("Не удалось обновить репозиторий",e.getMessage());});}});}));dialog.show();
    }

    private void showRepositoryVisibilityDialog(String full,boolean currentlyPrivate){
        boolean makePrivate=!currentlyPrivate;String title=makePrivate?"Сделать репозиторий приватным?":"Сделать репозиторий публичным?";String message=makePrivate?"После подтверждения содержимое будет доступно только вам и приглашённым участникам.":"После подтверждения код и история репозитория станут доступны всем. Не публикуйте секреты, ключи и приватные данные.";
        new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton(makePrivate?"Сделать приватным":"Сделать публичным",(d,w)->{ProgressDialog p=ProgressDialog.show(this,"Видимость","Изменяем настройки GitHub…",true,false);io.execute(()->{try{JSONObject updated=features.repositoryAdmin.setPrivate(full,makePrivate);runOnUiThread(()->{p.dismiss();toast(makePrivate?"Репозиторий теперь приватный":"Репозиторий теперь публичный");showRepositoryAdmin(updated.optString("full_name",full),updated);});}catch(Exception e){runOnUiThread(()->{p.dismiss();errorDialog("Не удалось изменить видимость",e.getMessage());});}});}).setNegativeButton("Отмена",null).show();
    }

    private void showDeleteRepositoryDialog(String full){
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(18),0,dp(18),0);TextView warning=text("Это удалит репозиторий на GitHub. Для подтверждения введите точное имя:\n\n"+full,13,false);warning.setTextColor(Color.rgb(190,55,60));form.addView(warning);EditText confirm=new EditText(this);confirm.setHint(full);confirm.setSingleLine(true);form.addView(confirm);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Удалить репозиторий").setView(form).setPositiveButton("Удалить",null).setNegativeButton("Отмена",null).create();dialog.setOnShowListener(x->{Button delete=dialog.getButton(AlertDialog.BUTTON_POSITIVE);delete.setTextColor(Color.rgb(190,55,60));delete.setOnClickListener(v->{if(!full.equals(confirm.getText().toString().trim())){confirm.setError("Введите "+full);return;}delete.setEnabled(false);io.execute(()->{try{features.repositoryAdmin.deleteRepository(full);runOnUiThread(()->{dialog.dismiss();toast("Репозиторий удалён");openRoot(this::showMyRepositories);});}catch(Exception e){runOnUiThread(()->{delete.setEnabled(true);errorDialog("Не удалось удалить репозиторий",e.getMessage());});}});});});dialog.show();
    }

    private String humanBytes(long bytes){if(bytes<=0)return "неизвестно";double value=bytes;String[] units={"Б","КиБ","МиБ","ГиБ"};int i=0;while(value>=1024&&i<units.length-1){value/=1024;i++;}return String.format(Locale.ROOT,value>=10?"%.0f %s":"%.1f %s",value,units[i]);}
    private String shortSha(String sha){return sha==null?"":(sha.length()>12?sha.substring(0,12):sha);}

    private void showForkDialog(String full){
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(18),0,dp(18),0);EditText org=new EditText(this);org.setHint("Организация (необязательно)");org.setSingleLine(true);form.addView(org);EditText name=new EditText(this);name.setHint("Новое название (необязательно)");name.setSingleLine(true);form.addView(name);
        new AlertDialog.Builder(this).setTitle("Создать fork").setView(form).setPositiveButton("Создать",(d,w)->{ProgressDialog p=ProgressDialog.show(this,"Fork","GitHub создаёт копию…",true,false);io.execute(()->{try{JSONObject result=features.repositoryAdmin.fork(full,org.getText().toString(),name.getText().toString());String target=result.optString("full_name","");runOnUiThread(()->{p.dismiss();toast("Fork создан");if(!target.isEmpty())navigate(()->showRepository(target));});}catch(Exception e){runOnUiThread(()->{p.dismiss();errorDialog("Не удалось создать fork",e.getMessage());});}});}).setNegativeButton("Отмена",null).show();
    }

    private void showCreateBranchDialog(String full,String defaultBranch){
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(18),0,dp(18),0);EditText name=new EditText(this);name.setHint("Название новой ветки");name.setSingleLine(true);form.addView(name);EditText source=new EditText(this);source.setHint("Исходная ветка или SHA");source.setText(defaultBranch);source.setSingleLine(true);form.addView(source);
        new AlertDialog.Builder(this).setTitle("Новая ветка").setView(form).setPositiveButton("Создать",(d,w)->{String n=name.getText().toString().trim();if(n.isEmpty()){toast("Название ветки не заполнено");return;}ProgressDialog p=ProgressDialog.show(this,"Ветка","Создание…",true,false);io.execute(()->{try{features.repositoryAdmin.createBranch(full,n,source.getText().toString());runOnUiThread(()->{p.dismiss();toast("Ветка "+n+" создана");navigate(()->showRepositoryFiles(full,n,""));});}catch(Exception e){runOnUiThread(()->{p.dismiss();errorDialog("Не удалось создать ветку",e.getMessage());});}});}).setNegativeButton("Отмена",null).show();
    }

    private void showCreateTagDialog(String full,String defaultBranch){
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(18),0,dp(18),0);EditText name=new EditText(this);name.setHint("Тег, например v1.0.0");name.setSingleLine(true);form.addView(name);EditText source=new EditText(this);source.setHint("Ветка, тег или SHA");source.setText(defaultBranch);source.setSingleLine(true);form.addView(source);
        new AlertDialog.Builder(this).setTitle("Новый тег").setView(form).setPositiveButton("Создать",(d,w)->{String n=name.getText().toString().trim();if(n.isEmpty()){toast("Название тега не заполнено");return;}ProgressDialog p=ProgressDialog.show(this,"Тег","Создание…",true,false);io.execute(()->{try{features.repositoryAdmin.createTag(full,n,source.getText().toString());runOnUiThread(()->{p.dismiss();toast("Тег "+n+" создан");});}catch(Exception e){runOnUiThread(()->{p.dismiss();errorDialog("Не удалось создать тег",e.getMessage());});}});}).setNegativeButton("Отмена",null).show();
    }

    private void showCreateReleaseDialog(String full,String defaultBranch){
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(18),0,dp(18),0);EditText tag=new EditText(this);tag.setHint("Тег, например v1.0.0");tag.setSingleLine(true);form.addView(tag);EditText target=new EditText(this);target.setHint("Целевая ветка");target.setText(defaultBranch);target.setSingleLine(true);form.addView(target);EditText title=new EditText(this);title.setHint("Название Release");title.setSingleLine(true);form.addView(title);EditText body=new EditText(this);body.setHint("Описание Markdown");body.setMinLines(4);form.addView(body);CheckBox draft=new CheckBox(this);draft.setText("Черновик");form.addView(draft);CheckBox prerelease=new CheckBox(this);prerelease.setText("Предварительный релиз");form.addView(prerelease);CheckBox notes=new CheckBox(this);notes.setText("Сгенерировать заметки GitHub");form.addView(notes);
        new AlertDialog.Builder(this).setTitle("Новый Release").setView(form).setPositiveButton("Создать",(d,w)->{String t=tag.getText().toString().trim();if(t.isEmpty()){toast("Укажите тег");return;}ProgressDialog p=ProgressDialog.show(this,"Release","Публикация…",true,false);io.execute(()->{try{features.repositoryAdmin.createRelease(full,t,target.getText().toString().trim(),title.getText().toString().trim(),body.getText().toString(),draft.isChecked(),prerelease.isChecked(),notes.isChecked());runOnUiThread(()->{p.dismiss();toast("Release создан");navigate(()->showRepoReleases(full));});}catch(Exception e){runOnUiThread(()->{p.dismiss();errorDialog("Не удалось создать Release",e.getMessage());});}});}).setNegativeButton("Отмена",null).show();
    }

    private void showPullChecks(String full,int number,JSONObject pull){
        rememberScreen("pull_checks",routeArgs("full",full,"number",number));setCurrentScreen(()->showPullChecks(full,number,pull));shell("Проверки PR #"+number,true);heroBanner("Проверки и защита ветки",full,R.drawable.ic_pull_request);ProgressBar pb=new ProgressBar(this);content.addView(pb);
        JSONObject head=pull==null?null:pull.optJSONObject("head"),base=pull==null?null:pull.optJSONObject("base");String sha=head==null?"":head.optString("sha","");String branch=base==null?"":base.optString("ref","");
        io.execute(()->{try{JSONObject result=features.pullAdvanced.loadChecks(full,sha,branch);runOnUiThread(()->{content.removeView(pb);renderPullChecks(result);});}catch(Exception e){runOnUiThread(()->{content.removeView(pb);errorCard("Не удалось загрузить проверки",e.getMessage());});}});
    }

    private void renderPullChecks(JSONObject result){
        JSONObject combined=result.optJSONObject("combined_status"),required=result.optJSONObject("required"),checks=result.optJSONObject("checks");String state=combined==null?"unknown":combined.optString("state","unknown");TextView summary=text("Общий статус: "+state,16,true);summary.setTextColor("success".equals(state)?GREEN:"failure".equals(state)||"error".equals(state)?Color.rgb(190,55,60):BLUE);card(summary);
        Set<String> requiredNames=new LinkedHashSet<>();if(required!=null){JSONArray contexts=required.optJSONArray("contexts");if(contexts!=null)for(int i=0;i<contexts.length();i++)requiredNames.add(contexts.optString(i));JSONArray rc=required.optJSONArray("checks");if(rc!=null)for(int i=0;i<rc.length();i++){JSONObject o=rc.optJSONObject(i);if(o!=null)requiredNames.add(o.optString("context"));}}
        if(required!=null&&required.optBoolean("unavailable",false)){TextView n=text("GitHub не предоставил список обязательных проверок. Ниже показаны доступные check runs и статусы коммитов.",12,false);n.setTextColor(MUTED);card(n);}else if(!requiredNames.isEmpty()){TextView n=text("Обязательные: "+String.join(", ",requiredNames),12,false);n.setTextColor(MUTED);card(n);}
        sectionTitle("Check runs",null);JSONArray runs=checks==null?null:checks.optJSONArray("check_runs");if(runs==null||runs.length()==0)card(text("Check runs не найдены.",13,false));else for(int i=0;i<runs.length();i++){JSONObject o=runs.optJSONObject(i);if(o==null)continue;String name=o.optString("name","Проверка"),status=o.optString("status",""),conclusion=o.optString("conclusion","");LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);row.addView(text((requiredNames.contains(name)?"★ ":"")+name,14,true));TextView meta=text(status+(conclusion.isEmpty()?"":" · "+conclusion),11,false);meta.setTextColor(MUTED);row.addView(meta);String url=o.optString("details_url",o.optString("html_url",""));if(!url.isEmpty())row.setOnClickListener(v->openWeb(url));card(row);}
        sectionTitle("Статусы коммита",null);JSONArray statuses=combined==null?null:combined.optJSONArray("statuses");if(statuses==null||statuses.length()==0)card(text("Статусы коммита не найдены.",13,false));else for(int i=0;i<statuses.length();i++){JSONObject o=statuses.optJSONObject(i);if(o==null)continue;String name=o.optString("context","status");LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);row.addView(text((requiredNames.contains(name)?"★ ":"")+name,14,true));TextView meta=text(o.optString("state","")+" · "+o.optString("description",""),11,false);meta.setTextColor(MUTED);row.addView(meta);String url=o.optString("target_url","");if(!url.isEmpty())row.setOnClickListener(v->openWeb(url));card(row);}
    }

    private void showRepoIssues(String full){rememberScreen("repo_issues",routeArgs("full",full));showRepoIssuesPage(full,1,true);}
    private void showRepoIssuesPage(String full,int page,boolean first){
        if(first){setCurrentScreen(()->showRepoIssues(full));shell("Issues",true);repoSectionHeader(full,"Issues","Открытые задачи",R.drawable.ic_issue);if(isSignedIn()){Button create=button("Создать Issue");create.setOnClickListener(v->showIssueEditor(full,0,"","",""));content.addView(create,new LinearLayout.LayoutParams(-1,dp(50)));}}
        ProgressBar pb=new ProgressBar(this);content.addView(pb);io.execute(()->{try{JSONArray a=getArray("https://api.github.com/repos/"+full+"/issues?state=open&per_page=50&page="+page);runOnUiThread(()->{content.removeView(pb);int count=0;for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null&&!o.has("pull_request"))count++;}if(first)sectionTitle("Открытые Issues",null);if(count==0&&first){card(text("Открытых Issues нет.",14,false));return;}for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null||o.has("pull_request"))continue;int number=o.optInt("number");String title=o.optString("title"),user=o.optJSONObject("user")!=null?o.optJSONObject("user").optString("login"):"";LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);row.addView(text("#"+number+"  "+title,15,true));TextView meta=text(user+" · "+o.optInt("comments")+" комментариев · "+formatGitHubDate(o.optString("updated_at","")),11,false);meta.setTextColor(MUTED);row.addView(meta);row.setOnClickListener(v->navigate(()->showIssueDetail(full,number)));card(row);}if(a.length()==50)addLoadMoreButton("Показать ещё Issues",()->showRepoIssuesPage(full,page+1,false));});}catch(Exception e){runOnUiThread(()->{content.removeView(pb);errorCard("Не удалось загрузить Issues",e.getMessage());});}});
    }

    private void showIssueDetail(String full,int number) {
        rememberScreen("issue",routeArgs("full",full,"number",number));
        setCurrentScreen(() -> showIssueDetail(full,number)); shell("Issue #"+number,true); ProgressBar pb=new ProgressBar(this);content.addView(pb);
        io.execute(() -> {try {
            JSONObject issue=getJson("https://api.github.com/repos/"+full+"/issues/"+number);
            JSONArray comments=getArray("https://api.github.com/repos/"+full+"/issues/"+number+"/comments?per_page=100");
            runOnUiThread(() -> {
                content.removeView(pb);
                heroBanner(issue.optString("title"),full,R.drawable.ic_issue);
                JSONObject author=issue.optJSONObject("user");
                String state=issue.optString("state","open");
                TextView meta=text((author==null?"":author.optString("login",""))+" · "+("open".equals(state)?"Открыта":"Закрыта")+" · "+formatGitHubDate(issue.optString("updated_at","")),11,false);meta.setTextColor(MUTED);card(meta);
                JSONArray labels=issue.optJSONArray("labels");if(labels!=null&&labels.length()>0){LinearLayout chips=new LinearLayout(this);chips.setOrientation(LinearLayout.HORIZONTAL);for(int i=0;i<Math.min(labels.length(),5);i++){JSONObject l=labels.optJSONObject(i);if(l!=null)chips.addView(pill(l.optString("name","label")));}card(chips);}
                addTranslatableMarkdownCard("issue:"+full+"#"+number,issue.optString("body","Без описания"),issue.optString("updated_at",""));
                GridLayout actions=new GridLayout(this);actions.setColumnCount(adaptiveColumns(2));
                addAction(actions,"Комментарий",()->showIssueCommentDialog(full,number));
                addAction(actions,"Редактировать",()->showIssueEditor(full,number,issue.optString("title",""),issue.optString("body",""),issue.optString("updated_at","")));
                if(isSignedIn()){
                    addAction(actions,"Метки и участники",()->showIssueMetadataDialog(full,number,issue));
                    addAction(actions,"Поручить Copilot",()->showAssignIssueToCopilot(full,number,issue));
                }
                addAction(actions,"👍 Реакция",()->postIssueReaction(full,number,"+1"));
                addAction(actions,"Открыть GitHub",()->openWeb(issue.optString("html_url","https://github.com/"+full+"/issues/"+number)));
                content.addView(actions);
                sectionTitle("Комментарии: "+comments.length(),null);
                for(int i=0;i<comments.length();i++){
                    JSONObject c=comments.optJSONObject(i);if(c==null)continue;
                    LinearLayout b=new LinearLayout(this);b.setOrientation(LinearLayout.VERTICAL);
                    JSONObject user=c.optJSONObject("user");String login=user==null?"GitHub":user.optString("login","GitHub");
                    LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);head.addView(text(login,13,true),new LinearLayout.LayoutParams(0,-2,1));TextView date=text(formatGitHubDate(c.optString("updated_at","")),10,false);date.setTextColor(MUTED);head.addView(date);b.addView(head);
                    b.addView(translatableMarkdownPanel("issue-comment:"+c.optLong("id",0),c.optString("body",""),c.optString("updated_at","")));
                    Button react=button("👍");long commentId=c.optLong("id",0);react.setOnClickListener(v->postCommentReaction(full,commentId,"+1"));b.addView(react,new LinearLayout.LayoutParams(dp(74),dp(40)));card(b);
                }
            });
        }catch(Exception e){runOnUiThread(() -> {content.removeView(pb);errorCard("Не удалось открыть Issue",e.getMessage());});}});
    }

    private void showIssueEditor(String full,int number,String oldTitle,String oldBody,String baseUpdatedAt){
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(18),0,dp(18),0);
        EditText title=new EditText(this);title.setHint("Заголовок");title.setSingleLine(true);title.setText(oldTitle);title.setTextColor(TEXT);title.setHintTextColor(MUTED);form.addView(title);
        EditText body=new EditText(this);body.setHint("Описание в Markdown");body.setMinLines(6);body.setGravity(Gravity.TOP);body.setText(oldBody);body.setTextColor(TEXT);body.setHintTextColor(MUTED);form.addView(body);
        new AlertDialog.Builder(this).setTitle(number>0?"Редактировать Issue":"Создать Issue").setView(form).setPositiveButton(number>0?"Сохранить":"Создать",(d,w)->{
            String titleValue=title.getText().toString().trim();String bodyValue=body.getText().toString();if(titleValue.isEmpty()){toast("Нужен заголовок");return;}
            ProgressDialog progress=ProgressDialog.show(this,"Issue",number>0?"Сохраняем…":"Создаём…",true,false);
            io.execute(() -> {try{JSONObject payload=new JSONObject();payload.put("title",titleValue);payload.put("body",bodyValue);JSONObject result;if(number>0){String url="https://api.github.com/repos/"+full+"/issues/"+number;result=features.offlineDispatcher.executeOrQueue("PATCH",url,payload,"issue:"+full+"#"+number,"Редактирование Issue #"+number,url,"updated_at",baseUpdatedAt).response;}else result=api.postJson("https://api.github.com/repos/"+full+"/issues",payload,true);int created=result.optInt("number",number);boolean queued=result.optBoolean("queued",false);runOnUiThread(() -> {progress.dismiss();if(queued){toast("Изменение сохранено в офлайн-очереди");navigate(this::showOfflineQueue);}else{toast(number>0?"Issue обновлена":"Issue создана");navigate(() -> showIssueDetail(full,created));}});}catch(Exception e){runOnUiThread(() -> {progress.dismiss();toast("GitHub не принял изменение: "+e.getMessage());});}});
        }).setNegativeButton("Отмена",null).show();
    }

    private void showIssueCommentDialog(String full,int number){
        EditText input=new EditText(this);input.setMinLines(4);input.setGravity(Gravity.TOP);input.setHint("Комментарий в Markdown");input.setTextColor(TEXT);input.setHintTextColor(MUTED);
        new AlertDialog.Builder(this).setTitle("Новый комментарий").setView(input).setPositiveButton("Отправить",(d,w)->{String value=input.getText().toString().trim();if(value.isEmpty())return;ProgressDialog progress=ProgressDialog.show(this,"Issue","Отправляем комментарий…",true,false);io.execute(() -> {try{JSONObject body=new JSONObject();body.put("body",value);api.postJson("https://api.github.com/repos/"+full+"/issues/"+number+"/comments",body,true);runOnUiThread(() -> {progress.dismiss();toast("Комментарий добавлен");showIssueDetail(full,number);});}catch(Exception e){runOnUiThread(() -> {progress.dismiss();toast("Не удалось отправить: "+e.getMessage());});}});}).setNegativeButton("Отмена",null).show();
    }

    private void postIssueReaction(String full,int number,String reaction){
        postReaction("https://api.github.com/repos/"+full+"/issues/"+number+"/reactions",reaction);
    }

    private void postCommentReaction(String full,long commentId,String reaction){
        if(commentId<=0)return;postReaction("https://api.github.com/repos/"+full+"/issues/comments/"+commentId+"/reactions",reaction);
    }

    private void postReaction(String endpoint,String reaction){
        ProgressDialog progress=ProgressDialog.show(this,"GitHub","Добавляем реакцию…",true,false);io.execute(() -> {try{JSONObject body=new JSONObject();body.put("content",reaction);api.postJson(endpoint,body,true);runOnUiThread(() -> {progress.dismiss();toast("Реакция добавлена");});}catch(Exception e){runOnUiThread(() -> {progress.dismiss();toast("Не удалось добавить реакцию: "+e.getMessage());});}});
    }

    private void showRepoPulls(String full){rememberScreen("repo_pulls",routeArgs("full",full));showRepoPullsPage(full,1,true);}
    private void showRepoPullsPage(String full,int page,boolean first){
        if(first){setCurrentScreen(()->showRepoPulls(full));shell("Pull Requests",true);repoSectionHeader(full,"Pull Requests","Изменения и ревью",R.drawable.ic_pull_request);if(isSignedIn()){Button create=button("Создать Pull Request");create.setOnClickListener(v->showCreatePullRequest(full));content.addView(create,new LinearLayout.LayoutParams(-1,dp(50)));}}ProgressBar pb=new ProgressBar(this);content.addView(pb);
        io.execute(()->{try{JSONArray a=getArray("https://api.github.com/repos/"+full+"/pulls?state=open&per_page=50&page="+page);runOnUiThread(()->{content.removeView(pb);if(first)sectionTitle("Открытые Pull Requests",null);if(a.length()==0&&first){card(text("Открытых Pull Requests нет.",14,false));return;}for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;int n=o.optInt("number");LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);row.addView(text("#"+n+"  "+o.optString("title"),15,true));JSONObject user=o.optJSONObject("user");JSONObject head=o.optJSONObject("head");TextView meta=text((user==null?"":user.optString("login"))+" · "+(head==null?"":head.optString("ref","")),11,false);meta.setTextColor(MUTED);row.addView(meta);row.setOnClickListener(v->navigate(()->showPullDetail(full,n)));card(row);}if(a.length()==50)addLoadMoreButton("Показать ещё Pull Requests",()->showRepoPullsPage(full,page+1,false));});}catch(Exception e){runOnUiThread(()->{content.removeView(pb);errorCard("Не удалось загрузить PR",e.getMessage());});}});
    }

    private void showCreatePullRequest(String full){
        if(!isSignedIn()){accountRequiredCard("Для создания Pull Request подключите GitHub.");return;}
        ProgressDialog progress=ProgressDialog.show(this,"Новый Pull Request","Загружаем ветки…",true,false);
        io.execute(()->{try{
            JSONObject repo=getJson("https://api.github.com/repos/"+full);
            JSONArray branches=getArray("https://api.github.com/repos/"+full+"/branches?per_page=100");
            String[] names=new String[branches.length()];for(int i=0;i<branches.length();i++)names[i]=branches.optJSONObject(i).optString("name","");
            String defaultBranch=repo.optString("default_branch",names.length>0?names[0]:"main");
            runOnUiThread(()->{progress.dismiss();if(names.length<1){toast("В репозитории нет доступных веток");return;}
                LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),dp(4),dp(18),0);
                EditText title=new EditText(this);title.setHint("Заголовок Pull Request");title.setSingleLine(true);box.addView(title,new LinearLayout.LayoutParams(-1,dp(54)));
                EditText body=new EditText(this);body.setHint("Описание изменений (Markdown)");body.setMinLines(4);body.setGravity(Gravity.TOP);box.addView(body,new LinearLayout.LayoutParams(-1,dp(128)));
                TextView headLabel=text("Ветка с изменениями",12,true);box.addView(headLabel);Spinner head=new Spinner(this);head.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));box.addView(head,new LinearLayout.LayoutParams(-1,dp(50)));
                TextView baseLabel=text("Целевая ветка",12,true);box.addView(baseLabel);Spinner base=new Spinner(this);base.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));int baseIndex=0;for(int i=0;i<names.length;i++)if(defaultBranch.equals(names[i]))baseIndex=i;base.setSelection(baseIndex);box.addView(base,new LinearLayout.LayoutParams(-1,dp(50)));
                Switch draft=new Switch(this);draft.setText("Создать как черновик");draft.setPadding(0,dp(6),0,dp(6));box.addView(draft);
                AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Создать Pull Request").setView(box).setPositiveButton("Создать",null).setNegativeButton("Отмена",null).create();
                dialog.setOnShowListener(x->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String t=title.getText().toString().trim();String h=String.valueOf(head.getSelectedItem());String b=String.valueOf(base.getSelectedItem());if(t.isEmpty()){title.setError("Введите заголовок");return;}if(h.equals(b)){toast("Исходная и целевая ветки должны отличаться");return;}dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);io.execute(()->{try{JSONObject created=repositoryController.createPullRequest(full,t,body.getText().toString(),h,b,draft.isChecked());int number=created.optInt("number");runOnUiThread(()->{dialog.dismiss();toast("Pull Request #"+number+" создан");navigate(()->showPullDetail(full,number));});}catch(Exception e){runOnUiThread(()->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);new AlertDialog.Builder(this).setTitle("Не удалось создать PR").setMessage(e.getMessage()).setPositiveButton("ОК",null).show();});}}); }));dialog.show();
            });
        }catch(Exception e){runOnUiThread(()->{progress.dismiss();errorDialog("Не удалось подготовить Pull Request",e.getMessage());});}});
    }

    private void showPullReviewDialog(String full,int number){
        String[] labels={"Комментарий","Одобрить","Запросить изменения"};String[] events={"COMMENT","APPROVE","REQUEST_CHANGES"};
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),0,dp(18),0);
        Spinner event=new Spinner(this);event.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));box.addView(event,new LinearLayout.LayoutParams(-1,dp(54)));
        EditText body=new EditText(this);body.setHint("Текст ревью");body.setMinLines(4);body.setGravity(Gravity.TOP);box.addView(body,new LinearLayout.LayoutParams(-1,dp(140)));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Ревью Pull Request").setView(box).setPositiveButton("Отправить",null).setNegativeButton("Отмена",null).create();
        dialog.setOnShowListener(x->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String selected=events[event.getSelectedItemPosition()];String text=body.getText().toString().trim();if(!"APPROVE".equals(selected)&&text.isEmpty()){body.setError("Добавьте комментарий");return;}dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);io.execute(()->{try{repositoryController.createReview(full,number,text,selected);runOnUiThread(()->{dialog.dismiss();toast("Ревью отправлено");showPullDetail(full,number);});}catch(Exception e){runOnUiThread(()->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);errorDialog("Не удалось отправить ревью",e.getMessage());});}});}));dialog.show();
    }

    private void showMergePullDialog(String full,int number,JSONObject pull){
        ProgressDialog progress=ProgressDialog.show(this,"Слияние PR","Проверяем настройки репозитория…",true,false);
        io.execute(()->{try{JSONObject repo=getJson("https://api.github.com/repos/"+full);runOnUiThread(()->{progress.dismiss();List<String> names=new ArrayList<>();List<String> methods=new ArrayList<>();if(repo.optBoolean("allow_merge_commit",true)){names.add("Merge commit");methods.add("merge");}if(repo.optBoolean("allow_squash_merge",true)){names.add("Squash and merge");methods.add("squash");}if(repo.optBoolean("allow_rebase_merge",true)){names.add("Rebase and merge");methods.add("rebase");}if(names.isEmpty()){toast("В репозитории не разрешён ни один способ слияния");return;}
            LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),0,dp(18),0);Spinner strategy=new Spinner(this);strategy.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));box.addView(strategy,new LinearLayout.LayoutParams(-1,dp(54)));EditText title=new EditText(this);title.setHint("Заголовок merge-коммита (необязательно)");title.setSingleLine(true);box.addView(title,new LinearLayout.LayoutParams(-1,dp(54)));EditText message=new EditText(this);message.setHint("Описание merge-коммита (необязательно)");message.setMinLines(3);box.addView(message,new LinearLayout.LayoutParams(-1,dp(110)));
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Слить Pull Request #"+number+"?").setMessage("GitHub проверит правила ветки, обязательные проверки и права доступа.").setView(box).setPositiveButton("Слить",null).setNegativeButton("Отмена",null).create();dialog.setOnShowListener(x->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);String method=methods.get(strategy.getSelectedItemPosition());JSONObject head=pull.optJSONObject("head");String sha=head==null?"":head.optString("sha","");io.execute(()->{try{JSONObject result=repositoryController.merge(full,number,method,title.getText().toString().trim(),message.getText().toString().trim(),sha);runOnUiThread(()->{dialog.dismiss();if(result.optBoolean("merged",false)){toast("Pull Request успешно слит");showPullDetail(full,number);}else errorDialog("Слияние не выполнено",result.optString("message","GitHub отклонил слияние"));});}catch(Exception e){runOnUiThread(()->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);errorDialog("Не удалось слить PR",e.getMessage());});}});}));dialog.show();});}catch(Exception e){runOnUiThread(()->{progress.dismiss();errorDialog("Не удалось проверить параметры слияния",e.getMessage());});}});
    }

    private void showIssueMetadataDialog(String full,int number,JSONObject issue){
        ProgressDialog progress=ProgressDialog.show(this,"Метки и участники","Загрузка…",true,false);
        io.execute(()->{try{JSONArray labels=repositoryController.labels(full);JSONArray assignees=repositoryController.assignees(full);JSONArray milestones=repositoryController.milestones(full);runOnUiThread(()->{progress.dismiss();
            LinkedHashSet<String> selectedLabels=new LinkedHashSet<>();JSONArray currentLabels=issue.optJSONArray("labels");if(currentLabels!=null)for(int i=0;i<currentLabels.length();i++){JSONObject v=currentLabels.optJSONObject(i);if(v!=null)selectedLabels.add(v.optString("name"));}
            LinkedHashSet<String> selectedAssignees=new LinkedHashSet<>();JSONArray currentAssignees=issue.optJSONArray("assignees");if(currentAssignees!=null)for(int i=0;i<currentAssignees.length();i++){JSONObject v=currentAssignees.optJSONObject(i);if(v!=null)selectedAssignees.add(v.optString("login"));}
            Integer[] selectedMilestone={issue.optJSONObject("milestone")==null?null:issue.optJSONObject("milestone").optInt("number")};
            LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),dp(4),dp(18),dp(4));TextView labelsState=text(metadataSummary("Метки",selectedLabels),13,false);TextView assigneesState=text(metadataSummary("Исполнители",selectedAssignees),13,false);TextView milestoneState=text("Этап: "+milestoneTitle(milestones,selectedMilestone[0]),13,false);
            Button labelsButton=compactButton("Выбрать метки");labelsButton.setOnClickListener(v->showMultiSelect("Метки",labels,"name",selectedLabels,()->labelsState.setText(metadataSummary("Метки",selectedLabels))));box.addView(labelsButton,new LinearLayout.LayoutParams(-1,dp(48)));box.addView(labelsState);
            Button assigneesButton=compactButton("Выбрать исполнителей");assigneesButton.setOnClickListener(v->showMultiSelect("Исполнители",assignees,"login",selectedAssignees,()->assigneesState.setText(metadataSummary("Исполнители",selectedAssignees))));box.addView(assigneesButton,new LinearLayout.LayoutParams(-1,dp(48)));box.addView(assigneesState);
            Button milestoneButton=compactButton("Выбрать этап");milestoneButton.setOnClickListener(v->showMilestoneSelect(milestones,selectedMilestone,()->milestoneState.setText("Этап: "+milestoneTitle(milestones,selectedMilestone[0]))));box.addView(milestoneButton,new LinearLayout.LayoutParams(-1,dp(48)));box.addView(milestoneState);
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Управление Issue #"+number).setView(box).setPositiveButton("Сохранить",null).setNegativeButton("Отмена",null).create();dialog.setOnShowListener(x->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);io.execute(()->{try{JSONObject payload=new JSONObject();payload.put("labels",new JSONArray(selectedLabels));payload.put("assignees",new JSONArray(selectedAssignees));payload.put("milestone",selectedMilestone[0]==null?JSONObject.NULL:selectedMilestone[0]);String url="https://api.github.com/repos/"+full+"/issues/"+number;OfflineMutationDispatcher.Result result=features.offlineDispatcher.executeOrQueue("PATCH",url,payload,"issue-meta:"+full+"#"+number,"Метки и участники Issue #"+number,url,"updated_at",issue.optString("updated_at",""));runOnUiThread(()->{dialog.dismiss();if(result.queued){toast("Изменение сохранено в офлайн-очереди");navigate(this::showOfflineQueue);}else{toast("Метки и участники обновлены");if(issue.has("pull_request")||currentTitle.startsWith("PR #"))showPullDetail(full,number);else showIssueDetail(full,number);}});}catch(Exception e){runOnUiThread(()->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);errorDialog("Не удалось обновить Issue",e.getMessage());});}});}));dialog.show();
        });}catch(Exception e){runOnUiThread(()->{progress.dismiss();errorDialog("Не удалось загрузить данные репозитория",e.getMessage());});}});
    }

    private void showMultiSelect(String title,JSONArray source,String key,Set<String> selected,Runnable changed){String[] names=new String[source.length()];boolean[] checked=new boolean[source.length()];for(int i=0;i<source.length();i++){JSONObject item=source.optJSONObject(i);names[i]=item==null?"":item.optString(key,"");checked[i]=selected.contains(names[i]);}new AlertDialog.Builder(this).setTitle(title).setMultiChoiceItems(names,checked,(d,which,isChecked)->{if(isChecked)selected.add(names[which]);else selected.remove(names[which]);}).setPositiveButton("Готово",(d,w)->changed.run()).setNegativeButton("Отмена",null).show();}
    private void showMilestoneSelect(JSONArray milestones,Integer[] selected,Runnable changed){String[] names=new String[milestones.length()+1];names[0]="Без этапа";int checked=0;for(int i=0;i<milestones.length();i++){JSONObject m=milestones.optJSONObject(i);names[i+1]=m==null?"":m.optString("title","");if(m!=null&&selected[0]!=null&&m.optInt("number")==selected[0])checked=i+1;}new AlertDialog.Builder(this).setTitle("Этап").setSingleChoiceItems(names,checked,(d,which)->{selected[0]=which==0?null:milestones.optJSONObject(which-1).optInt("number");d.dismiss();changed.run();}).setNegativeButton("Отмена",null).show();}
    private String metadataSummary(String label,Set<String> values){return label+": "+(values.isEmpty()?"не выбраны":android.text.TextUtils.join(", ",values));}
    private String milestoneTitle(JSONArray milestones,Integer number){if(number==null)return "не выбран";for(int i=0;i<milestones.length();i++){JSONObject m=milestones.optJSONObject(i);if(m!=null&&m.optInt("number")==number)return m.optString("title","#"+number);}return "#"+number;}

    private void showPullDetail(String full,int number) {
        rememberScreen("pull",routeArgs("full",full,"number",number));
        setCurrentScreen(() -> showPullDetail(full,number));shell("PR #"+number,true);ProgressBar pb=new ProgressBar(this);content.addView(pb);
        io.execute(() -> {try {
            JSONObject pull=getJson("https://api.github.com/repos/"+full+"/pulls/"+number);
            JSONArray comments=getArray("https://api.github.com/repos/"+full+"/issues/"+number+"/comments?per_page=50");
            JSONArray reviews;
            try{reviews=getArray("https://api.github.com/repos/"+full+"/pulls/"+number+"/reviews?per_page=50");}catch(Exception ignored){reviews=new JSONArray();}
            JSONArray finalReviews=reviews;
            runOnUiThread(() -> {
                content.removeView(pb);heroBanner(pull.optString("title"),full,R.drawable.ic_pull_request);
                JSONObject author=pull.optJSONObject("user");String state=pull.optBoolean("merged",false)?"Слит":pull.optString("state","open").equals("open")?"Открыт":"Закрыт";
                TextView meta=text((author==null?"":author.optString("login",""))+" · "+state+" · "+pull.optJSONObject("head").optString("ref","")+" → "+pull.optJSONObject("base").optString("ref",""),11,false);meta.setTextColor(MUTED);card(meta);
                addTranslatableMarkdownCard("pr:"+full+"#"+number,pull.optString("body","Без описания"),pull.optString("updated_at",""));
                GridLayout stat=new GridLayout(this);stat.setColumnCount(adaptiveColumns(3));addMiniStat(stat,String.valueOf(pull.optInt("commits")),"Коммиты",BLUE);addMiniStat(stat,String.valueOf(pull.optInt("additions")),"Добавлено",GREEN);addMiniStat(stat,String.valueOf(pull.optInt("deletions")),"Удалено",Color.rgb(220,80,80));content.addView(stat);
                GridLayout actions=new GridLayout(this);actions.setColumnCount(adaptiveColumns(2));
                addAction(actions,"Коммиты",()->navigate(() -> showPullCommits(full,number)));
                addAction(actions,"Файлы и diff",()->navigate(() -> showPullFiles(full,number)));
                addAction(actions,"Проверки",()->navigate(() -> showPullChecks(full,number,pull)));
                addAction(actions,"Комментарий",()->showIssueCommentDialog(full,number));
                if(isSignedIn()){
                    addAction(actions,"Ревью",()->showPullReviewDialog(full,number));
                    addAction(actions,"Метки и участники",()->showIssueMetadataDialog(full,number,pull));
                    if("open".equals(pull.optString("state","open"))&&!pull.optBoolean("merged",false))addAction(actions,"Слить PR",()->showMergePullDialog(full,number,pull));
                }
                addAction(actions,"👍 Реакция",()->postIssueReaction(full,number,"+1"));
                addAction(actions,"Открыть GitHub",()->openWeb(pull.optString("html_url","https://github.com/"+full+"/pull/"+number)));
                content.addView(actions);
                sectionTitle("Обсуждение: "+comments.length(),null);
                for(int i=0;i<comments.length();i++){JSONObject c=comments.optJSONObject(i);if(c==null)continue;LinearLayout b=new LinearLayout(this);b.setOrientation(LinearLayout.VERTICAL);JSONObject u=c.optJSONObject("user");b.addView(text(u==null?"GitHub":u.optString("login","GitHub"),13,true));b.addView(translatableMarkdownPanel("pr-comment:"+c.optLong("id",0),c.optString("body",""),c.optString("updated_at","")));card(b);}
                if(finalReviews.length()>0){sectionTitle("Ревью: "+finalReviews.length(),null);for(int i=0;i<finalReviews.length();i++){JSONObject r=finalReviews.optJSONObject(i);if(r==null)continue;LinearLayout b=new LinearLayout(this);b.setOrientation(LinearLayout.VERTICAL);JSONObject u=r.optJSONObject("user");b.addView(text((u==null?"GitHub":u.optString("login","GitHub"))+" · "+reviewStateLabel(r.optString("state","")),13,true));String body=r.optString("body","");if(!body.isEmpty())b.addView(translatableMarkdownPanel("pr-review:"+r.optLong("id",0),body,r.optString("submitted_at","")));card(b);}}
            });
        }catch(Exception e){runOnUiThread(() -> {content.removeView(pb);errorCard("Не удалось открыть PR",e.getMessage());});}});
    }

    private String reviewStateLabel(String state){
        switch(state){case "APPROVED":return "Одобрено";case "CHANGES_REQUESTED":return "Запрошены изменения";case "COMMENTED":return "Комментарий";case "DISMISSED":return "Отклонено";default:return state;}
    }

    private void showPullCommits(String full,int number){showPullCommitsPage(full,number,1,true);}
    private void showPullCommitsPage(String full,int number,int page,boolean first){
        if(first){setCurrentScreen(()->showPullCommits(full,number));shell("Коммиты PR",true);repoSectionHeader(full,"Коммиты PR","#"+number,R.drawable.ic_pull_request);}ProgressBar pb=new ProgressBar(this);content.addView(pb);
        io.execute(()->{try{JSONArray commits=getArray("https://api.github.com/repos/"+full+"/pulls/"+number+"/commits?per_page=50&page="+page);runOnUiThread(()->{content.removeView(pb);if(first)sectionTitle("Коммиты",null);for(int i=0;i<commits.length();i++){JSONObject item=commits.optJSONObject(i);if(item==null)continue;JSONObject commit=item.optJSONObject("commit");JSONObject author=commit==null?null:commit.optJSONObject("author");LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);String message=commit==null?"Коммит":commit.optString("message","Коммит");row.addView(text(firstLine(message),14,true));String sha=item.optString("sha","");TextView meta=text(sha.substring(0,Math.min(7,sha.length()))+(author==null?"":" · "+author.optString("name",""))+" · "+formatGitHubDate(author==null?"":author.optString("date","")),11,false);meta.setTextColor(MUTED);row.addView(meta);if(CommitDetailController.validSha(sha))
                            row.setOnClickListener(v->navigate(()->showCommitDetail(full,sha)));
                        card(row);}if(commits.length()==50)addLoadMoreButton("Показать ещё коммиты",()->showPullCommitsPage(full,number,page+1,false));});}catch(Exception e){runOnUiThread(()->{content.removeView(pb);errorCard("Не удалось загрузить коммиты",e.getMessage());});}});
    }

    private void showPullFiles(String full,int number){showPullFilesPage(full,number,1,true);}
    private void showPullFilesPage(String full,int number,int page,boolean first){
        if(first){setCurrentScreen(()->showPullFiles(full,number));shell("Файлы PR",true);repoSectionHeader(full,"Файлы PR","#"+number,R.drawable.ic_pull_request);}ProgressBar pb=new ProgressBar(this);content.addView(pb);
        io.execute(()->{try{JSONArray files=getArray("https://api.github.com/repos/"+full+"/pulls/"+number+"/files?per_page=50&page="+page);runOnUiThread(()->{content.removeView(pb);if(first)sectionTitle("Изменённые файлы",null);for(int i=0;i<files.length();i++){JSONObject f=files.optJSONObject(i);if(f==null)continue;int index=i;LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);row.addView(text(f.optString("filename"),14,true));TextView m=text("+"+f.optInt("additions")+"  −"+f.optInt("deletions")+" · "+f.optString("status"),11,false);m.setTextColor(MUTED);row.addView(m);String patch=f.optString("patch","");TextView open=text(patch.isEmpty()?"Предпросмотр файла ›":"Открыть diff ›",12,true);open.setTextColor(BLUE);row.addView(open);row.setOnClickListener(v->navigate(()->showAdvancedDiff(full,number,files,index)));card(row);}if(files.length()==50)addLoadMoreButton("Показать ещё файлы",()->showPullFilesPage(full,number,page+1,false));});}catch(Exception e){runOnUiThread(()->{content.removeView(pb);errorCard("Не удалось загрузить файлы",e.getMessage());});}});
    }

    private void showAdvancedDiff(String full,int number,JSONArray files,int index){
        JSONObject file=files.optJSONObject(index);if(file==null)return;String filename=file.optString("filename","diff");
        setCurrentScreen(() -> showAdvancedDiff(full,number,files,index));shell("Diff",true);heroBanner(filename,full+" · PR #"+number,R.drawable.ic_repository);
        TextView stats=text("+"+file.optInt("additions")+"  −"+file.optInt("deletions")+" · "+file.optInt("changes")+" изменений",12,true);stats.setTextColor(MUTED);card(stats);
        LinearLayout navigation=new LinearLayout(this);Button prev=compactButton("← Файл");prev.setEnabled(index>0);prev.setOnClickListener(v->showAdvancedDiff(full,number,files,index-1));navigation.addView(prev,new LinearLayout.LayoutParams(0,dp(44),1));Button next=compactButton("Файл →");next.setEnabled(index<files.length()-1);next.setOnClickListener(v->showAdvancedDiff(full,number,files,index+1));LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(0,dp(44),1);np.setMargins(dp(6),0,0,0);navigation.addView(next,np);content.addView(navigation);
        LinearLayout modes=new LinearLayout(this);Button mode=compactButton("split".equals(featureState.diffMode)?"Режим: Split":"Режим: Unified");mode.setOnClickListener(v->{featureState.diffMode="split".equals(featureState.diffMode)?"unified":"split";prefs.edit().putString("diff_mode",featureState.diffMode).apply();showAdvancedDiff(full,number,files,index);});modes.addView(mode,new LinearLayout.LayoutParams(0,dp(44),1));Button collapse=compactButton(featureState.collapseDiffContext?"Контекст свёрнут":"Контекст полностью");collapse.setOnClickListener(v->{featureState.collapseDiffContext=!featureState.collapseDiffContext;prefs.edit().putBoolean("diff_collapse_context",featureState.collapseDiffContext).apply();showAdvancedDiff(full,number,files,index);});LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,dp(44),1);cp.setMargins(dp(6),0,0,0);modes.addView(collapse,cp);content.addView(modes);
        String patch=file.optString("patch","");if(patch.isEmpty()){card(text("GitHub не вернул текстовый patch: файл бинарный или изменение слишком большое.",13,false));String raw=file.optString("raw_url","");if(!raw.isEmpty()){Button preview=button("Открыть содержимое");preview.setOnClickListener(v->showRemotePreview(filename,raw,file.optString("blob_url","")));content.addView(preview,new LinearLayout.LayoutParams(-1,dp(48)));}return;}
        DiffModel model=DiffModel.parse(patch);List<DiffModel.Line> lines=model.displayLines(featureState.collapseDiffContext,3);TextView info=text("Изменённых строк: "+model.changedLines()+" · нажмите изменённую строку для комментария",11,false);info.setTextColor(MUTED);card(info);
        if("split".equals(featureState.diffMode))renderSplitDiff(full,number,file,lines);else renderUnifiedDiff(full,number,file,lines);
    }

    private void renderUnifiedDiff(String full,int number,JSONObject file,List<DiffModel.Line> lines){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setBackground(round(CARD,14,BORDER,1));int shown=0;
        for(DiffModel.Line line:lines){if(shown++>650){TextView more=text("Diff сокращён после 650 строк. Откройте GitHub для полного просмотра.",12,true);more.setTextColor(MUTED);more.setPadding(dp(10),dp(10),dp(10),dp(10));box.addView(more);break;}TextView row=text(diffPrefix(line)+line.text,11,false);row.setTypeface(android.graphics.Typeface.MONOSPACE);row.setTextIsSelectable(true);row.setPadding(dp(8),dp(3),dp(8),dp(3));applyDiffLineStyle(row,line);if(line.kind==DiffModel.Kind.ADD||line.kind==DiffModel.Kind.DELETE)row.setOnClickListener(v->showLineCommentDialog(full,number,file,line));box.addView(row,new LinearLayout.LayoutParams(-1,-2));}
        content.addView(box,new LinearLayout.LayoutParams(-1,-2));
    }

    private void renderSplitDiff(String full,int number,JSONObject file,List<DiffModel.Line> lines){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setBackground(round(CARD,14,BORDER,1));int shown=0;
        for(DiffModel.Line line:lines){if(shown++>450){box.addView(text("Split diff сокращён после 450 строк.",12,true));break;}if(line.kind==DiffModel.Kind.HUNK||line.kind==DiffModel.Kind.HEADER||line.kind==DiffModel.Kind.META){TextView h=text(line.text,11,true);h.setTypeface(android.graphics.Typeface.MONOSPACE);h.setTextColor(line.kind==DiffModel.Kind.HUNK?BLUE:MUTED);h.setPadding(dp(8),dp(5),dp(8),dp(5));box.addView(h);continue;}LinearLayout pair=new LinearLayout(this);pair.setOrientation(LinearLayout.HORIZONTAL);String left=(line.kind==DiffModel.Kind.ADD)?"":String.format(Locale.ROOT,"%4s  %s",line.oldLine>0?line.oldLine:"",line.text);String right=(line.kind==DiffModel.Kind.DELETE)?"":String.format(Locale.ROOT,"%4s  %s",line.newLine>0?line.newLine:"",line.text);TextView l=text(left,10,false);TextView r=text(right,10,false);l.setTypeface(android.graphics.Typeface.MONOSPACE);r.setTypeface(android.graphics.Typeface.MONOSPACE);l.setPadding(dp(5),dp(3),dp(5),dp(3));r.setPadding(dp(5),dp(3),dp(5),dp(3));if(line.kind==DiffModel.Kind.DELETE){l.setTextColor(Color.rgb(198,62,72));l.setBackgroundColor(blend(CARD,Color.rgb(198,62,72),0.10f));l.setOnClickListener(v->showLineCommentDialog(full,number,file,line));}else if(line.kind==DiffModel.Kind.ADD){r.setTextColor(GREEN);r.setBackgroundColor(blend(CARD,GREEN,0.11f));r.setOnClickListener(v->showLineCommentDialog(full,number,file,line));}else{l.setTextColor(MUTED);r.setTextColor(TEXT);}pair.addView(l,new LinearLayout.LayoutParams(0,-2,1));pair.addView(r,new LinearLayout.LayoutParams(0,-2,1));box.addView(pair);}
        content.addView(box,new LinearLayout.LayoutParams(-1,-2));
    }

    private String diffPrefix(DiffModel.Line line){if(line.kind==DiffModel.Kind.ADD)return String.format(Locale.ROOT,"+ %4d  ",line.newLine);if(line.kind==DiffModel.Kind.DELETE)return String.format(Locale.ROOT,"- %4d  ",line.oldLine);if(line.kind==DiffModel.Kind.CONTEXT)return String.format(Locale.ROOT,"  %4d  ",line.newLine);return "  ";}
    private void applyDiffLineStyle(TextView row,DiffModel.Line line){if(line.kind==DiffModel.Kind.ADD){row.setTextColor(GREEN);row.setBackgroundColor(blend(CARD,GREEN,0.10f));}else if(line.kind==DiffModel.Kind.DELETE){row.setTextColor(Color.rgb(198,62,72));row.setBackgroundColor(blend(CARD,Color.rgb(198,62,72),0.09f));}else if(line.kind==DiffModel.Kind.HUNK){row.setTextColor(BLUE);row.setTypeface(android.graphics.Typeface.MONOSPACE,android.graphics.Typeface.BOLD);}else if(line.kind==DiffModel.Kind.META||line.kind==DiffModel.Kind.HEADER)row.setTextColor(MUTED);else row.setTextColor(TEXT);}

    private void showLineCommentDialog(String full,int number,JSONObject file,DiffModel.Line line){
        ProgressDialog progress=ProgressDialog.show(this,"Комментарий к строке","Получаем актуальный commit SHA…",true,false);io.execute(()->{try{JSONObject pull=getJson("https://api.github.com/repos/"+full+"/pulls/"+number);JSONObject head=pull.optJSONObject("head");String commit=head==null?"":head.optString("sha","");runOnUiThread(()->{progress.dismiss();LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(18),0,dp(18),0);TextView where=text(file.optString("filename")+" · "+line.side()+" · строка "+line.commentLine(),12,true);where.setTextColor(MUTED);form.addView(where);EditText body=new EditText(this);body.setHint("Комментарий");body.setMinLines(4);body.setGravity(Gravity.TOP);form.addView(body,new LinearLayout.LayoutParams(-1,dp(130)));Switch suggestion=new Switch(this);suggestion.setText("Оформить как suggested change");form.addView(suggestion);EditText replacement=new EditText(this);replacement.setHint("Предлагаемый код");replacement.setMinLines(3);replacement.setTypeface(android.graphics.Typeface.MONOSPACE);replacement.setVisibility(View.GONE);form.addView(replacement,new LinearLayout.LayoutParams(-1,dp(110)));suggestion.setOnCheckedChangeListener((b,checked)->replacement.setVisibility(checked?View.VISIBLE:View.GONE));AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Комментарий к строке").setView(form).setPositiveButton("Отправить",null).setNegativeButton("Отмена",null).create();dialog.setOnShowListener(x->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String note=body.getText().toString().trim();if(!suggestion.isChecked()&&note.isEmpty()){body.setError("Введите комментарий");return;}dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);io.execute(()->{try{if(suggestion.isChecked())features.pullAdvanced.createSuggestedChange(full,number,replacement.getText().toString(),note,commit,file.optString("filename"),line.commentLine(),line.side(),null,null);else features.pullAdvanced.createLineComment(full,number,note,commit,file.optString("filename"),line.commentLine(),line.side(),null,null);runOnUiThread(()->{dialog.dismiss();toast("Комментарий к строке отправлен");});}catch(Exception e){runOnUiThread(()->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);errorDialog("Не удалось отправить комментарий",e.getMessage());});}});}));dialog.show();});}catch(Exception e){runOnUiThread(()->{progress.dismiss();errorDialog("Не удалось подготовить комментарий",e.getMessage());});}});
    }

    private String firstLine(String value){if(value==null)return "";int i=value.indexOf('\n');return i<0?value:value.substring(0,i);}

    private void showRepoReleases(String full){rememberScreen("repo_releases",routeArgs("full",full));showRepoReleasesPage(full,1,true);}
    private void showRepoReleasesPage(String full,int page,boolean first){
        if(first){setCurrentScreen(()->showRepoReleases(full));shell("Релизы",true);repoSectionHeader(full,"Релизы","Версии и файлы",R.drawable.ic_release);}ProgressBar pb=new ProgressBar(this);content.addView(pb);
        io.execute(()->{try{JSONArray releases=getArray("https://api.github.com/repos/"+full+"/releases?per_page=20&page="+page);runOnUiThread(()->{content.removeView(pb);if(releases.length()==0&&first){card(text("У репозитория пока нет релизов.",15,false));return;}for(int i=0;i<releases.length();i++){JSONObject release=releases.optJSONObject(i);if(release!=null)renderReleaseCard(release);}if(releases.length()==20)addLoadMoreButton("Показать ещё релизы",()->showRepoReleasesPage(full,page+1,false));});}catch(Exception e){runOnUiThread(()->{content.removeView(pb);errorCard("Не удалось загрузить релизы",e.getMessage());});}});
    }

    private void renderReleaseCard(JSONObject release){
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        String releaseName=cleanNullable(release.optString("name",""));
        String tagName=cleanNullable(release.optString("tag_name",""));
        if(releaseName.isEmpty())releaseName=tagName.isEmpty()?"Релиз":tagName;
        TextView title=text(releaseName,17,true);
        title.setPadding(dp(2),0,dp(2),dp(2));
        box.addView(title);

        StringBuilder metaText=new StringBuilder();
        if(!tagName.isEmpty())metaText.append(tagName);
        if(release.optBoolean("prerelease"))metaText.append(metaText.length()>0?" · prerelease":"prerelease");
        if(release.optBoolean("draft"))metaText.append(metaText.length()>0?" · draft":"draft");
        String published=formatGitHubDate(release.optString("published_at",release.optString("created_at","")));
        if(!published.isEmpty())metaText.append(metaText.length()>0?" · ":"").append(published);
        if(metaText.length()>0){
            TextView meta=text(metaText.toString(),11,false);
            meta.setTextColor(MUTED);
            meta.setPadding(dp(2),0,dp(2),dp(8));
            box.addView(meta);
        }

        String bodyValue=release.optString("body","");
        if(!bodyValue.trim().isEmpty()){
            box.addView(translatableMarkdownPanel("release:"+release.optLong("id",0),bodyValue,release.optString("updated_at",release.optString("published_at",""))));
        }

        JSONArray assets=release.optJSONArray("assets");
        if(assets!=null&&assets.length()>0){
            TextView files=text("Файлы релиза · "+assets.length(),13,true);
            files.setPadding(dp(2),dp(4),dp(2),dp(3));
            box.addView(files);
            for(int j=0;j<assets.length();j++){
                JSONObject asset=assets.optJSONObject(j);
                if(asset!=null)addReleaseAssetRow(box,asset);
            }
        }else{
            TextView noFiles=text("Прикреплённых файлов нет",12,false);
            noFiles.setTextColor(MUTED);
            noFiles.setPadding(dp(2),dp(6),dp(2),dp(2));
            box.addView(noFiles);
        }
        card(box);
    }

    private void addReleaseAssetRow(LinearLayout parent,JSONObject asset){
        String assetName=asset.optString("name","release.bin");
        String url=asset.optString("browser_download_url","");
        long size=asset.optLong("size",0L);
        int downloads=asset.optInt("download_count",0);
        String contentType=cleanNullable(asset.optString("content_type",""));

        LinearLayout row=new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12),dp(10),dp(8),dp(10));
        row.setBackground(round(SUBTLE,14,BORDER,1));

        ImageView icon=imageIcon(R.drawable.ic_release,GREEN,22);
        row.addView(icon,new LinearLayout.LayoutParams(dp(34),dp(34)));

        LinearLayout info=new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(7),0,dp(7),0);
        TextView fileName=text(assetName,13,true);
        fileName.setPadding(0,0,0,dp(2));
        fileName.setMaxLines(3);
        info.addView(fileName);
        StringBuilder details=new StringBuilder(formatAssetSize(size));
        if(!contentType.isEmpty())details.append(" · ").append(shortContentType(contentType));
        if(downloads>0)details.append(" · ").append(downloads).append(" загрузок");
        TextView fileMeta=text(details.toString(),10,false);
        fileMeta.setTextColor(MUTED);
        fileMeta.setPadding(0,0,0,0);
        info.addView(fileMeta);
        row.addView(info,new LinearLayout.LayoutParams(0,-2,1));

        Button download=new Button(this);
        download.setText("Скачать");
        download.setAllCaps(false);
        download.setTextSize(12);
        download.setTextColor(GREEN);
        download.setPadding(dp(10),0,dp(10),0);
        download.setMinWidth(0);
        download.setMinimumWidth(0);
        download.setBackground(round(blend(CARD,GREEN,0.08f),12,GREEN,1));
        download.setOnClickListener(v->enqueueReleaseDownload(assetName,url));
        row.addView(download,new LinearLayout.LayoutParams(dp(102),dp(42)));

        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
        params.setMargins(0,dp(5),0,dp(5));
        parent.addView(row,params);
    }

    private String formatAssetSize(long bytes){
        if(bytes<=0)return "Размер не указан";
        double value=bytes;
        String[] units={"Б","КБ","МБ","ГБ"};
        int unit=0;
        while(value>=1024d&&unit<units.length-1){value/=1024d;unit++;}
        if(unit==0)return ((long)value)+" "+units[unit];
        return String.format(Locale.getDefault(),value>=10d?"%.0f %s":"%.1f %s",value,units[unit]);
    }

    private String shortContentType(String value){
        if(value==null||value.isEmpty())return "файл";
        int slash=value.indexOf('/');
        String type=slash>=0&&slash<value.length()-1?value.substring(slash+1):value;
        return type.replace("application/","").replace("x-","");
    }

    private void enqueueReleaseDownload(String name,String url){
        if(url==null||url.isEmpty()){toast("Ссылка загрузки отсутствует");return;}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},301);}
        try{
            String safe=name==null||name.trim().isEmpty()?"github-release.bin":name.replaceAll("[\\/:*?\"<>|]","_");
            DownloadManager.Request request=new DownloadManager.Request(Uri.parse(url));
            request.setTitle(safe);request.setDescription("GitHubble · загрузка релиза");request.setMimeType("application/octet-stream");
            request.setAllowedOverMetered(true);request.setAllowedOverRoaming(false);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,safe);
            DownloadManager manager=(DownloadManager)getSystemService(DOWNLOAD_SERVICE);long id=manager.enqueue(request);
            prefs.edit().putLong("last_download_id",id).putString("last_download_name",safe).apply();
            showDownloadProgress(manager,id,safe);
        }catch(Exception e){toast("Не удалось начать загрузку: "+e.getMessage());}
    }

    private void showDownloadProgress(DownloadManager manager,long id,String name){
        ProgressDialog dialog=new ProgressDialog(this);dialog.setTitle("Загрузка релиза");dialog.setMessage(name);dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);dialog.setMax(100);dialog.setIndeterminate(true);dialog.setCancelable(true);dialog.show();
        Handler handler=new Handler(Looper.getMainLooper());
        Runnable poll=new Runnable(){@Override public void run(){
            if(!dialog.isShowing())return;
            DownloadManager.Query query=new DownloadManager.Query().setFilterById(id);
            try(android.database.Cursor cursor=manager.query(query)){
                if(cursor==null||!cursor.moveToFirst()){dialog.dismiss();toast("Загрузка не найдена");return;}
                int status=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                long total=cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                long done=cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                if(total>0){dialog.setIndeterminate(false);dialog.setProgress((int)Math.min(100,done*100L/total));}
                if(status==DownloadManager.STATUS_SUCCESSFUL){dialog.dismiss();toast("Файл сохранён в Downloads");return;}
                if(status==DownloadManager.STATUS_FAILED){int reason=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));dialog.dismiss();toast("Ошибка загрузки: "+reason);return;}
            }catch(Exception e){dialog.dismiss();toast("Не удалось проверить загрузку");return;}
            handler.postDelayed(this,800);
        }};
        handler.post(poll);
    }

    private void openRepoUrl(String full,String suffix){String u="https://github.com/"+full+(suffix.isEmpty()?"":"/"+suffix);startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(u)));}

    private void showRepositoryFiles(String full,String branch,String path){
        setCurrentScreen(() -> showRepositoryFiles(full, branch, path));
        shell("Файлы",true);repoSectionHeader(full,"Файлы",path.isEmpty()?"Корень · "+branch:path,R.drawable.ic_repository);ProgressBar pb=new ProgressBar(this);content.addView(pb);
        io.execute(()->{try{JSONArray a=getArray("https://api.github.com/repos/"+full+"/contents/"+path+"?ref="+URLEncoder.encode(branch,"UTF-8"));runOnUiThread(()->{content.removeView(pb);if(!path.isEmpty()){Button up=button("Назад к родительской папке");String parent=path.contains("/")?path.substring(0,path.lastIndexOf('/')):"";up.setOnClickListener(v->navigate(() -> showRepositoryFiles(full,branch,parent)));content.addView(up,new LinearLayout.LayoutParams(-1,dp(48)));}for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;String name=o.optString("name"),type=o.optString("type"),p=o.optString("path"),download=o.optString("download_url"),html=o.optString("html_url");long size=o.optLong("size");LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);ImageView ic=imageIcon("dir".equals(type)?R.drawable.ic_projects:R.drawable.ic_repository,"dir".equals(type)?tileColors[3]:BLUE,14);row.addView(ic,new LinearLayout.LayoutParams(dp(46),dp(46)));LinearLayout info=new LinearLayout(this);info.setOrientation(LinearLayout.VERTICAL);info.addView(text(name,15,true));TextView meta=text("dir".equals(type)?"Папка":"Файл · "+formatAssetSize(size),11,false);meta.setTextColor(MUTED);info.addView(meta);row.addView(info,new LinearLayout.LayoutParams(0,-2,1));row.setOnClickListener(v->{if("dir".equals(type))navigate(() -> showRepositoryFiles(full,branch,p));else navigate(() -> showFilePreview(full,branch,p,name,size,download,html));});card(row);}});}catch(Exception e){runOnUiThread(()->{content.removeView(pb);errorCard("Не удалось загрузить файлы",e.getMessage());});}});
    }

    /** Edit text inside GitHubble using GitHub Contents API, without force overwrite. */
    private void showFileEditor(String full,String branch,String path){
        if(!CommitDetailController.validRepository(full)||path==null||path.isEmpty()
                ||branch==null||branch.isEmpty()){
            errorDialog("Редактор","Некорректный репозиторий, ветка или путь.");return;
        }
        setCurrentScreen(()->showFileEditor(full,branch,path));
        shell("Редактор файла",false);
        repoSectionHeader(full,"Редактор файла",path+" · "+branch,R.drawable.ic_repository);
        if(!isSignedIn()){accountRequiredCard("Для редактирования войдите в GitHub.");return;}
        final long generation=screenGeneration.get();
        final String account=currentAccountId();
        final LinearLayout destination=content;
        ProgressBar loading=new ProgressBar(this);destination.addView(loading);
        io.execute(()->{
            try{
                app.githubble.mobile.controller.GitFileEditorController editor=
                        new app.githubble.mobile.controller.GitFileEditorController(api);
                app.githubble.mobile.controller.GitFileEditorController.Snapshot original=
                        editor.load(full,branch,path);
                runOnUiThread(()->{
                    if(generation!=screenGeneration.get()||destination!=content
                            ||!account.equals(currentAccountId()))return;
                    destination.removeView(loading);
                    EditText source=new EditText(this);
                    source.setText(original.text);
                    source.setTypeface(android.graphics.Typeface.MONOSPACE);
                    source.setTextSize(12);source.setGravity(Gravity.TOP|Gravity.START);
                    source.setInputType(InputType.TYPE_CLASS_TEXT|
                            InputType.TYPE_TEXT_FLAG_MULTI_LINE|
                            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                    destination.addView(source,new LinearLayout.LayoutParams(-1,dp(360)));
                    EditText message=new EditText(this);
                    message.setSingleLine(true);message.setHint("Сообщение коммита");
                    message.setText("Edit "+path+" via GitHubble");
                    destination.addView(message,new LinearLayout.LayoutParams(-1,dp(52)));
                    TextView note=text("Сохранение создаст коммит в выбранной ветке. "+
                            "Если файл изменился на GitHub, перезапись будет заблокирована.",12,false);
                    note.setTextColor(MUTED);destination.addView(note);
                    Button save=button("Создать коммит");
                    destination.addView(save,new LinearLayout.LayoutParams(-1,dp(52)));
                    save.setOnClickListener(v->{
                        String updated=source.getText().toString();
                        String commitMessage=message.getText().toString().trim();
                        if(commitMessage.isEmpty()){message.setError("Введите сообщение");return;}
                        if(updated.equals(original.text)){toast("Нет изменений");return;}
                        save.setEnabled(false);
                        io.execute(()->{
                            try{
                                editor.save(full,branch,path,original,updated,commitMessage);
                                runOnUiThread(()->{
                                    if(generation!=screenGeneration.get()||destination!=content
                                            ||!account.equals(currentAccountId()))return;
                                    toast("Коммит создан");
                                    navigate(()->showRepositoryFiles(full,branch,
                                            path.contains("/")?path.substring(0,path.lastIndexOf('/')):""));
                                });
                            }catch(Exception error){
                                runOnUiThread(()->{
                                    if(generation!=screenGeneration.get()||destination!=content
                                            ||!account.equals(currentAccountId()))return;
                                    save.setEnabled(true);
                                    errorDialog("Не удалось сохранить файл",error.getMessage());
                                });
                            }
                        });
                    });
                });
            }catch(Exception error){
                runOnUiThread(()->{
                    if(generation!=screenGeneration.get()||destination!=content
                            ||!account.equals(currentAccountId()))return;
                    destination.removeView(loading);
                    errorCard("Редактор недоступен",error.getMessage());
                });
            }
        });
    }

    private void showFilePreview(String full,String branch,String path,String name,long size,String download,String htmlUrl){
        setCurrentScreen(()->showFilePreview(full,branch,path,name,size,download,htmlUrl));shell(name,true);repoSectionHeader(full,"Просмотр файла",path,R.drawable.ic_repository);ProgressBar pb=new ProgressBar(this);content.addView(pb);
        io.execute(()->{try{FilePreviewController.Preview preview=features.filePreview.load(full,branch,path,name,size,download,htmlUrl);runOnUiThread(()->{content.removeView(pb);renderFilePreview(full,preview,download);
            if(preview.type==FilePreviewController.Type.TEXT&&preview.declaredSize<=512*1024){
                Button edit=compactButton("Редактировать и создать коммит");
                edit.setOnClickListener(v->navigate(()->showFileEditor(full,branch,path)));
                content.addView(edit,new LinearLayout.LayoutParams(-1,dp(48)));
            }
        });}catch(Exception e){runOnUiThread(()->{content.removeView(pb);errorCard("Не удалось открыть файл",e.getMessage());if(htmlUrl!=null&&!htmlUrl.isEmpty()){Button web=button("Открыть на GitHub");web.setOnClickListener(v->openWeb(htmlUrl));content.addView(web,new LinearLayout.LayoutParams(-1,dp(48)));}});}});
    }

    private void showRemotePreview(String name,String download,String htmlUrl){
        setCurrentScreen(()->showRemotePreview(name,download,htmlUrl));shell(name,true);heroBanner(name,"Просмотр изменённого файла",R.drawable.ic_repository);ProgressBar pb=new ProgressBar(this);content.addView(pb);
        io.execute(()->{try{FilePreviewController.Preview preview=features.filePreview.loadRemote(name,download,htmlUrl);runOnUiThread(()->{content.removeView(pb);renderFilePreview("Pull Request",preview,download);});}catch(Exception e){runOnUiThread(()->{content.removeView(pb);errorCard("Встроенный просмотр недоступен",e.getMessage());if(htmlUrl!=null&&!htmlUrl.isEmpty()){Button web=button("Открыть на GitHub");web.setOnClickListener(v->openWeb(htmlUrl));content.addView(web,new LinearLayout.LayoutParams(-1,dp(48)));}});}});
    }

    private void renderFilePreview(String scope,FilePreviewController.Preview preview,String downloadUrl){
        TextView meta=text(preview.mime+" · "+formatAssetSize(preview.declaredSize),12,false);meta.setTextColor(MUTED);card(meta);
        if(preview.type==FilePreviewController.Type.TEXT){renderCodeViewer(scope,preview.name,preview.text,1,"");return;}
        if(preview.type==FilePreviewController.Type.IMAGE){Bitmap bitmap=PerformanceMonitor.decodeSampled(preview.bytes, getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);if(bitmap!=null){ImageView image=new ImageView(this);image.setAdjustViewBounds(true);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setImageBitmap(bitmap);image.setBackgroundColor(CARD);LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(-1,-2);ip.setMargins(0,dp(8),0,dp(12));content.addView(image,ip);}else errorCard("Не удалось декодировать изображение","Файл можно скачать или открыть на GitHub.");}
        else if(preview.type==FilePreviewController.Type.SVG){TextView note=text("SVG показан как безопасный исходный код. Для визуального просмотра откройте официальный файл на GitHub.",12,false);note.setTextColor(MUTED);card(note);renderCodeViewer(scope,preview.name,preview.text,1,"");return;}
        else if(preview.type==FilePreviewController.Type.PDF){card(text("PDF не исполняется внутри GitHubble. Откройте документ в системном просмотрщике или на GitHub.",13,false));}
        else if(preview.type==FilePreviewController.Type.TOO_LARGE){card(text("Файл больше 12 МБ и не загружается в память приложения. Доступны безопасное скачивание и просмотр на GitHub.",13,false));}
        else card(text("Бинарный файл не преобразуется в текст, чтобы избежать повреждения и зависания приложения.",13,false));
        LinearLayout actions=new LinearLayout(this);if(downloadUrl!=null&&!downloadUrl.isEmpty()){Button download=compactButton("Скачать");download.setOnClickListener(v->enqueueReleaseDownload(preview.name,downloadUrl));actions.addView(download,new LinearLayout.LayoutParams(0,dp(46),1));}if(preview.htmlUrl!=null&&!preview.htmlUrl.isEmpty()){Button web=compactButton("Открыть GitHub");web.setOnClickListener(v->openWeb(preview.htmlUrl));LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(0,dp(46),1);wp.setMargins(dp(6),0,0,0);actions.addView(web,wp);}if(actions.getChildCount()>0)content.addView(actions);
    }

    private void renderCodeViewer(String full,String name,String raw,int requestedLine,String query){
        shell(name,true);repoSectionHeader(full,"Исходный код",raw.split("\n",-1).length+" строк",R.drawable.ic_repository);
        final int pageSize=400;String[] lines=raw.replace("\r","").split("\n",-1);int totalPages=Math.max(1,(lines.length+pageSize-1)/pageSize);
        int line=Math.max(1,Math.min(lines.length,requestedLine));int page=(line-1)/pageSize;int from=page*pageSize;int to=Math.min(lines.length,from+pageSize);
        LinearLayout tools=new LinearLayout(this);tools.setGravity(Gravity.CENTER_VERTICAL);
        Button search=compactButton(query==null||query.isEmpty()?"Найти":"Найти: "+limit(query,12));search.setOnClickListener(v->showCodeSearchDialog(full,name,raw,line,query));tools.addView(search,new LinearLayout.LayoutParams(0,dp(44),1));
        Button go=compactButton("К строке");go.setOnClickListener(v->showGoToLineDialog(full,name,raw,line,query));LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(0,dp(44),1);gp.setMargins(dp(5),0,0,0);tools.addView(go,gp);
        Button copy=compactButton("Копировать");copy.setOnClickListener(v->showCopyLinesDialog(name,raw));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,dp(44),1);cp.setMargins(dp(5),0,0,0);tools.addView(copy,cp);content.addView(tools);
        TextView status=text("Строки "+(from+1)+"–"+to+" из "+lines.length+" · страница "+(page+1)+"/"+totalPages,11,false);status.setTextColor(MUTED);status.setGravity(Gravity.CENTER);content.addView(status);
        String slice=numberedSlice(lines,from,to);TextView code=text("",12,false);code.setTypeface(android.graphics.Typeface.MONOSPACE);code.setTextIsSelectable(true);code.setHorizontallyScrolling(true);code.setBackground(round(CARD,14,BORDER,1));code.setPadding(dp(12),dp(12),dp(12),dp(12));
        code.setText(CodeHighlighter.highlight(slice,name,TEXT,MUTED,GREEN,blend(SUBTLE,Color.YELLOW,0.40f),query));content.addView(code,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout pager=new LinearLayout(this);Button prev=compactButton("← Предыдущие");prev.setEnabled(page>0);prev.setOnClickListener(v->renderCodeViewer(full,name,raw,Math.max(1,line-pageSize),query));pager.addView(prev,new LinearLayout.LayoutParams(0,dp(46),1));Button next=compactButton("Следующие →");next.setEnabled(page<totalPages-1);next.setOnClickListener(v->renderCodeViewer(full,name,raw,Math.min(lines.length,line+pageSize),query));LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(0,dp(46),1);np.setMargins(dp(6),0,0,0);pager.addView(next,np);content.addView(pager);
    }

    private Button compactButton(String label){Button b=button(label);b.setTextSize(12);b.setPadding(dp(6),dp(4),dp(6),dp(4));return b;}
    private void addLoadMoreButton(String label,Runnable action){Button more=button(label);more.setTextColor(GREEN);more.setBackground(round(CARD,14,GREEN,1));more.setOnClickListener(v->{content.removeView(more);action.run();});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(48));p.setMargins(0,dp(6),0,dp(12));content.addView(more,p);}
    private String numberedSlice(String[] lines,int from,int to){StringBuilder b=new StringBuilder();int width=String.valueOf(lines.length).length();for(int i=from;i<to;i++)b.append(String.format(Locale.ROOT,"%"+width+"d  %s\n",i+1,lines[i]));return b.toString();}
    private String addLineNumbers(String raw){String[] lines=raw.split("\n",-1);return numberedSlice(lines,0,lines.length);}
    private void showCodeSearchDialog(String full,String name,String raw,int currentLine,String oldQuery){final EditText input=new EditText(this);input.setSingleLine(true);input.setHint("Текст для поиска");input.setText(oldQuery==null?"":oldQuery);new AlertDialog.Builder(this).setTitle("Поиск в файле").setView(input).setPositiveButton("Найти",(d,w)->{String q=input.getText().toString();int idx=raw.toLowerCase(Locale.ROOT).indexOf(q.toLowerCase(Locale.ROOT));int line=idx<0?currentLine:1+raw.substring(0,idx).split("\n",-1).length-1;if(idx<0)toast("Совпадений не найдено");renderCodeViewer(full,name,raw,line,q);}).setNeutralButton("Очистить",(d,w)->renderCodeViewer(full,name,raw,currentLine,"")).setNegativeButton("Отмена",null).show();}
    private void showGoToLineDialog(String full,String name,String raw,int currentLine,String query){final EditText input=new EditText(this);input.setSingleLine(true);input.setInputType(InputType.TYPE_CLASS_NUMBER);input.setHint("Номер строки");new AlertDialog.Builder(this).setTitle("Перейти к строке").setView(input).setPositiveButton("Перейти",(d,w)->{int line=currentLine;try{line=Integer.parseInt(input.getText().toString());}catch(Exception ignored){}renderCodeViewer(full,name,raw,line,query);}).setNegativeButton("Отмена",null).show();}
    private void showCopyLinesDialog(String name,String raw){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);final EditText from=new EditText(this);from.setHint("С строки");from.setInputType(InputType.TYPE_CLASS_NUMBER);final EditText to=new EditText(this);to.setHint("По строку");to.setInputType(InputType.TYPE_CLASS_NUMBER);box.addView(from);box.addView(to);new AlertDialog.Builder(this).setTitle("Копировать диапазон").setView(box).setPositiveButton("Копировать",(d,w)->{String[] lines=raw.replace("\r","").split("\n",-1);int a=1,b=lines.length;try{a=Integer.parseInt(from.getText().toString());}catch(Exception ignored){}try{b=Integer.parseInt(to.getText().toString());}catch(Exception ignored){}a=Math.max(1,Math.min(lines.length,a));b=Math.max(a,Math.min(lines.length,b));StringBuilder out=new StringBuilder();for(int i=a-1;i<b;i++){if(out.length()>0)out.append('\n');out.append(lines[i]);}ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(cm!=null)cm.setPrimaryClip(ClipData.newPlainText(name+":"+a+"-"+b,out.toString()));toast("Строки "+a+"–"+b+" скопированы");}).setNegativeButton("Отмена",null).show();}

    private void showReadme(String full,String readme){setCurrentScreen(() -> showReadme(full,readme));shell("README",true);repoSectionHeader(full,"README","Документация проекта",R.drawable.ic_repository);showReadmeInline(full,readme);}
    private void showReadmeInline(String full,String readme){sectionTitle("README",null);addTranslatableMarkdownCard("readme:"+full,readme,prefs.getString("cache_repo_updated_"+full,""));}

    private TranslationEngine.Config translationConfig(){return translationController.config();}

    private void addTranslatableMarkdownCard(String scopeKey,String source,String updateMarker){
        LinearLayout panel=translatableMarkdownPanel(scopeKey,source,updateMarker);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.setMargins(0,0,0,dp(12));content.addView(panel,params);
    }
    private LinearLayout translatableMarkdownPanel(String scopeKey,String source,String updateMarker){
        String safeSource=source==null?"":source;LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(12),dp(10),dp(12),dp(10));panel.setBackground(round(CARD,16,BORDER,1));panel.setElevation(dp(1));
        renderTranslatablePanel(panel,scopeKey,safeSource,updateMarker,false);
        if(prefs.getBoolean("translator_auto",false)&&LanguageDetector.needsRussianTranslation(safeSource)&&!translationConfig().endpoint.isEmpty()&&translationCache.get(safeSource,updateMarker,"ru",translationConfig().identity())==null)new Handler(Looper.getMainLooper()).postDelayed(()->startTranslation(panel,scopeKey,safeSource,updateMarker),180L);
        return panel;
    }

    private void renderTranslatablePanel(LinearLayout panel,String scopeKey,String source,String updateMarker,boolean russian){
        panel.removeAllViews();TranslationEngine.Config config=translationConfig();LanguageDetector.Result detected=LanguageDetector.detect(source);TranslationCache.Entry cached=translationCache.get(source,updateMarker,"ru",config.identity());
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);TextView language=text(detected.displayName,10,true);language.setTextColor(detected.code.equals("ru")?GREEN:BLUE);language.setBackground(round(SUBTLE,10,Color.TRANSPARENT,0));language.setPadding(dp(8),dp(3),dp(8),dp(3));header.addView(language);TextView cacheState=text(cached==null?"":"  перевод сохранён",10,false);cacheState.setTextColor(MUTED);header.addView(cacheState,new LinearLayout.LayoutParams(0,-2,1));panel.addView(header);
        String shown=russian&&cached!=null?cached.translated:source;TextView body=markdownView(shown,14);body.setPadding(0,dp(8),0,dp(8));panel.addView(body);
        LinearLayout actions=new LinearLayout(this);Button original=compactButton("Оригинал");original.setEnabled(russian);original.setOnClickListener(v->renderTranslatablePanel(panel,scopeKey,source,updateMarker,false));actions.addView(original,new LinearLayout.LayoutParams(0,dp(44),1));
        Button translate=compactButton(cached==null?"Перевести":"Русский");translate.setEnabled(!detected.code.equals("ru"));translate.setOnClickListener(v->{if(cached!=null)renderTranslatablePanel(panel,scopeKey,source,updateMarker,true);else startTranslation(panel,scopeKey,source,updateMarker);});LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,dp(44),1);tp.setMargins(dp(6),0,0,0);actions.addView(translate,tp);panel.addView(actions);
    }

    private void startTranslation(LinearLayout panel,String scopeKey,String source,String updateMarker){
        TranslationEngine.Config config=translationConfig();if(config.endpoint.isEmpty()){toast("Сначала настройте провайдер перевода");configureTranslatorSettings();return;}
        panel.removeAllViews();TextView title=text("Переводим на русский…",14,true);panel.addView(title);ProgressBar progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setProgress(4);panel.addView(progress,new LinearLayout.LayoutParams(-1,dp(8)));TextView detail=text("Подготовка Markdown и защита технических фрагментов",11,false);detail.setTextColor(MUTED);panel.addView(detail);
        io.execute(()->{try{String translated=translationEngine.translate(source,"ru",config,(done,total)->runOnUiThread(()->{progress.setProgress(Math.max(4,done*100/Math.max(1,total)));detail.setText("Переведено частей: "+done+"/"+total);}));LanguageDetector.Result detected=LanguageDetector.detect(source);translationCache.put(source,updateMarker,"ru",config.identity(),translated,detected.code);runOnUiThread(()->renderTranslatablePanel(panel,scopeKey,source,updateMarker,true));}catch(Exception e){runOnUiThread(()->{panel.removeAllViews();TextView error=text("Не удалось перевести: "+e.getMessage(),13,false);error.setTextColor(Color.rgb(190,55,60));panel.addView(error);Button retry=compactButton("Повторить");retry.setOnClickListener(v->startTranslation(panel,scopeKey,source,updateMarker));panel.addView(retry,new LinearLayout.LayoutParams(-1,dp(44)));});}});
    }

    private void addAction(GridLayout g,String label,Runnable r){TextView v=text(label,14,true);v.setGravity(Gravity.CENTER);v.setTextColor(BLUE);v.setContentDescription(label);v.setMinHeight(dp(48));v.setBackground(round(CARD,14,BORDER,1));v.setOnClickListener(x->r.run());GridLayout.LayoutParams p=new GridLayout.LayoutParams();p.width=0;p.height=dp(isLargeText()?64:52);p.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);p.setMargins(dp(4),dp(4),dp(4),dp(4));g.addView(v,p);}

    private void renderMarkdownCard(String source){addTranslatableMarkdownCard("markdown:"+TranslationCache.sha256(source),source,"");}

    private TextView markdownView(String source,int sp){
        TextView view=text("",sp,false);
        view.setTextIsSelectable(true);
        view.setLineSpacing(0,1.12f);
        view.setAutoLinkMask(Linkify.WEB_URLS|Linkify.EMAIL_ADDRESSES);
        view.setLinksClickable(true);
        view.setText(buildMarkdownSpannable(source==null?"":source));
        return view;
    }

    private SpannableStringBuilder buildMarkdownSpannable(String source){
        SpannableStringBuilder out=new SpannableStringBuilder();
        boolean code=false;
        String[] lines=source.replace("\r","").split("\n",-1);
        for(String raw:lines){
            if(raw.trim().startsWith("```")){code=!code;continue;}
            String line=raw;
            int heading=0;
            while(heading<line.length()&&heading<6&&line.charAt(heading)=='#')heading++;
            if(heading>0&&heading<line.length()&&Character.isWhitespace(line.charAt(heading)))line=line.substring(heading).trim();
            line=line.replaceAll("^\\s*[-*]\\s+\\[xX]\\s+","☑ ")
                    .replaceAll("^\\s*[-*]\\s+\\[ \\]\\s+","☐ ")
                    .replaceAll("^\\s*[-*]\\s+","• ")
                    .replaceAll("^\\s*>\\s?","❯ ");
            line=line.replaceAll("!\\[([^]]*)]\\((https?://[^)]+)\\)","🖼 $1 — $2");
            line=line.replaceAll("\\[([^]]+)]\\((https?://[^)]+)\\)","$1 — $2");
            int start=out.length();
            out.append(line).append('\n');
            int finish=out.length();
            if(code){
                out.setSpan(new TypefaceSpan("monospace"),start,finish,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new BackgroundColorSpan(blend(CARD,MUTED,0.09f)),start,finish,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }else if(heading>0){
                out.setSpan(new StyleSpan(android.graphics.Typeface.BOLD),start,finish,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(heading<=2?1.22f:1.10f),start,finish,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }else if(line.contains("|")&&line.indexOf('|')!=line.lastIndexOf('|')){
                out.setSpan(new TypefaceSpan("monospace"),start,finish,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            applyInlineMarkdownSpans(out,start,finish);
        }
        return out;
    }

    private void applyInlineMarkdownSpans(SpannableStringBuilder text,int start,int end){
        String segment=text.subSequence(start,end).toString();
        java.util.regex.Matcher code=java.util.regex.Pattern.compile("`([^`]+)`").matcher(segment);
        while(code.find()){
            int a=start+code.start(),b=start+code.end();
            text.setSpan(new TypefaceSpan("monospace"),a,b,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new BackgroundColorSpan(blend(CARD,MUTED,0.12f)),a,b,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        java.util.regex.Matcher bold=java.util.regex.Pattern.compile("\\*\\*([^*]+)\\*\\*").matcher(segment);
        while(bold.find())text.setSpan(new StyleSpan(android.graphics.Typeface.BOLD),start+bold.start(),start+bold.end(),Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private String cleanMarkdown(String s){
        return s==null?"":s.replace("```","")
                .replaceAll("(?m)^#{1,6}\\s*","")
                .replaceAll("!\\[([^]]*)]\\(([^)]+)\\)","$1 — $2")
                .replaceAll("\\[([^]]+)]\\(([^)]+)\\)","$1 — $2")
                .replaceAll("(?m)^\\s*[-*]\\s+\\[xX]\\s+","☑ ")
                .replaceAll("(?m)^\\s*[-*]\\s+\\[ \\]\\s+","☐ ");
    }
    private void errorCard(String title,String message){LinearLayout b=new LinearLayout(this);b.setOrientation(LinearLayout.VERTICAL);TextView h=text(title,18,true);h.setTextColor(Color.rgb(190,55,60));b.addView(h);TextView m=text(message==null?"Неизвестная ошибка":message,14,false);m.setTextColor(MUTED);b.addView(m);card(b);}

    private void showTranslationInfo(){new AlertDialog.Builder(this).setTitle("Интеллектуальный перевод").setMessage("GitHubble автоматически определяет язык и переводит длинный Markdown частями. Кодовые блоки, inline-код, URL, пути, JSON/YAML, версии, хеши и разметка GFM защищаются плейсхолдерами и восстанавливаются после перевода. Переводы кэшируются по SHA содержимого и дате обновления.").setPositiveButton("Понятно",null).show();}

    private void showFavorites(){setCurrentScreen(this::showFavorites);shell("Избранное",true);Set<String> f=prefs.getStringSet("favorites",new LinkedHashSet<>());if(f.isEmpty()){card(text("Пока ничего не добавлено. Откройте репозиторий и нажмите «Избранное».",16,false));return;}sectionTitle("Сохранённые проекты",null);for(String s:f){LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);ImageView i=imageIcon(R.drawable.ic_star,Color.rgb(245,191,39),14);row.addView(i,new LinearLayout.LayoutParams(dp(44),dp(44)));TextView t=text(s,16,true);row.addView(t,new LinearLayout.LayoutParams(0,-2,1));row.setOnClickListener(v->navigate(() -> showRepository(s)));card(row);}}
    private void saveFavorite(String s){Set<String> old=new LinkedHashSet<>(prefs.getStringSet("favorites",new LinkedHashSet<>()));old.add(s);prefs.edit().putStringSet("favorites",old).apply();}

    private void showProfile(){rememberScreen("profile",null);renderProfilePage(true);}

    private void renderProfilePage(boolean refresh){
        setCurrentScreen(this::showProfile);shell("Профиль",false);
        if(!isSignedIn()){
            LinearLayout guest=new LinearLayout(this);guest.setOrientation(LinearLayout.VERTICAL);guest.setGravity(Gravity.CENTER);
            ImageView avatar=createProfileAvatar(dp(86));guest.addView(avatar,new LinearLayout.LayoutParams(dp(86),dp(86)));
            TextView name=text("Гостевой режим",22,true);name.setGravity(Gravity.CENTER);guest.addView(name);
            TextView note=text("Публичный поиск доступен без входа. Подключите GitHub, чтобы загрузить личный профиль и приватные данные, разрешённые GiHubble.",13,false);note.setTextColor(MUTED);note.setGravity(Gravity.CENTER);guest.addView(note);card(guest);
            Button login=button("Войти через GitHub · Device Flow");login.setOnClickListener(v->startSmartLogin());content.addView(login,new LinearLayout.LayoutParams(-1,dp(52)));
            Button register=button("Создать аккаунт GitHub");register.setTextColor(GREEN);register.setBackground(round(CARD,14,GREEN,1));register.setOnClickListener(v->startGitHubRegistration());
            LinearLayout.LayoutParams registerParams=new LinearLayout.LayoutParams(-1,dp(52));registerParams.setMargins(0,dp(9),0,0);content.addView(register,registerParams);
            TextView secureRegistration=text("Вход через официальный GitHub по восьмисимвольному коду Device Flow. Пароль GitHub приложение не получает.",11,false);secureRegistration.setTextColor(MUTED);secureRegistration.setGravity(Gravity.CENTER);secureRegistration.setPadding(dp(8),dp(11),dp(8),0);content.addView(secureRegistration);
            return;
        }
        JSONObject profile=getCachedProfile();
        if(profile.length()==0){
            LinearLayout loading=new LinearLayout(this);loading.setOrientation(LinearLayout.VERTICAL);loading.setGravity(Gravity.CENTER);
            ImageView avatar=createProfileAvatar(dp(86));loading.addView(avatar,new LinearLayout.LayoutParams(dp(86),dp(86)));
            TextView title=text("GitHub подключён",21,true);title.setGravity(Gravity.CENTER);loading.addView(title);
            TextView note=text("Загружаем сведения личного профиля…",13,false);note.setTextColor(MUTED);note.setGravity(Gravity.CENTER);loading.addView(note);card(loading);
        }else renderProfileContent(profile);
        if(refresh)refreshProfileData(false);
    }


    /** Native editor for all fields supported by GitHub's PATCH /user endpoint. */
    private void showNativeProfileEditor(){
        rememberScreen("profile_editor",null);
        setCurrentScreen(this::showNativeProfileEditor);
        shell("Редактирование профиля",false);
        if(!isSignedIn()){
            accountRequiredCard("Войдите в GitHub, чтобы редактировать профиль.");return;
        }
        final long generation=screenGeneration.get();
        final String account=currentAccountId();
        final LinearLayout destination=content;
        ProgressBar progress=new ProgressBar(this);
        destination.addView(progress);
        io.execute(()->{
            try{
                // Always read the current GitHub account: cached fields may be stale.
                JSONObject original=profileController.current();
                runOnUiThread(()->{
                    if(generation!=screenGeneration.get()||destination!=content
                            ||!account.equals(currentAccountId()))return;
                    destination.removeView(progress);
                    renderNativeProfileEditor(original,generation,account,destination);
                });
            }catch(Exception error){
                runOnUiThread(()->{
                    if(generation!=screenGeneration.get()||destination!=content
                            ||!account.equals(currentAccountId()))return;
                    destination.removeView(progress);
                    errorCard("Не удалось загрузить профиль",error.getMessage());
                    Button retry=compactButton("Повторить загрузку");
                    retry.setOnClickListener(v->showNativeProfileEditor());
                    destination.addView(retry,new LinearLayout.LayoutParams(-1,dp(48)));
                });
            }
        });
    }

    private EditText profileEditInput(String title,String value,String hint,
                                      int maxLength,boolean multiline,int inputType){
        TextView label=text(title,13,true);
        label.setPadding(dp(3),dp(11),dp(3),dp(5));
        content.addView(label);
        EditText field=new EditText(this);
        field.setText(value==null||"null".equalsIgnoreCase(value)? "":value);
        field.setHint(hint);
        field.setTextSize(14);
        field.setTextColor(TEXT);
        field.setHintTextColor(MUTED);
        field.setInputType(inputType);
        field.setSingleLine(!multiline);
        if(multiline){
            field.setGravity(Gravity.TOP|Gravity.START);
            field.setMinLines(3);
        }
        field.setFilters(new android.text.InputFilter[]{
                new android.text.InputFilter.LengthFilter(maxLength)});
        field.setPadding(dp(13),dp(10),dp(13),dp(10));
        field.setBackground(round(CARD,12,BORDER,1));
        content.addView(field,new LinearLayout.LayoutParams(-1,
                multiline?dp(112):dp(52)));
        return field;
    }

    private void renderNativeProfileEditor(JSONObject original,long generation,
                                           String account,LinearLayout destination){
        LinearLayout identity=new LinearLayout(this);
        identity.setGravity(Gravity.CENTER_VERTICAL);
        identity.setPadding(dp(10),dp(6),dp(10),dp(6));
        identity.addView(createProfileAvatar(dp(58)),
                new LinearLayout.LayoutParams(dp(58),dp(58)));
        TextView login=text("@"+original.optString("login","")+" · GitHub ID "+
                original.optLong("id",0L),13,true);
        login.setTextIsSelectable(true);
        login.setPadding(dp(12),0,0,0);
        identity.addView(login);
        card(identity);

        TextView details=text("Изменения сохраняются непосредственно в GitHub. "+
                "Логин, пароль и фотография не редактируются через этот API. "+
                "Для сохранения нужен доступ GitHub App: Profile → Write.",12,false);
        details.setTextColor(MUTED);
        details.setPadding(dp(3),dp(8),dp(3),dp(3));
        destination.addView(details);

        EditText name=profileEditInput("Имя",original.optString("name",""),
                "Как вас зовут",255,false,InputType.TYPE_CLASS_TEXT
                        |InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText bio=profileEditInput("О себе",original.optString("bio",""),
                "Краткое описание профиля (до 160 символов)",160,true,
                InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        |InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        EditText company=profileEditInput("Компания / организация",
                original.optString("company",""),"Где вы работаете",255,false,
                InputType.TYPE_CLASS_TEXT);
        EditText location=profileEditInput("Местоположение",
                original.optString("location",""),"Город, страна",255,false,
                InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText website=profileEditInput("Сайт",
                original.optString("blog",""),"https://example.com",255,false,
                InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        EditText twitter=profileEditInput("Имя пользователя X / Twitter",
                original.optString("twitter_username",""),"Без символа @",50,false,
                InputType.TYPE_CLASS_TEXT);
        EditText email=profileEditInput("Публичный email",
                original.optString("email",""),"example@domain.com (необязательно)",
                255,false,InputType.TYPE_CLASS_TEXT
                        |InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        TextView emailNote=text("Это адрес, отображаемый в профиле, а не управление "+
                "почтовыми адресами входа. GitHub применяет ваши настройки приватности.",11,false);
        emailNote.setTextColor(MUTED);
        emailNote.setPadding(dp(3),dp(4),dp(3),dp(7));
        destination.addView(emailNote);

        Switch hireable=new Switch(this);
        hireable.setText("Открыт(а) для предложений о работе");
        hireable.setTextColor(TEXT);
        hireable.setTextSize(13);
        hireable.setChecked(original.optBoolean("hireable",false));
        hireable.setPadding(dp(4),dp(12),dp(4),dp(12));
        destination.addView(hireable,new LinearLayout.LayoutParams(-1,dp(58)));

        Button save=button("Сохранить в GitHub");
        LinearLayout.LayoutParams saveParams=new LinearLayout.LayoutParams(-1,dp(53));
        saveParams.setMargins(0,dp(8),0,dp(7));
        destination.addView(save,saveParams);
        save.setOnClickListener(v->{
            String url=website.getText().toString().trim();
            String contact=email.getText().toString().trim();
            if(!url.isEmpty()&&!url.matches("(?i)^https?://\\S+$")){
                website.setError("Укажите адрес http:// или https://");website.requestFocus();return;
            }
            if(!contact.isEmpty()&&!android.util.Patterns.EMAIL_ADDRESS.matcher(contact).matches()){
                email.setError("Некорректный email");email.requestFocus();return;
            }
            String twitterName=twitter.getText().toString().trim();
            if(twitterName.startsWith("@"))twitterName=twitterName.substring(1);
            JSONObject draft=new JSONObject();
            try{
                draft.put("name",name.getText().toString().trim());
                draft.put("bio",bio.getText().toString());
                draft.put("company",company.getText().toString().trim());
                draft.put("location",location.getText().toString().trim());
                draft.put("blog",url);
                draft.put("twitter_username",twitterName);
                draft.put("email",contact);
                draft.put("hireable",hireable.isChecked());
                JSONObject patch=ProfileController.editablePatch(original,draft);
                if(patch.length()==0){toast("Изменений нет");return;}
                save.setEnabled(false);
                io.execute(()->{
                    try{
                        JSONObject updated=profileController.updateEditable(patch);
                        runOnUiThread(()->{
                            if(generation!=screenGeneration.get()||destination!=content
                                    ||!account.equals(currentAccountId()))return;
                            cacheGitHubProfile(updated);
                            toast("Профиль GitHub обновлён");
                            navigate(this::showProfile);
                        });
                    }catch(Exception error){
                        runOnUiThread(()->{
                            if(generation!=screenGeneration.get()||destination!=content
                                    ||!account.equals(currentAccountId()))return;
                            save.setEnabled(true);
                            String message=error.getMessage();
                            errorDialog("Не удалось сохранить профиль",
                                    (message==null?"GitHub отклонил запрос.":message)
                                    +"\n\nПроверьте разрешение Profile: Write для GitHub App.");
                        });
                    }
                });
            }catch(Exception error){save.setEnabled(true);errorDialog("Ошибка формы",error.getMessage());}
        });
    }

    private void renderProfileContent(JSONObject profile){
        final String login=profile.optString("login",getProfileLogin());
        String displayName=cleanNullable(profile.optString("name",""));
        if(displayName.isEmpty())displayName=login;
        final String profileUrl=profile.optString("html_url","https://github.com/"+login);

        // Profile hero: the actual cached GitHub avatar and profile fields, never mockup numbers.
        LinearLayout hero=new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(12),dp(10),dp(12),dp(10));
        hero.setBackground(round(blend(CARD,GREEN,0.065f),17,BORDER,1));
        LinearLayout identity=new LinearLayout(this);
        identity.setGravity(Gravity.CENTER_VERTICAL);
        ImageView avatar=createProfileAvatar(dp(78));
        identity.addView(avatar,new LinearLayout.LayoutParams(dp(78),dp(78)));
        LinearLayout labels=new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(10),0,dp(3),0);
        TextView name=text(displayName,20,true);
        name.setPadding(0,0,0,0);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(name);
        TextView handle=text("@"+login,13,true);
        handle.setTextColor(GREEN);
        handle.setPadding(0,dp(2),0,0);
        handle.setSingleLine(true);
        handle.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(handle);
        identity.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        TextView edit=text("✎",20,true);
        edit.setTextColor(GREEN);
        edit.setContentDescription("Редактировать профиль GitHub");
        edit.setGravity(Gravity.CENTER);
        edit.setBackground(round(blend(CARD,GREEN,0.12f),15,0,0));
        edit.setOnClickListener(v->navigate(this::showNativeProfileEditor));
        identity.addView(edit,new LinearLayout.LayoutParams(dp(42),dp(42)));
        hero.addView(identity);

        String location=cleanNullable(profile.optString("location",""));
        String website=cleanNullable(profile.optString("blog",""));
        if(!location.isEmpty()||!website.isEmpty()){
            TextView info=text((location.isEmpty()?"":"⌖ "+location)
                    +(website.isEmpty()?"":(location.isEmpty()?"":"   ·   ")+"↗ "+website),11,false);
            info.setTextColor(MUTED);
            info.setMaxLines(2);
            info.setEllipsize(TextUtils.TruncateAt.END);
            info.setPadding(dp(1),dp(9),0,0);
            info.setOnClickListener(v->openWeb(profileUrl));
            hero.addView(info);
        }
        String bio=cleanNullable(profile.optString("bio",""));
        if(!bio.isEmpty()){
            TextView description=text(bio,12,false);
            description.setTextColor(MUTED);
            description.setPadding(dp(1),dp(8),dp(1),0);
            description.setMaxLines(4);
            description.setEllipsize(TextUtils.TruncateAt.END);
            hero.addView(description);
        }
        LinearLayout.LayoutParams heroP=new LinearLayout.LayoutParams(-1,-2);
        heroP.setMargins(0,0,0,dp(6));
        content.addView(hero,heroP);

        RepositoryStore.Snapshot snapshot=repositoryStore.read(currentAccountId());
        final int repoCount=snapshot.hasData()?snapshot.repositories.length():
                Math.max(0,profile.optInt("public_repos",0));
        GridLayout stats=new GridLayout(this);
        stats.setColumnCount(4);
        profileStat(stats,R.drawable.ic_repository,Integer.toString(repoCount),"Репозитории",GREEN,
                ()->navigate(this::showMyRepositories));
        profileStat(stats,R.drawable.ic_organization,Integer.toString(profile.optInt("followers",0)),
                "Подписчики",0xFF347EE0,
                ()->navigate(()->showPeopleList("Подписчики","https://api.github.com/user/followers")));
        profileStat(stats,R.drawable.ic_profile,Integer.toString(profile.optInt("following",0)),
                "Подписки",0xFF8555CD,
                ()->navigate(()->showPeopleList("Подписки","https://api.github.com/user/following")));
        profileStat(stats,R.drawable.ic_projects,Integer.toString(profile.optInt("public_gists",0)),
                "Gists",0xFFE47B2F,()->openWeb("https://gist.github.com/"+login));
        LinearLayout.LayoutParams statsP=new LinearLayout.LayoutParams(-1,-2);
        statsP.setMargins(0,0,0,dp(3));
        content.addView(stats,statsP);

        profileSection("Быстрые действия","Все действия",()->{
            new AlertDialog.Builder(this).setTitle("Действия с аккаунтом").setItems(
                new String[]{"Мои репозитории","Организации","История","Открыть GitHub",
                        "Настройки","Обновить профиль"},
                (dialog,which)->{
                    switch(which){
                        case 0: navigate(this::showMyRepositories);break;
                        case 1: navigate(this::showOrganizations);break;
                        case 2: navigate(this::showAccountActivity);break;
                        case 3: openWeb(profileUrl);break;
                        case 4: navigate(this::showSettings);break;
                        case 5: refreshProfileData(true);break;
                        default:break;
                    }
                }).show();
        });
        GridLayout quick=new GridLayout(this);
        quick.setColumnCount(2);
        profileAction(quick,R.drawable.ic_repository,"Мои репозитории","Ваши проекты",GREEN,
                ()->navigate(this::showMyRepositories));
        profileAction(quick,R.drawable.ic_organization,"Организации","Команды и доступ",0xFF3098A0,
                ()->navigate(this::showOrganizations));
        profileAction(quick,R.drawable.ic_history,"История","Недавняя активность",0xFFF08430,
                ()->navigate(this::showAccountActivity));
        profileAction(quick,R.drawable.ic_tools,"Открыть GitHub","Профиль в браузере",0xFF8252D3,
                ()->openWeb(profileUrl));
        content.addView(quick,new LinearLayout.LayoutParams(-1,-2));

        profileSection("Закреплённые репозитории","Изменить",()->profileChoosePinned(snapshot));
        Set<String> pins=new LinkedHashSet<>(prefs.getStringSet(
                profilePinsKey(),new LinkedHashSet<>()));
        int shown=0;
        if(snapshot.hasData()){
            for(String full:pins){
                for(int i=0;i<snapshot.repositories.length();i++){
                    JSONObject repo=snapshot.repositories.optJSONObject(i);
                    if(repo!=null&&full.equals(repo.optString("full_name",""))){
                        profilePinnedRepo(repo);
                        shown++;
                        break;
                    }
                }
                if(shown>=3)break;
            }
        }
        if(shown==0){
            TextView empty=text(snapshot.hasData()
                    ?"Закрепите до трёх репозиториев для быстрого доступа из профиля."
                    :"Список появится после синхронизации репозиториев.",12,false);
            empty.setTextColor(MUTED);
            empty.setPadding(dp(12),dp(13),dp(12),dp(13));
            empty.setBackground(round(CARD,14,BORDER,1));
            empty.setOnClickListener(v->profileChoosePinned(snapshot));
            content.addView(empty);
        }
        profileSection("Аккаунт и безопасность",null,null);
        GridLayout security=new GridLayout(this);
        security.setColumnCount(2);
        profileSecurityTile(security,R.drawable.ic_lock,"Сеанс активен",
                "Управление аккаунтом",GREEN,()->navigate(this::showSettingsAccount));
        profileSecurityTile(security,R.drawable.ic_settings,"Токен защищён",
                "Android Keystore",0xFF347EE0,()->navigate(this::showSettingsSecurity));
        content.addView(security,new LinearLayout.LayoutParams(-1,-2));
    }

    private void profileSection(String title,String actionLabel,Runnable onAction){
        LinearLayout row=new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading=text(title,17,true);
        heading.setPadding(0,0,0,0);
        heading.setSingleLine(true);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        if(actionLabel!=null&&onAction!=null){
            TextView action=text(actionLabel+" ›",11,true);
            action.setTextColor(GREEN);
            action.setGravity(Gravity.CENTER_VERTICAL|Gravity.END);
            action.setOnClickListener(v->onAction.run());
            row.addView(action);
        }
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(49));
        p.setMargins(0,dp(5),0,0);
        content.addView(row,p);
    }

    private void profileStat(GridLayout target,int icon,String value,String label,int tint,Runnable action){
        LinearLayout tile=new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(2),dp(8),dp(2),dp(7));
        tile.setBackground(round(CARD,13,BORDER,1));
        ImageView art=imageIcon(icon,tint,2);
        tile.addView(art,new LinearLayout.LayoutParams(dp(22),dp(22)));
        TextView amount=text(value,17,true);
        amount.setGravity(Gravity.CENTER);
        amount.setPadding(0,dp(5),0,0);
        tile.addView(amount);
        TextView caption=text(label,10,false);
        caption.setTextColor(MUTED);
        caption.setSingleLine(true);
        caption.setGravity(Gravity.CENTER);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        caption.setPadding(0,dp(2),0,0);
        tile.addView(caption);
        tile.setOnClickListener(v->action.run());
        GridLayout.LayoutParams p=new GridLayout.LayoutParams();
        p.width=0;
        p.height=dp(87);
        p.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);
        p.setMargins(dp(2),0,dp(2),0);
        target.addView(tile,p);
    }

    private void profileAction(GridLayout grid,int icon,String title,String subtitle,int accent,Runnable action){
        LinearLayout tile=new LinearLayout(this);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPadding(dp(9),dp(10),dp(7),dp(10));
        tile.setBackground(round(CARD,14,BORDER,1));
        ImageView image=imageIcon(icon,Color.WHITE,9);
        image.setBackground(round(accent,11,0,0));
        tile.addView(image,new LinearLayout.LayoutParams(dp(38),dp(38)));
        LinearLayout labels=new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(7),0,0,0);
        TextView heading=text(title,12,true);
        heading.setPadding(0,0,0,0);
        heading.setMaxLines(2);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(heading);
        TextView sub=text(subtitle,10,false);
        sub.setPadding(0,dp(2),0,0);
        sub.setTextColor(MUTED);
        sub.setMaxLines(2);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(sub);
        tile.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        tile.setOnClickListener(v->action.run());
        GridLayout.LayoutParams p=new GridLayout.LayoutParams();
        p.width=0;
        p.height=dp(80);
        p.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);
        p.setMargins(dp(2),dp(2),dp(2),dp(2));
        grid.addView(tile,p);
    }

    private String profilePinsKey(){
        return "profile_pins_"+currentAccountId();
    }

    private void profileChoosePinned(RepositoryStore.Snapshot snapshot){
        if(!snapshot.hasData()||snapshot.repositories.length()==0){
            new AlertDialog.Builder(this).setTitle("Закреплённые репозитории")
                    .setMessage("Сначала синхронизируйте репозитории GitHub.")
                    .setPositiveButton("Открыть репозитории",(d,w)->navigate(this::showMyRepositories))
                    .setNegativeButton("Закрыть",null).show();
            return;
        }
        List<String> values=new ArrayList<>();
        for(int i=0;i<snapshot.repositories.length();i++){
            JSONObject repo=snapshot.repositories.optJSONObject(i);
            if(repo==null)continue;
            String full=repo.optString("full_name","");
            if(!full.isEmpty()&&!values.contains(full))values.add(full);
        }
        String[] names=values.toArray(new String[0]);
        Set<String> chosen=new LinkedHashSet<>(prefs.getStringSet(
                profilePinsKey(),new LinkedHashSet<>()));
        boolean[] checked=new boolean[names.length];
        for(int i=0;i<names.length;i++)checked[i]=chosen.contains(names[i]);
        AlertDialog dialog=new AlertDialog.Builder(this)
                .setTitle("Закрепить до трёх репозиториев")
                .setMultiChoiceItems(names,checked,(d,index,on)->{
                    if(on&&chosen.size()>=3&&!chosen.contains(names[index])){
                        ((AlertDialog)d).getListView().setItemChecked(index,false);
                        toast("Можно закрепить не более трёх репозиториев");
                    }else if(on)chosen.add(names[index]);
                    else chosen.remove(names[index]);
                })
                .setPositiveButton("Сохранить",(d,w)->{
                    prefs.edit().putStringSet(profilePinsKey(),new LinkedHashSet<>(chosen)).apply();
                    renderProfilePage(false);
                })
                .setNegativeButton("Отмена",null).create();
        dialog.show();
    }

    private void profilePinnedRepo(JSONObject repo){
        final String full=repo.optString("full_name","");
        if(full.isEmpty())return;
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(11),dp(10),dp(9),dp(9));
        box.setBackground(round(CARD,14,BORDER,1));
        LinearLayout first=new LinearLayout(this);
        first.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon=imageIcon(R.drawable.ic_repository,Color.WHITE,10);
        icon.setBackground(round(GREEN,11,0,0));
        first.addView(icon,new LinearLayout.LayoutParams(dp(42),dp(42)));
        LinearLayout words=new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(9),0,dp(5),0);
        TextView title=text(repo.optString("name",full),14,true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0,0,0,0);
        words.addView(title);
        TextView description=text(cleanNullable(repo.optString("description","")),11,false);
        description.setMaxLines(2);
        description.setTextColor(MUTED);
        description.setEllipsize(TextUtils.TruncateAt.END);
        description.setPadding(0,dp(2),0,0);
        words.addView(description);
        first.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        TextView pin=text("●",12,true);
        pin.setTextColor(GREEN);
        pin.setContentDescription("Закреплённый репозиторий");
        pin.setGravity(Gravity.CENTER);
        first.addView(pin,new LinearLayout.LayoutParams(dp(20),dp(35)));
        TextView menu=text("⋮",21,true);
        menu.setTextColor(MUTED);
        menu.setGravity(Gravity.CENTER);
        menu.setContentDescription("Действия с репозиторием "+full);
        menu.setOnClickListener(v->new AlertDialog.Builder(this).setTitle(full)
                .setItems(new String[]{"Открыть","Открепить"},(dialog,index)->{
                    if(index==0)navigate(()->showRepository(full));
                    else{
                        Set<String> pins=new LinkedHashSet<>(prefs.getStringSet(
                                profilePinsKey(),new LinkedHashSet<>()));
                        pins.remove(full);
                        prefs.edit().putStringSet(profilePinsKey(),pins).apply();
                        renderProfilePage(false);
                    }
                }).show());
        first.addView(menu,new LinearLayout.LayoutParams(dp(25),dp(40)));
        box.addView(first);
        TextView meta=text(cleanNullable(repo.optString("language","—"))+
                "    ★ "+repo.optInt("stargazers_count",0)+
                "    ⑂ "+repo.optInt("forks_count",0)+
                "    · "+formatGitHubDate(repo.optString("updated_at","")),10,false);
        meta.setTextColor(MUTED);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        meta.setPadding(dp(1),dp(9),0,0);
        box.addView(meta);
        box.setOnClickListener(v->navigate(()->showRepository(full)));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,0,0,dp(8));
        content.addView(box,p);
    }

    private void profileSecurityTile(GridLayout grid,int icon,String title,String subtitle,
                                     int accent,Runnable action){
        LinearLayout row=new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8),dp(9),dp(6),dp(9));
        row.setBackground(round(blend(CARD,accent,0.04f),13,BORDER,1));
        ImageView art=imageIcon(icon,accent,4);
        art.setBackground(round(blend(CARD,accent,0.11f),9,0,0));
        row.addView(art,new LinearLayout.LayoutParams(dp(33),dp(33)));
        LinearLayout labels=new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(6),0,0,0);
        TextView name=text(title,11,true);
        name.setPadding(0,0,0,0);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(name);
        TextView detail=text(subtitle,9,false);
        detail.setPadding(0,dp(2),0,0);
        detail.setTextColor(MUTED);
        detail.setMaxLines(2);
        detail.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(detail);
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        row.setOnClickListener(v->action.run());
        GridLayout.LayoutParams p=new GridLayout.LayoutParams();
        p.width=0;
        p.height=dp(69);
        p.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);
        p.setMargins(dp(2),dp(2),dp(2),dp(2));
        grid.addView(row,p);
    }

    private void addProfileStat(GridLayout grid,String value,String label,Runnable action){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setGravity(Gravity.CENTER);box.setBackground(round(CARD,15,BORDER,1));
        TextView number=text(value,18,true);number.setTextColor(GREEN);number.setGravity(Gravity.CENTER);number.setPadding(0,0,0,0);box.addView(number);
        TextView caption=text(label,9,false);caption.setTextColor(MUTED);caption.setGravity(Gravity.CENTER);caption.setPadding(0,0,0,0);box.addView(caption);box.setOnClickListener(v->navigate(action));
        GridLayout.LayoutParams p=new GridLayout.LayoutParams();p.width=0;p.height=dp(72);p.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);p.setMargins(dp(3),dp(3),dp(3),dp(3));grid.addView(box,p);
    }

    private void addProfileDetail(LinearLayout parent,String label,String value){if(value==null||value.trim().isEmpty())return;LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);TextView l=text(label,13,true);l.setTextColor(MUTED);row.addView(l,new LinearLayout.LayoutParams(dp(116),-2));TextView v=text(value,14,false);v.setTextIsSelectable(true);row.addView(v,new LinearLayout.LayoutParams(0,-2,1));parent.addView(row);}

    private void refreshProfileData(boolean userInitiated){
        if(!isSignedIn()||!profileRefreshInFlight.compareAndSet(false,true))return;
        io.execute(() -> {try{
            JSONObject user=profileController.current();
            cacheGitHubProfile(user);
            downloadAvatar(user.optString("avatar_url",""));
            try{
                RepositoryAccessController.SyncResult result=repositoryAccess.sync(false);
                repositoryStore.saveSuccess(currentAccountId(),result.repositories);
            }catch(Exception ignored){
                // The profile remains useful when repository access is temporarily unavailable.
            }
            runOnUiThread(() -> {profileRefreshInFlight.set(false);if(userInitiated)toast("Профиль и репозитории обновлены");if("Профиль".equals(currentTitle))renderProfilePage(false);else if(currentScreen!=null&&!"Редактирование профиля".equals(currentTitle))currentScreen.run();});
        }catch(Exception e){runOnUiThread(() -> {profileRefreshInFlight.set(false);if(userInitiated)toast("Не удалось обновить профиль: "+e.getMessage());});}});
    }

    private void cacheGitHubProfile(JSONObject user){prefs.edit().putString("github_profile_json",user.toString()).putString("github_avatar_url",user.optString("avatar_url","")).apply();secureStore.put("github_login",user.optString("login",""));secureStore.put("github_name",cleanNullable(user.optString("name","")));}
    private JSONObject getCachedProfile(){try{return new JSONObject(prefs.getString("github_profile_json","{}"));}catch(Exception e){return new JSONObject();}}
    private String getProfileLogin(){JSONObject p=getCachedProfile();String login=p.optString("login","");if(login.isEmpty())login=secureStore.get("github_login");return login;}
    private String cleanNullable(String value){return value==null||"null".equalsIgnoreCase(value.trim())?"":value.trim();}

    private ImageView createProfileAvatar(int sizePx){
        ImageView avatar=new ImageView(this);avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);avatar.setBackground(round(SUBTLE,50,BORDER,1));avatar.setClipToOutline(true);
        Bitmap bitmap=readCachedAvatar();if(bitmap!=null)avatar.setImageBitmap(bitmap);else{avatar.setImageResource(R.drawable.ic_profile);avatar.setColorFilter(GREEN);avatar.setPadding(sizePx/4,sizePx/4,sizePx/4,sizePx/4);}return avatar;
    }

    private ImageView createTopAccountIcon(){
        ImageView account=new ImageView(this);account.setScaleType(ImageView.ScaleType.CENTER_CROP);account.setBackground(round(SUBTLE,22,BORDER,1));account.setClipToOutline(true);
        Bitmap bitmap=readCachedAvatar();if(bitmap!=null)account.setImageBitmap(bitmap);else{account.setImageResource(R.drawable.ic_profile);account.setColorFilter(GREEN);account.setPadding(dp(10),dp(10),dp(10),dp(10));}return account;
    }

    private Bitmap readCachedAvatar(){try{File file=new File(getFilesDir(),"github_avatar.png");return file.isFile()?BitmapFactory.decodeFile(file.getAbsolutePath()):null;}catch(Exception e){return null;}}
    private void downloadAvatar(String url){if(url==null||url.isEmpty())return;try{URLConnection connection=new URL(url).openConnection();connection.setConnectTimeout(12000);connection.setReadTimeout(20000);connection.setRequestProperty("User-Agent","GitHubble-Android/0.25");try(InputStream in=connection.getInputStream();FileOutputStream out=new FileOutputStream(new File(getFilesDir(),"github_avatar.png"))){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}}catch(Exception ignored){}}

    private void showTracking(){ rememberScreen("tracking",null); showTrackingFiltered("ALL"); }

    private void showTrackingFiltered(String filter){
        setCurrentScreen(() -> showTrackingFiltered(filter));
        shell(getString(R.string.nav_activity), false);
        heroBanner("Наблюдение", "Новые репозитории, релизы и изменения разработчиков", R.drawable.ic_bell);

        JSONObject stats = trackingDb.statistics();
        GridLayout summary = new GridLayout(this); summary.setColumnCount(adaptiveColumns(4));
        addMiniStat(summary, String.valueOf(stats.optInt("developers",0)), "Разработчиков", GREEN);
        addMiniStat(summary, String.valueOf(stats.optInt("repositories",0)), "Репозиториев", tileColors[4]);
        addMiniStat(summary, compactNumber(stats.optLong("stars",0)), "Звёзд", tileColors[5]);
        addMiniStat(summary, String.valueOf(stats.optInt("unread",0)), "Новых", tileColors[0]);
        content.addView(summary);

        GridLayout actions = new GridLayout(this); actions.setColumnCount(adaptiveColumns(3));
        addAction(actions, "Добавить", () -> showAddDeveloperDialog(false));
        addAction(actions, trackingCheckInFlight.get()?"Проверка…":"Проверить", () -> runTrackingCheck(true));
        addAction(actions, "Настройки", () -> navigate(this::showTrackingSettings));
        content.addView(actions);

        List<TrackingDatabase.Developer> developers = trackingDb.getDevelopers(true);
        sectionTitle("Отслеживаемые разработчики", null);
        if(developers.isEmpty()){
            LinearLayout empty = new LinearLayout(this); empty.setOrientation(LinearLayout.VERTICAL);
            empty.addView(text("Пока никто не отслеживается",16,true));
            TextView note = text("Добавьте GitHub login. Первая синхронизация создаст базовый снимок без ложных уведомлений, а следующие проверки покажут только реальные изменения.",13,false);
            note.setTextColor(MUTED); empty.addView(note); card(empty);
        }else{
            for(TrackingDatabase.Developer developer:developers) renderTrackedDeveloperCard(developer);
        }

        sectionTitle("Лента нового", null);
        GridLayout filters = new GridLayout(this); filters.setColumnCount(adaptiveColumns(3));
        addTrackingFilter(filters,"Всё","ALL",filter);
        addTrackingFilter(filters,"Репозитории",TrackingEngine.TYPE_NEW_REPOSITORY,filter);
        addTrackingFilter(filters,"Релизы",TrackingEngine.TYPE_RELEASE,filter);
        addTrackingFilter(filters,"Обновления",TrackingEngine.TYPE_PUSH,filter);
        addTrackingFilter(filters,"Популярность",TrackingEngine.TYPE_POPULARITY,filter);
        addTrackingFilter(filters,"Статусы",TrackingEngine.TYPE_STATUS,filter);
        content.addView(filters);

        List<TrackingDatabase.Event> events = trackingDb.getEvents(filter, 100);
        if(events.isEmpty()){
            TextView noEvents = text(developers.isEmpty()?"Добавьте разработчика и выполните первую синхронизацию.":"Новых изменений пока не обнаружено.",13,false);
            noEvents.setTextColor(MUTED); card(noEvents);
        }else{
            LinearLayout readActions = new LinearLayout(this);
            Button markRead = button("Прочитать всё"); markRead.setOnClickListener(v->{trackingDb.markAllRead();showTrackingFiltered(filter);});
            Button clear = button("Очистить историю"); clear.setOnClickListener(v->confirmClearTrackingHistory(filter));
            readActions.addView(markRead,new LinearLayout.LayoutParams(0,dp(46),1));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0,dp(46),1);cp.setMargins(dp(7),0,0,0);readActions.addView(clear,cp);
            content.addView(readActions);
            for(TrackingDatabase.Event event:events) renderTrackingEventCard(event);
        }
    }

    private void addTrackingFilter(GridLayout grid,String label,String value,String selected){
        TextView chip=text(label,12,true);chip.setGravity(Gravity.CENTER);chip.setTextColor(value.equals(selected)?Color.WHITE:GREEN);
        chip.setBackground(round(value.equals(selected)?GREEN:CARD,13,value.equals(selected)?GREEN:BORDER,1));
        chip.setOnClickListener(v->showTrackingFiltered(value));
        GridLayout.LayoutParams p=new GridLayout.LayoutParams();p.width=0;p.height=dp(42);p.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);p.setMargins(dp(3),dp(3),dp(3),dp(3));grid.addView(chip,p);
    }

    private void renderTrackedDeveloperCard(TrackingDatabase.Developer developer){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        TextView avatar=text(developer.login.isEmpty()?"?":developer.login.substring(0,1).toUpperCase(Locale.ROOT),18,true);
        avatar.setTextColor(Color.WHITE);avatar.setGravity(Gravity.CENTER);avatar.setBackground(round(GREEN,18,GREEN,0));
        row.addView(avatar,new LinearLayout.LayoutParams(dp(48),dp(48)));
        LinearLayout info=new LinearLayout(this);info.setOrientation(LinearLayout.VERTICAL);info.setPadding(dp(12),0,dp(6),0);
        String display=developer.displayName.isEmpty()?"@"+developer.login:developer.displayName;
        info.addView(text(display,15,true));
        TextView handle=text("@"+developer.login+" · "+developer.repositoryCount+" репо · ★ "+compactNumber(developer.totalStars),11,false);handle.setTextColor(MUTED);info.addView(handle);
        String state=developer.lastCheckedAt<=0?"Ещё не проверялся":"Проверено "+formatEpoch(developer.lastCheckedAt);
        if(!developer.lastError.isEmpty())state="Ошибка: "+developer.lastError;
        TextView checked=text(state,10,false);checked.setTextColor(developer.lastError.isEmpty()?MUTED:Color.rgb(190,55,60));info.addView(checked);
        row.addView(info,new LinearLayout.LayoutParams(0,-2,1));
        TextView arrow=text("›",28,false);arrow.setTextColor(MUTED);arrow.setGravity(Gravity.CENTER);row.addView(arrow,new LinearLayout.LayoutParams(dp(28),dp(48)));
        row.setOnClickListener(v->navigate(() -> showDeveloperProfile(developer.login)));
        card(row);
    }

    private void renderTrackingEventCard(TrackingDatabase.Event event){
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon=text(trackingEventSymbol(event.eventType),18,true);icon.setTextColor(trackingEventColor(event.eventType));icon.setGravity(Gravity.CENTER);
        icon.setBackground(round(blend(SUBTLE,trackingEventColor(event.eventType),0.10f),14,Color.TRANSPARENT,0));
        head.addView(icon,new LinearLayout.LayoutParams(dp(42),dp(42)));
        LinearLayout titles=new LinearLayout(this);titles.setOrientation(LinearLayout.VERTICAL);titles.setPadding(dp(10),0,0,0);
        TextView title=text(event.title,15,true);title.setMaxLines(2);titles.addView(title);
        TextView meta=text("@"+event.developerLogin+" · "+trackingEventLabel(event.eventType)+" · "+formatEpoch(event.createdAt),10,false);meta.setTextColor(MUTED);titles.addView(meta);
        head.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        if(!event.read){TextView badge=text("НОВОЕ",9,true);badge.setTextColor(Color.WHITE);badge.setGravity(Gravity.CENTER);badge.setBackground(round(Color.rgb(226,83,94),10,Color.TRANSPARENT,0));head.addView(badge,new LinearLayout.LayoutParams(dp(56),dp(24)));}
        row.addView(head);
        if(!event.summary.isEmpty()){TextView summary=text(event.summary,12,false);summary.setTextColor(MUTED);summary.setPadding(dp(52),dp(4),0,0);row.addView(summary);}
        row.setOnClickListener(v->{String full=trackingEventRepository(event);if(!full.isEmpty())navigate(() -> showRepository(full));else if(!event.htmlUrl.isEmpty())openWeb(event.htmlUrl);});
        card(row);
    }

    private String trackingEventRepository(TrackingDatabase.Event event){
        try{return new JSONObject(event.payload).optString("full_name","");}catch(Exception e){return "";}
    }

    private String trackingEventLabel(String type){
        if(TrackingEngine.TYPE_NEW_REPOSITORY.equals(type))return "Новый репозиторий";
        if(TrackingEngine.TYPE_RELEASE.equals(type))return "Релиз";
        if(TrackingEngine.TYPE_PUSH.equals(type))return "Обновление";
        if(TrackingEngine.TYPE_POPULARITY.equals(type))return "Рост";
        if(TrackingEngine.TYPE_STATUS.equals(type))return "Статус";
        return "Событие";
    }

    private String trackingEventSymbol(String type){
        if(TrackingEngine.TYPE_NEW_REPOSITORY.equals(type))return "+";
        if(TrackingEngine.TYPE_RELEASE.equals(type))return "↓";
        if(TrackingEngine.TYPE_PUSH.equals(type))return "↻";
        if(TrackingEngine.TYPE_POPULARITY.equals(type))return "★";
        if(TrackingEngine.TYPE_STATUS.equals(type))return "◆";
        return "•";
    }

    private int trackingEventColor(String type){
        if(TrackingEngine.TYPE_NEW_REPOSITORY.equals(type))return GREEN;
        if(TrackingEngine.TYPE_RELEASE.equals(type))return tileColors[1];
        if(TrackingEngine.TYPE_PUSH.equals(type))return tileColors[6];
        if(TrackingEngine.TYPE_POPULARITY.equals(type))return tileColors[5];
        if(TrackingEngine.TYPE_STATUS.equals(type))return tileColors[2];
        return MUTED;
    }

    private void showAddDeveloperDialog(boolean openProfileAfter){
        EditText input=new EditText(this);input.setSingleLine(true);input.setHint("GitHub login, например torvalds");input.setTextColor(TEXT);input.setHintTextColor(MUTED);
        new AlertDialog.Builder(this).setTitle("Добавить разработчика").setMessage("GitHubble сохранит базовый снимок публичных репозиториев, затем будет сравнивать новые проверки с ним.").setView(input)
                .setPositiveButton("Добавить",(d,w)->{String login=input.getText().toString().trim();if(login.isEmpty())return;addDeveloperToTracking(login,openProfileAfter);})
                .setNegativeButton("Отмена",null).show();
    }

    private void addDeveloperToTracking(String login,boolean openProfileAfter){
        final String normalizedLogin=normalizeDeveloperLogin(login);
        if(normalizedLogin.isEmpty()){toast("Введите GitHub login");return;}
        if(trackingDb.isTracked(normalizedLogin)){toast("Этот разработчик уже отслеживается");if(openProfileAfter)navigate(() -> showDeveloperProfile(normalizedLogin));else showTracking();return;}
        if(!trackingCheckInFlight.compareAndSet(false,true)){toast("Проверка уже выполняется");return;}
        requestTrackingNotificationPermission();
        ProgressDialog progress=ProgressDialog.show(this,"Наблюдение","Создаём базовый снимок…",true,false);
        io.execute(() -> {try{
            trackingEngine.addDeveloper(normalizedLogin);
            runOnUiThread(() -> {trackingCheckInFlight.set(false);progress.dismiss();toast("Разработчик добавлен. Базовый снимок создан без старых уведомлений.");if(openProfileAfter)navigate(() -> showDeveloperProfile(normalizedLogin));else showTracking();});
        }catch(Exception e){runOnUiThread(() -> {trackingCheckInFlight.set(false);progress.dismiss();new AlertDialog.Builder(this).setTitle("Не удалось добавить").setMessage(e.getMessage()).setPositiveButton("ОК",null).show();});}});
    }

    private void toggleDeveloperTracking(String login){
        if(trackingDb.isTracked(login)){
            new AlertDialog.Builder(this).setTitle("Перестать следить за @"+login+"?").setMessage("Локальная история и снимки этого разработчика будут удалены.")
                    .setPositiveButton("Удалить",(d,w)->{trackingDb.removeDeveloper(login);if(trackingDb.getDevelopers(true).isEmpty())TrackingJobService.cancel(this);toast("Наблюдение отключено");if(currentScreen!=null)currentScreen.run();})
                    .setNegativeButton("Отмена",null).show();
        }else addDeveloperToTracking(login,false);
    }

    private void runTrackingCheck(boolean userInitiated){
        if(trackingDb.getDevelopers(true).isEmpty()){toast("Сначала добавьте разработчика");return;}
        if(!trackingCheckInFlight.compareAndSet(false,true)){toast("Проверка уже выполняется");return;}
        ProgressDialog progress=userInitiated?ProgressDialog.show(this,"Наблюдение","Проверяем новые репозитории и релизы…",true,false):null;
        io.execute(() -> {TrackingEngine.CheckResult result=trackingEngine.checkAll();runOnUiThread(() -> {
            trackingCheckInFlight.set(false);if(progress!=null)progress.dismiss();TrackingNotificationHelper.notifyEvents(this,result.newEvents);
            String message=result.newEvents.isEmpty()?"Новых изменений нет":"Найдено новых событий: "+result.newEvents.size();
            if(result.failedDevelopers>0)message+=" · ошибок: "+result.failedDevelopers;toast(message);showTracking();
        });});
    }

    private void showDeveloperProfile(String rawLogin){
        rememberScreen("developer",routeArgs("login",normalizeDeveloperLogin(rawLogin)));
        String login=normalizeDeveloperLogin(rawLogin);
        if(login.isEmpty()){toast("Не указан GitHub login");return;}
        setCurrentScreen(() -> showDeveloperProfile(login));shell("Разработчик",true);ProgressBar pb=new ProgressBar(this);content.addView(pb);
        io.execute(() -> {try{
            JSONObject user=profileController.publicProfile(URLEncoder.encode(login,"UTF-8"));
            JSONArray repos=getArray("https://api.github.com/users/"+URLEncoder.encode(login,"UTF-8")+"/repos?type=owner&sort=pushed&direction=desc&per_page=30");
            Bitmap avatarBitmap=downloadBitmap(user.optString("avatar_url",""));
            Boolean githubFollowing=isSignedIn()?profileController.isFollowing(login):null;
            runOnUiThread(() -> renderDeveloperProfile(user,repos,avatarBitmap,pb,githubFollowing));
        }catch(Exception e){runOnUiThread(() -> {content.removeView(pb);TrackingDatabase.Developer cached=trackingDb.getDeveloper(login);if(cached!=null)renderCachedDeveloperProfile(cached);else errorCard("Не удалось загрузить профиль",e.getMessage());});}});
    }

    private void renderDeveloperProfile(JSONObject user,JSONArray repos,Bitmap avatarBitmap,ProgressBar pb,Boolean githubFollowing){
        content.removeView(pb);
        String login=user.optString("login","");String name=cleanNullable(user.optString("name",""));if(name.isEmpty())name=login;
        LinearLayout hero=new LinearLayout(this);hero.setOrientation(LinearLayout.VERTICAL);hero.setGravity(Gravity.CENTER);
        ImageView avatar=new ImageView(this);avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);avatar.setBackground(round(SUBTLE,48,BORDER,1));avatar.setClipToOutline(true);
        if(avatarBitmap!=null)avatar.setImageBitmap(avatarBitmap);else{avatar.setImageResource(R.drawable.ic_profile);avatar.setColorFilter(GREEN);avatar.setPadding(dp(18),dp(18),dp(18),dp(18));}
        hero.addView(avatar,new LinearLayout.LayoutParams(dp(96),dp(96)));
        TextView title=text(name,23,true);title.setGravity(Gravity.CENTER);hero.addView(title);
        TextView handle=text("@"+login,14,true);handle.setTextColor(GREEN);handle.setGravity(Gravity.CENTER);hero.addView(handle);
        String bio=cleanNullable(user.optString("bio",""));if(!bio.isEmpty()){TextView b=text(bio,13,false);b.setTextColor(MUTED);b.setGravity(Gravity.CENTER);hero.addView(b);}card(hero);

        GridLayout stats=new GridLayout(this);stats.setColumnCount(adaptiveColumns(4));
        addMiniStat(stats,String.valueOf(user.optInt("public_repos",0)),"Репо",GREEN);
        addMiniStat(stats,String.valueOf(user.optInt("followers",0)),"Подписчики",tileColors[1]);
        addMiniStat(stats,String.valueOf(user.optInt("following",0)),"Подписки",tileColors[2]);
        long stars=0;for(int i=0;i<repos.length();i++){JSONObject r=repos.optJSONObject(i);if(r!=null)stars+=r.optInt("stargazers_count",0);}addMiniStat(stats,compactNumber(stars),"Звёзды",tileColors[5]);
        content.addView(stats);

        GridLayout tracking=new GridLayout(this);tracking.setColumnCount(adaptiveColumns(2));
        addAction(tracking,trackingDb.isTracked(login)?"Не отслеживать локально":"Отслеживать новинки",()->toggleDeveloperTracking(login));
        if(isSignedIn()&&githubFollowing!=null)addAction(tracking,githubFollowing?"Отписаться в GitHub":"Подписаться в GitHub",()->toggleGitHubFollow(login,githubFollowing));
        else if(isSignedIn())addAction(tracking,"Подписка: статус неизвестен",()->toast("Проверьте сеть и разрешения GitHub"));
        addAction(tracking,"Открыть GitHub",()->openWeb(user.optString("html_url","https://github.com/"+login)));
        content.addView(tracking);

        TrackingDatabase.Developer tracked=trackingDb.getDeveloper(login);
        if(tracked!=null){TextView state=text("Наблюдение активно · последняя проверка: "+(tracked.lastCheckedAt<=0?"ещё не выполнена":formatEpoch(tracked.lastCheckedAt)),11,false);state.setTextColor(GREEN);card(state);}

        sectionTitle("Последние репозитории",null);
        if(repos.length()==0)card(text("Публичных репозиториев не найдено.",13,false));
        for(int i=0;i<Math.min(20,repos.length());i++){JSONObject repo=repos.optJSONObject(i);if(repo!=null)try{addRepoCard(repo);}catch(Exception ignored){}}
    }

    private void renderCachedDeveloperProfile(TrackingDatabase.Developer developer){
        LinearLayout hero=new LinearLayout(this);hero.setOrientation(LinearLayout.VERTICAL);hero.setGravity(Gravity.CENTER);
        hero.addView(text(developer.displayName.isEmpty()?"@"+developer.login:developer.displayName,22,true));TextView handle=text("@"+developer.login,14,true);handle.setTextColor(GREEN);hero.addView(handle);card(hero);
        TextView offline=text("Офлайн-данные наблюдения · последняя проверка "+formatEpoch(developer.lastCheckedAt),12,false);offline.setTextColor(MUTED);card(offline);
        List<TrackingDatabase.RepoSnapshot> repos=trackingDb.getRepositories(developer.login,30);for(TrackingDatabase.RepoSnapshot repo:repos){LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);row.addView(text(repo.fullName,15,true));TextView meta=text("★ "+repo.stars+" · Forks "+repo.forks+" · "+formatGitHubDate(repo.pushedAt),11,false);meta.setTextColor(MUTED);row.addView(meta);row.setOnClickListener(v->navigate(() -> showRepository(repo.fullName)));card(row);}
    }

    private Bitmap downloadBitmap(String url){
        if(url==null||url.isEmpty())return null;try{URLConnection c=new URL(url).openConnection();c.setConnectTimeout(10000);c.setReadTimeout(15000);try(InputStream in=c.getInputStream()){return BitmapFactory.decodeStream(in);}}catch(Exception e){return null;}
    }

    private void showTrackingSettings(){
        setCurrentScreen(this::showTrackingSettings);shell("Настройки наблюдения",false);
        settingsPanelHeader("Настройки наблюдения","Интервал, события и ограничения GitHub API",R.drawable.ic_bell);
        sectionTitle("Фоновая проверка",null);
        settingsActionRow("Интервал",prefs.getInt("tracking_interval_hours",6)+" ч",this::showTrackingIntervalChooser);
        settingsRow("Последняя проверка",formatEpoch(prefs.getLong("tracking_last_global_check",0L)));
        settingsActionRow("Проверить сейчас",trackingCheckInFlight.get()?"Выполняется":"Запустить",() -> runTrackingCheck(true));
        sectionTitle("События",null);
        trackingToggleRow("Новые репозитории","tracking_notify_new_repositories",true);
        trackingToggleRow("Новые релизы","tracking_notify_releases",true);
        trackingToggleRow("Каждый push","tracking_notify_pushes",false);
        trackingToggleRow("Рост популярности","tracking_notify_popularity",true);
        trackingToggleRow("Изменение статуса","tracking_notify_status",true);
        sectionTitle("Порог популярности",null);
        settingsActionRow("Рост звёзд","от "+prefs.getInt("tracking_star_threshold",10),() -> showTrackingThresholdDialog("tracking_star_threshold","Порог роста звёзд",10));
        settingsActionRow("Рост forks","от "+prefs.getInt("tracking_fork_threshold",3),() -> showTrackingThresholdDialog("tracking_fork_threshold","Порог роста forks",3));
        sectionTitle("Ограничение API",null);
        settingsRow("Репозиториев за цикл","до "+(prefs.getInt("tracking_max_pages",5)*100));
        settingsRow("Проверка релизов","последние "+prefs.getInt("tracking_release_check_limit",8)+" репо");
        settingsRow("Остаток GitHub API",api.getRemaining()<0?"Не определён":String.valueOf(api.getRemaining()));
    }

    private void trackingToggleRow(String title,String key,boolean defaultValue){
        settingsActionRow(title,prefs.getBoolean(key,defaultValue)?"Включено":"Выключено",() -> {boolean next=!prefs.getBoolean(key,defaultValue);prefs.edit().putBoolean(key,next).apply();showTrackingSettings();});
    }

    private void showTrackingIntervalChooser(){
        String[] labels={"1 час","3 часа","6 часов","12 часов","24 часа"};int[] values={1,3,6,12,24};int current=prefs.getInt("tracking_interval_hours",6),checked=2;for(int i=0;i<values.length;i++)if(values[i]==current)checked=i;
        new AlertDialog.Builder(this).setTitle("Интервал фоновой проверки").setSingleChoiceItems(labels,checked,(d,which)->{prefs.edit().putInt("tracking_interval_hours",values[which]).apply();TrackingJobService.schedule(this,values[which]);d.dismiss();showTrackingSettings();}).setNegativeButton("Отмена",null).show();
    }

    private void showTrackingThresholdDialog(String key,String title,int defaultValue){
        EditText input=new EditText(this);input.setInputType(InputType.TYPE_CLASS_NUMBER);input.setSingleLine(true);input.setText(String.valueOf(prefs.getInt(key,defaultValue)));input.setTextColor(TEXT);
        new AlertDialog.Builder(this).setTitle(title).setView(input).setPositiveButton("Сохранить",(d,w)->{try{int value=Math.max(1,Math.min(100000,Integer.parseInt(input.getText().toString().trim())));prefs.edit().putInt(key,value).apply();}catch(Exception ignored){}showTrackingSettings();}).setNegativeButton("Отмена",null).show();
    }

    private void confirmClearTrackingHistory(String filter){
        new AlertDialog.Builder(this).setTitle("Очистить историю?").setMessage("Снимки репозиториев и список разработчиков останутся. Будут удалены только события ленты.").setPositiveButton("Очистить",(d,w)->{trackingDb.clearEvents();showTrackingFiltered(filter);}).setNegativeButton("Отмена",null).show();
    }

    private void requestTrackingNotificationPermission(){
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},210);
    }

    private String normalizeDeveloperLogin(String value){
        if(value==null)return "";String login=value.trim();
        if(login.startsWith("@"))login=login.substring(1);
        int marker=login.indexOf("github.com/");if(marker>=0)login=login.substring(marker+"github.com/".length());
        int slash=login.indexOf('/');if(slash>=0)login=login.substring(0,slash);
        return login.replaceAll("[^A-Za-z0-9-]","");
    }

    private String compactNumber(long value){
        if(value>=1_000_000)return String.format(Locale.ROOT,"%.1fM",value/1_000_000.0);
        if(value>=1_000)return String.format(Locale.ROOT,"%.1fK",value/1_000.0);
        return String.valueOf(value);
    }

    private String formatEpoch(long epoch){
        if(epoch<=0)return "ещё не выполнялась";try{java.time.format.DateTimeFormatter f=java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm",new Locale("ru")).withZone(java.time.ZoneId.systemDefault());return f.format(java.time.Instant.ofEpochMilli(epoch));}catch(Exception e){return String.valueOf(epoch);}
    }

    private void showTrackingDiagnostics(){
        setCurrentScreen(this::showTrackingDiagnostics);shell("Диагностика фона",false);heroBanner("Фоновые проверки","JobScheduler, ограничения батареи и журнал выполнения",R.drawable.ic_tools);
        JSONObject state=TrackingDiagnostics.inspect(this);LinearLayout info=new LinearLayout(this);info.setOrientation(LinearLayout.VERTICAL);
        addProfileDetail(info,"JobScheduler",state.optBoolean("scheduled",false)?"Задание запланировано":"Задание не найдено");
        addProfileDetail(info,"Оптимизация батареи",state.optBoolean("battery_optimization_ignored",false)?"Исключение включено":"Может ограничивать фон");
        addProfileDetail(info,"Устройство",state.optString("manufacturer",Build.MANUFACTURER));
        addProfileDetail(info,"Последний запуск",formatEpoch(prefs.getLong("tracking_job_last_started",0L)));
        addProfileDetail(info,"Последний успех",formatEpoch(prefs.getLong("tracking_job_last_success",0L)));
        addProfileDetail(info,"Результат",prefs.getString("tracking_job_last_result","Нет данных"));
        String error=prefs.getString("tracking_job_last_error","");if(!error.isEmpty())addProfileDetail(info,"Ошибка",error);card(info);
        if(state.optBoolean("is_miui_family",false)){TextView miui=text("На MIUI/HyperOS дополнительно разрешите автозапуск и фоновую активность для GitHubble. Системные названия пунктов зависят от версии прошивки.",12,false);miui.setTextColor(MUTED);card(miui);}
        Button test=button("Запустить проверку сейчас");test.setOnClickListener(v->{if(trackingCheckInFlight.get()){toast("Проверка уже выполняется");return;}trackingCheckInFlight.set(true);ProgressDialog dialog=ProgressDialog.show(this,"Наблюдение","Проверяем фоновые условия и репозитории…",true,false);io.execute(()->{try{TrackingEngine.CheckResult result=trackingEngine.checkAll();TrackingNotificationHelper.notifyEvents(this,result.newEvents);prefs.edit().putLong("tracking_job_last_success",System.currentTimeMillis()).putString("tracking_job_last_result","Ручной тест: разработчиков "+result.checkedDevelopers+", репозиториев "+result.checkedRepositories+", новых событий "+result.newEvents.size()).apply();runOnUiThread(()->{dialog.dismiss();trackingCheckInFlight.set(false);toast("Диагностика завершена");showTrackingDiagnostics();});}catch(Exception e){prefs.edit().putString("tracking_job_last_error",e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()).apply();runOnUiThread(()->{dialog.dismiss();trackingCheckInFlight.set(false);toast("Ошибка проверки: "+e.getMessage());showTrackingDiagnostics();});}});});content.addView(test,new LinearLayout.LayoutParams(-1,dp(50)));
        Button battery=button("Настройки экономии батареи");battery.setTextColor(GREEN);battery.setBackground(round(CARD,14,GREEN,1));battery.setOnClickListener(v->{try{startActivity(TrackingDiagnostics.batteryOptimizationIntent(this));}catch(Exception e){startActivity(TrackingDiagnostics.appDetailsIntent(this));}});LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,dp(50));bp.setMargins(0,dp(8),0,0);content.addView(battery,bp);
        Button app=button("Открыть системные настройки приложения");app.setTextColor(GREEN);app.setBackground(round(CARD,14,GREEN,1));app.setOnClickListener(v->startActivity(TrackingDiagnostics.appDetailsIntent(this)));LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,dp(50));ap.setMargins(0,dp(8),0,0);content.addView(app,ap);
    }


    private void openPrivateRepositoryAccess(){
        repositoryAccessBrowserActive = true;
        repositoryAccessBrowserWasPaused = false;
        String url = prefs.getString("repository_access_settings_url", AccountOperationsController.INSTALLATIONS_PAGE);
        openSecureGitHubPage(url);
    }

    private void openSecureGitHubPage(String url){
        try{
            boolean customTab=GitHubCustomTabs.open(this,Uri.parse(url),GREEN,"dark".equals(activeTheme));
            if(!customTab)openWeb(url);
        }catch(Exception e){openWeb(url);}
    }

    private boolean accountViewIsCurrent(long generation,String accountId) {
        return !isFinishing() && !isDestroyed()
                && generation==screenGeneration.get()
                && isSignedIn() && accountId.equals(currentAccountId());
    }

    private void showAccountCenter(){
        setCurrentScreen(this::showAccountCenter);
        shell("Аккаунт GitHub",false);
        settingsPanelHeader("Billing и Actions",
                "Быстрый обзор; сканирование хранилища запускается отдельно",R.drawable.ic_settings);
        if(!isSignedIn()){
            accountRequiredCard("Подключите GitHub, чтобы загрузить данные аккаунта и Actions.");
            return;
        }
        final long generation=screenGeneration.get();
        final String accountId=currentAccountId();

        sectionTitle("GitHub Actions · использование",null);
        TextView billingText=text("Загружаем официальный отчёт Billing…",13,false);
        billingText.setTextColor(MUTED);
        card(billingText);
        Button refreshBilling=button("Обновить Billing");
        refreshBilling.setOnClickListener(v->{
            api.invalidatePrefix("https://api.github.com/users/");
            showAccountCenter();
        });
        content.addView(refreshBilling,new LinearLayout.LayoutParams(-1,dp(48)));

        sectionTitle("Хранилище Actions","Инвентаризация не является официальным остатком квоты");
        TextView inventoryStatus=text("Хранилище ещё не проверено. Нажмите «Сканировать», чтобы получить данные о доступных репозиториях.",12,false);
        inventoryStatus.setTextColor(MUTED);
        card(inventoryStatus);
        ProgressBar inventoryProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        inventoryProgress.setMax(100);
        inventoryProgress.setProgress(0);
        inventoryProgress.setVisibility(View.GONE);
        content.addView(inventoryProgress,new LinearLayout.LayoutParams(-1,dp(7)));
        LinearLayout inventoryActions=new LinearLayout(this);
        Button scan=button("Сканировать хранилище");
        Button cancel=button("Остановить");
        cancel.setEnabled(false);
        inventoryActions.addView(scan,new LinearLayout.LayoutParams(0,dp(48),2));
        LinearLayout.LayoutParams cancelParams=new LinearLayout.LayoutParams(0,dp(48),1);
        cancelParams.setMargins(dp(7),0,0,0);
        inventoryActions.addView(cancel,cancelParams);
        content.addView(inventoryActions,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout inventoryRows=new LinearLayout(this);
        inventoryRows.setOrientation(LinearLayout.VERTICAL);
        content.addView(inventoryRows);
        scan.setOnClickListener(v->startAccountInventory(generation,accountId,
                inventoryStatus,inventoryProgress,inventoryRows,scan,cancel));
        cancel.setOnClickListener(v->{
            accountInventoryCancelled.set(true);
            if(accountInventoryFuture!=null)accountInventoryFuture.cancel(true);
            inventoryStatus.setText("Сканирование остановлено. Неполный результат не считается общим объёмом хранилища.");
            inventoryProgress.setVisibility(View.GONE);
            scan.setEnabled(true);
            cancel.setEnabled(false);
        });

        sectionTitle("Оплата GitHub",null);
        TextView payment=text("Карта, PayPal и подтверждение платежа вводятся только на официальной странице GitHub. GitHubble не получает и не хранит платёжные реквизиты.",12,false);
        payment.setTextColor(MUTED);
        card(payment);
        Button billing=button("Открыть Billing & payment");
        billing.setOnClickListener(v->openSecureGitHubPage(AccountOperationsController.BILLING_PAGE));
        content.addView(billing,new LinearLayout.LayoutParams(-1,dp(52)));

        sectionTitle("Доступ",null);
        settingsActionRow("Приватные репозитории",
                "Выбрать репозитории для GiHubble",this::openPrivateRepositoryAccess);
        TextView perms=text("Просмотр данных требует разрешений GitHub App и доступа к биллингу соответствующего владельца. Недоступный результат не приравнивается к нулю.",11,false);
        perms.setTextColor(MUTED);
        card(perms);

        io.execute(()->{
            try{
                AccountOperationsController.BillingSnapshot result=accountOperations.loadBillingOverview();
                runOnUiThread(()->{
                    if(!accountViewIsCurrent(generation,accountId))return;
                    StringBuilder details=new StringBuilder();
                    details.append("Аккаунт: @").append(result.login.isEmpty()?"не определён":result.login)
                            .append("\nТариф: ").append(result.planName.isEmpty()?"не определён":result.planName)
                            .append("\nПериод: ").append(result.billingPeriod).append(" UTC")
                            .append("\nActions: ");
                    if(result.billingApiAvailable){
                        details.append(String.format(Locale.ROOT,"%.2f мин",result.minutesUsed));
                    }else details.append("официальное использование недоступно");
                    details.append("\nМинут по стандартному тарифу (справочно): ")
                            .append(result.minutesLimit<0?"не определено":result.minutesLimit);
                    details.append("\nОстаток минут: не подтверждён GitHub Billing API.");
                    if(!result.billingWarning.isEmpty())
                        details.append("\n").append(result.billingWarning);
                    billingText.setText(details.toString());
                });
            }catch(Exception error){
                runOnUiThread(()->{
                    if(accountViewIsCurrent(generation,accountId))
                        billingText.setText("Billing недоступен: "+error.getMessage());
                });
            }
        });
    }

    private void startAccountInventory(long generation,String accountId,
                                       TextView status,ProgressBar progress,LinearLayout rows,
                                       Button scan,Button cancel){
        if(!accountViewIsCurrent(generation,accountId))return;
        accountInventoryCancelled.set(true);
        if(accountInventoryFuture!=null)accountInventoryFuture.cancel(true);
        final AtomicBoolean stopped=new AtomicBoolean(false);
        accountInventoryCancelled=stopped;
        rows.removeAllViews();
        status.setText("Получаем список доступных репозиториев…");
        progress.setProgress(0);
        progress.setVisibility(View.VISIBLE);
        scan.setEnabled(false);
        cancel.setEnabled(true);
        accountInventoryFuture=inventoryIo.submit(()->{
            try{
                AccountOperationsController.StorageInventory result=
                        accountOperations.loadStorageInventory((done,total)->runOnUiThread(()->{
                            if(accountViewIsCurrent(generation,accountId)&&!stopped.get()){
                                status.setText("Проверено репозиториев: "+done+" из "+total);
                                progress.setProgress(total==0?0:Math.min(100,(int)((long)done*100/total)));
                            }
                        }),()->stopped.get()||Thread.currentThread().isInterrupted()
                                ||generation!=screenGeneration.get()
                                ||!accountId.equals(currentAccountId()));
                runOnUiThread(()->{
                    if(!accountViewIsCurrent(generation,accountId)||stopped.get())return;
                    progress.setVisibility(View.GONE);
                    scan.setEnabled(true);
                    cancel.setEnabled(false);
                    String state=result.isPartial()?"ЧАСТИЧНЫЕ ДАННЫЕ":"Проверенные данные";
                    status.setText(state+" · "+result.scanned+" из "+result.total
                            +" репозиториев; без доступа: "+result.inaccessible
                            +"\nНайдено artifacts: "+formatAccountBytes(result.artifactBytes)
                            +"; Actions cache: "+formatAccountBytes(result.cacheBytes)
                            +"\nЭто сумма найденных объектов, а не остаток квоты GitHub."
                            +(result.truncated?"\nСканирование ограничено 100 репозиториями.":""));
                    if(result.repositories.isEmpty()){
                        TextView note=text("Доступные данные хранилища не найдены. Проверьте разрешение Actions: Read.",12,false);
                        note.setTextColor(MUTED);
                        rows.addView(note);
                    }
                    for(AccountOperationsController.RepositoryUsage usage:result.repositories){
                        if(usage.totalBytes()<=0&&usage.actionsAccessible)continue;
                        Button open=button(usage.fullName+" · "
                                +(usage.actionsAccessible?formatAccountBytes(usage.totalBytes()):"нет доступа"));
                        open.setAllCaps(false);
                        open.setEnabled(usage.actionsAccessible);
                        open.setOnClickListener(v->navigate(()->showActionsCleanup(usage.fullName)));
                        LinearLayout.LayoutParams rowParams=new LinearLayout.LayoutParams(-1,dp(49));
                        rowParams.setMargins(0,dp(5),0,0);
                        rows.addView(open,rowParams);
                    }
                });
            }catch(Exception error){
                runOnUiThread(()->{
                    if(!accountViewIsCurrent(generation,accountId)||stopped.get())return;
                    progress.setVisibility(View.GONE);
                    scan.setEnabled(true);
                    cancel.setEnabled(false);
                    status.setText("Инвентаризация не завершилась: "+error.getMessage()
                            +"\nОбъём хранилища неизвестен; данные Billing загружаются независимо.");
                });
            }
        });
    }

    private void showActionsCleanup(String full){
        setCurrentScreen(()->showActionsCleanup(full));
        shell("Actions storage",true);
        repoSectionHeader(full,"Артефакты и кэши","GitHub Actions",R.drawable.ic_history);
        if(!isSignedIn()){accountRequiredCard("Подключите GitHub для управления Actions.");return;}
        ProgressBar pb=new ProgressBar(this);content.addView(pb);
        io.execute(()->{
            try{
                List<AccountOperationsController.CleanupItem> items=accountOperations.loadCleanupItems(full);
                runOnUiThread(()->{
                    content.removeView(pb);
                    if(items.isEmpty()){
                        TextView empty=text("Артефактов и Actions cache нет.",13,false);empty.setTextColor(MUTED);card(empty);return;
                    }
                    long total=0L;for(AccountOperationsController.CleanupItem item:items)total+=Math.max(0L,item.sizeBytes);
                    settingsRow("Найдено",items.size()+" объектов · "+formatAccountBytes(total));
                    TextView note=text("Отметьте только то, что действительно можно удалить. Исходный код и Releases этим экраном не затрагиваются.",11,false);note.setTextColor(MUTED);card(note);
                    List<CheckBox> checks=new ArrayList<>();
                    for(AccountOperationsController.CleanupItem item:items){
                        CheckBox box=new CheckBox(this);
                        String type=AccountOperationsController.CleanupItem.CACHE.equals(item.kind)?"CACHE":"ARTIFACT";
                        box.setText(type+" · "+item.name+"\n"+formatAccountBytes(item.sizeBytes)+(item.extra.isEmpty()?"":" · "+item.extra));
                        box.setTextColor(TEXT);box.setTextSize(13);box.setPadding(dp(6),dp(7),dp(6),dp(7));
                        checks.add(box);card(box);
                    }
                    LinearLayout actions=new LinearLayout(this);
                    Button all=button("Выбрать всё");all.setOnClickListener(v->{for(CheckBox b:checks)b.setChecked(true);});
                    Button delete=button("Удалить выбранное");delete.setOnClickListener(v->{
                        List<AccountOperationsController.CleanupItem> selected=new ArrayList<>();long bytes=0L;
                        for(int i=0;i<checks.size();i++)if(checks.get(i).isChecked()){selected.add(items.get(i));bytes+=items.get(i).sizeBytes;}
                        if(selected.isEmpty()){toast("Ничего не выбрано");return;}
                        long finalBytes=bytes;
                        new AlertDialog.Builder(this).setTitle("Удалить выбранное?")
                                .setMessage("Объектов: "+selected.size()+"\nБудет освобождено примерно: "+formatAccountBytes(finalBytes)+"\n\nОперация необратима для этих Actions-артефактов/кэшей.")
                                .setNegativeButton("Отмена",null)
                                .setPositiveButton("Удалить",(d,w)->deleteActionsItems(full,selected)).show();
                    });
                    actions.addView(all,new LinearLayout.LayoutParams(0,dp(50),1));
                    LinearLayout.LayoutParams dpv=new LinearLayout.LayoutParams(0,dp(50),1);dpv.setMargins(dp(7),0,0,0);actions.addView(delete,dpv);content.addView(actions);
                });
            }catch(Exception e){runOnUiThread(()->{content.removeView(pb);errorCard("Не удалось загрузить Actions",e.getMessage());});}
        });
    }

    private void deleteActionsItems(String full,List<AccountOperationsController.CleanupItem> selected){
        ProgressDialog dialog=ProgressDialog.show(this,"Очистка Actions","Удаление выбранных объектов…",true,false);
        io.execute(()->{
            AccountOperationsController.DeleteResult result=accountOperations.deleteCleanupItems(selected);
            runOnUiThread(()->{
                dialog.dismiss();
                new AlertDialog.Builder(this).setTitle("Очистка завершена")
                        .setMessage("Удалено: "+result.deleted+"\nОшибок: "+result.failed+"\nОсвобождено: "+formatAccountBytes(result.bytesFreed))
                        .setPositiveButton("Обновить",(d,w)->showActionsCleanup(full)).show();
            });
        });
    }

    /**
     * GitHub commit details, changed files and per-file unified diffs remain in
     * native GitHubble UI. The official web link is an optional fallback only.
     */
    private void showCommitDetail(String full,String sha){
        if(!CommitDetailController.validRepository(full)
                ||!CommitDetailController.validSha(sha)){
            errorDialog("Некорректный коммит","Проверьте адрес репозитория и SHA.");
            return;
        }
        rememberScreen("commit",routeArgs("full",full,"sha",sha));
        setCurrentScreen(()->showCommitDetail(full,sha));
        shell("Коммит",false);
        final long generation=screenGeneration.get();
        final String account=currentAccountId();
        final LinearLayout destination=content;
        repoSectionHeader(full,"Коммит",shortSha(sha),R.drawable.ic_repository);
        ProgressBar loading=new ProgressBar(this);
        destination.addView(loading);
        io.execute(()->{
            try{
                CommitDetailController.Detail detail=new CommitDetailController(api).load(full,sha);
                runOnUiThread(()->{
                    if(generation!=screenGeneration.get()||destination!=content
                            ||!account.equals(currentAccountId()))return;
                    destination.removeView(loading);
                    CommitDetailScreen screen=new CommitDetailScreen(this,destination,
                            new CommitDetailScreen.Actions(){
                                @Override public void openRepository(String name){
                                    navigate(()->showRepository(name));
                                }
                                @Override public void openParent(String parentSha){
                                    navigate(()->showCommitDetail(full,parentSha));
                                }
                                @Override public void openExternalCommit(String name,String commit){
                                    openWeb("https://github.com/"+name+"/commit/"+commit);
                                }
                                @Override public void openRemoteFile(JSONObject file){
                                    String url=file.optString("raw_url","");
                                    if(!url.startsWith("https://"))return;
                                    String name=file.optString("filename","Файл");
                                    navigate(()->showRemotePreview(name,url,
                                            file.optString("blob_url","")));
                                }
                            },CARD,TEXT,MUTED,GREEN,BORDER);
                    screen.render(full,sha,detail);
                });
            }catch(Exception error){
                runOnUiThread(()->{
                    if(generation!=screenGeneration.get()||destination!=content
                            ||!account.equals(currentAccountId()))return;
                    destination.removeView(loading);
                    errorCard("Не удалось получить данные коммита",
                            error.getMessage()==null?"Проверьте доступ к GitHub и соединение."
                                    :error.getMessage());
                    Button retry=compactButton("Повторить");
                    retry.setOnClickListener(v->showCommitDetail(full,sha));
                    destination.addView(retry,new LinearLayout.LayoutParams(-1,dp(48)));
                });
            }
        });
    }

    private void showRepositoryCommits(String full,String branch){
        setCurrentScreen(()->showRepositoryCommits(full,branch));
        shell("Коммиты",true);
        repoSectionHeader(full,"Коммиты","Ветка "+branch,R.drawable.ic_history);
        TextView warning=text("Опасное принудительное удаление HEAD убрано. Для отмены изменений используйте revert-коммит и Pull Request на GitHub — история ветки останется восстанавливаемой.",12,false);warning.setTextColor(MUTED);card(warning);
        ProgressBar pb=new ProgressBar(this);content.addView(pb);
        io.execute(()->{
            try{
                JSONArray commits=getArray("https://api.github.com/repos/"+full+"/commits?sha="+Uri.encode(branch)+"&per_page=30");
                runOnUiThread(()->{
                    content.removeView(pb);sectionTitle("Последние коммиты",null);
                    for(int i=0;i<commits.length();i++){
                        JSONObject item=commits.optJSONObject(i);if(item==null)continue;
                        JSONObject commit=item.optJSONObject("commit");JSONObject author=commit==null?null:commit.optJSONObject("author");
                        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);
                        String message=commit==null?"Коммит":commit.optString("message","Коммит");row.addView(text(firstLine(message),14,true));
                        String sha=item.optString("sha","");TextView meta=text(shortSha(sha)+(author==null?"":" · "+author.optString("name",""))+" · "+formatGitHubDate(author==null?"":author.optString("date","")),11,false);meta.setTextColor(MUTED);row.addView(meta);
                        if(CommitDetailController.validSha(sha))
                            row.setOnClickListener(v->navigate(()->showCommitDetail(full,sha)));
                        card(row);
                    }
                });
            }catch(Exception e){runOnUiThread(()->{content.removeView(pb);errorCard("Не удалось загрузить коммиты",e.getMessage());});}
        });
    }

    private void showDeleteHeadCommitDialog(String full,String branch){
        ProgressDialog load=ProgressDialog.show(this,"HEAD-коммит","Проверка ветки…",true,false);
        io.execute(()->{
            try{
                AccountOperationsController.BranchHead head=accountOperations.loadBranchHead(full,branch);
                runOnUiThread(()->{
                    load.dismiss();
                    String summary=firstLine(head.message)+"\n\nHEAD: "+shortSha(head.sha)+"\nРодитель: "+shortSha(head.parentSha)+"\n\nВетка будет принудительно перемещена назад на один коммит.";
                    new AlertDialog.Builder(this).setTitle("Удалить последний коммит?")
                            .setMessage(summary).setNegativeButton("Отмена",null)
                            .setPositiveButton("Продолжить",(d,w)->confirmHeadReset(full,head)).show();
                });
            }catch(Exception e){runOnUiThread(()->{load.dismiss();errorDialog("Откат недоступен",e.getMessage());});}
        });
    }

    private void confirmHeadReset(String full,AccountOperationsController.BranchHead head){
        EditText input=new EditText(this);input.setHint(shortSha(head.sha));input.setSingleLine(true);
        new AlertDialog.Builder(this).setTitle("Подтверждение переписывания истории")
                .setMessage("Введите короткий SHA удаляемого HEAD-коммита: "+shortSha(head.sha))
                .setView(input).setNegativeButton("Отмена",null)
                .setPositiveButton("Удалить HEAD",(d,w)->{
                    if(!shortSha(head.sha).equals(input.getText().toString().trim())){toast("SHA не совпадает — операция отменена");return;}
                    performHeadReset(full,head);
                }).show();
    }

    private void performHeadReset(String full,AccountOperationsController.BranchHead head){
        ProgressDialog progress=ProgressDialog.show(this,"Коммиты","Перемещение ветки…",true,false);
        io.execute(()->{
            try{
                accountOperations.resetBranchToParent(full,head);
                runOnUiThread(()->{progress.dismiss();new AlertDialog.Builder(this).setTitle("HEAD удалён").setMessage("Ветка "+head.branch+" теперь указывает на "+shortSha(head.parentSha)).setPositiveButton("Обновить",(d,w)->showRepositoryCommits(full,head.branch)).show();});
            }catch(Exception e){runOnUiThread(()->{progress.dismiss();errorDialog("Не удалось изменить ветку",e.getMessage());});}
        });
    }

    private String formatAccountBytes(long bytes){
        if(bytes<1024L)return bytes+" B";
        double kb=bytes/1024d;if(kb<1024d)return String.format(Locale.US,"%.1f KB",kb);
        double mb=kb/1024d;if(mb<1024d)return String.format(Locale.US,"%.1f MB",mb);
        return String.format(Locale.US,"%.2f GB",mb/1024d);
    }

    private void showSettings(){
        rememberScreen("settings",null);
        setCurrentScreen(this::showSettings);
        shell("Настройки",false);
        settingsDashboardHeader();
        settingsCategoryCard("Аккаунт и доступ",
                "Профиль, GitHub App, приватные репозитории",
                R.drawable.ic_profile, 0xFF25994F,
                () -> navigate(this::showSettingsAccount));
        settingsCategoryCard("Интерфейс",
                "Темы, язык, перевод содержимого",
                R.drawable.ic_tools, 0xFF8654D3,
                () -> navigate(this::showSettingsInterface));
        settingsCategoryCard("Уведомления",
                "Наблюдение, события и фоновые проверки",
                R.drawable.ic_bell, 0xFF387DE2,
                () -> navigate(this::showSettingsNotifications));
        settingsCategoryCard("Данные и синхронизация",
                "Кэш, офлайн-очередь, резервные копии",
                R.drawable.ic_repository, 0xFF179B9C,
                () -> navigate(this::showSettingsData));
        settingsCategoryCard("Безопасность",
                "Защита токена, данные и выход из аккаунта",
                R.drawable.ic_lock, 0xFFE18C32,
                () -> navigate(this::showSettingsSecurity));
        settingsCategoryCard("О приложении",
                "Версия, диагностика, сведения о сборке",
                R.drawable.ic_settings, 0xFF657A88,
                () -> navigate(this::showSettingsAbout));
    }

    private void settingsDashboardHeader(){
        LinearLayout hero=new LinearLayout(this);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(12),dp(12),dp(12),dp(12));
        hero.setBackground(round(SUBTLE,18,BORDER,1));
        ImageView icon=imageIcon(R.drawable.ic_settings, Color.WHITE, 11);
        icon.setBackground(round(GREEN,13,0,0));
        hero.addView(icon,new LinearLayout.LayoutParams(dp(48),dp(48)));
        LinearLayout title=new LinearLayout(this);
        title.setOrientation(LinearLayout.VERTICAL);
        title.setPadding(dp(11),0,dp(4),0);
        TextView name=text("Настройки GitHubble",18,true);
        name.setPadding(0,0,0,0);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        title.addView(name);
        TextView caption=text("Управление приложением и GitHub",11,false);
        caption.setTextColor(MUTED);
        caption.setPadding(0,dp(3),0,0);
        caption.setMaxLines(2);
        title.addView(caption);
        hero.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);
        hp.setMargins(0,0,0,dp(12));
        content.addView(hero,hp);
        TextView state=text(isSignedIn()?
                (getProfileLogin().isEmpty()?"GitHub подключён":"Подключён: @"+getProfileLogin()):
                "Гостевой режим · аккаунт не подключён",11,false);
        state.setTextColor(GREEN);
        state.setPadding(dp(3),0,0,dp(9));
        state.setMaxLines(2);
        content.addView(state);
    }

    private void settingsCategoryCard(String title,String subtitle,int iconResource,int accent,Runnable action){
        LinearLayout row=new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(11),dp(11),dp(9),dp(11));
        row.setBackground(round(CARD,15,BORDER,1));

        ImageView pictogram=imageIcon(iconResource,Color.WHITE,10);
        pictogram.setBackground(round(accent,11,0,0));
        pictogram.setContentDescription(title);
        row.addView(pictogram,new LinearLayout.LayoutParams(dp(45),dp(45)));

        LinearLayout labels=new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(11),0,dp(3),0);
        TextView heading=text(title,15,true);
        heading.setPadding(0,0,0,0);
        heading.setSingleLine(true);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(heading,new LinearLayout.LayoutParams(-1,-2));
        TextView detail=text(subtitle,11,false);
        detail.setTextColor(MUTED);
        detail.setPadding(0,dp(3),0,0);
        detail.setMaxLines(2);
        detail.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(detail,new LinearLayout.LayoutParams(-1,-2));
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        TextView arrow=text("›",26,false);
        arrow.setTextColor(MUTED);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow,new LinearLayout.LayoutParams(dp(20),dp(42)));
        row.setContentDescription(title+". "+subtitle);
        row.setOnClickListener(v->action.run());
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
        params.setMargins(0,0,0,dp(9));
        content.addView(row,params);
    }

    private void settingsSubheader(String title,String subtitle,int icon){
        settingsPanelHeader(title,subtitle,icon);
    }

    private void settingsPanelHeader(String title,String subtitle,int icon){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12),dp(12),dp(12),dp(12));
        row.setBackground(round(SUBTLE,17,BORDER,1));
        ImageView art=imageIcon(icon,Color.WHITE,10);
        art.setBackground(round(GREEN,12,0,0));
        row.addView(art,new LinearLayout.LayoutParams(dp(44),dp(44)));
        LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(11),0,0,0);
        TextView name=text(title,17,true);name.setPadding(0,0,0,0);
        name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(name);
        TextView sub=text(subtitle,11,false);sub.setPadding(0,dp(3),0,0);
        sub.setTextColor(MUTED);sub.setMaxLines(2);
        labels.addView(sub);
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,0,0,dp(12));content.addView(row,p);
    }

    private boolean isSettingsDetail(){
        return "Аккаунт и доступ".equals(currentTitle)||"Интерфейс".equals(currentTitle)
            ||"Уведомления".equals(currentTitle)||"Данные и синхронизация".equals(currentTitle)
            ||"Безопасность".equals(currentTitle)||"О приложении".equals(currentTitle)
            ||"Настройки наблюдения".equals(currentTitle)||"Мониторинг сборок".equals(currentTitle)||"Оформление".equals(currentTitle)||"Размер карточек".equals(currentTitle)
            ||"Конфиденциальность и данные".equals(currentTitle)||"Резервная копия".equals(currentTitle);
    }

    private void nestedSettingsRow(String title,String detail,int icon,int accent,Runnable click){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(11),dp(10),dp(9),dp(10));
        row.setBackground(round(CARD,15,BORDER,1));
        ImageView art=imageIcon(icon,Color.WHITE,10);
        art.setBackground(round(accent,10,0,0));
        row.addView(art,new LinearLayout.LayoutParams(dp(40),dp(40)));
        LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(10),0,dp(5),0);
        TextView name=text(title,14,true);name.setPadding(0,0,0,0);
        name.setSingleLine(true);name.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(name);
        TextView value=text(detail,11,false);value.setPadding(0,dp(3),0,0);
        value.setTextColor(MUTED);value.setMaxLines(2);
        value.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(value);
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        if(click!=null){
            TextView arrow=text("›",25,false);arrow.setTextColor(MUTED);
            arrow.setGravity(Gravity.CENTER);
            row.addView(arrow,new LinearLayout.LayoutParams(dp(20),dp(40)));
            row.setOnClickListener(v->click.run());
        }
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,0,0,dp(8));content.addView(row,p);
    }

    private int settingsIcon(String name){
        String label=name.toLowerCase(Locale.ROOT);
        if(label.contains("тема")||label.contains("язык")||label.contains("перевод"))
            return R.drawable.ic_tools;
        if(label.contains("уведом")||label.contains("фон")||label.contains("наблюд"))
            return R.drawable.ic_bell;
        if(label.contains("кэш")||label.contains("синхр")||label.contains("офлайн")||label.contains("копи"))
            return R.drawable.ic_repository;
        if(label.contains("доступ")||label.contains("конфид")||label.contains("токен")||label.contains("выйти"))
            return R.drawable.ic_lock;
        if(label.contains("верси")||label.contains("сборк")||label.contains("диагност"))
            return R.drawable.ic_settings;
        return R.drawable.ic_profile;
    }


    private void showSettingsAccount(){
        setCurrentScreen(this::showSettingsAccount);
        shell("Аккаунт и доступ",false);
        settingsSubheader("Аккаунт и GitHub","Подключение и разрешения",R.drawable.ic_profile);
        String login=getProfileLogin();
        settingsActionRow("Личный профиль",isSignedIn()?
                (login.isEmpty()?"GitHub подключён":"@"+login):"Не подключён",
                () -> navigate(this::showProfile));
        settingsActionRow("GitHub App","Подключение, тариф и Actions",
                () -> navigate(this::showAccountCenter));
        settingsActionRow("Приватные репозитории","Разрешения и синхронизация",
                this::openPrivateRepositoryAccess);
        if(isSignedIn())settingsActionRow("Отключить аккаунт","Удалить локальный сеанс",
                this::confirmSignOut);
    }

    private void showSettingsInterface(){
        setCurrentScreen(this::showSettingsInterface);
        shell("Интерфейс",false);
        settingsSubheader("Внешний вид","Оформление и перевод содержимого",R.drawable.ic_tools);
        settingsActionRow("Тема оформления",themeDisplayName(),() -> navigate(this::showThemeChooser));
        settingsActionRow("Размер карточек", "spacious".equals(prefs.getString("ui_card_density","compact")) ? "Свободный" : "Компактный", () -> navigate(this::showCardSizeChooser));
        settingsRow("Язык интерфейса","Русский · другие языки пока не поддерживаются");
        sectionTitle("Перевод",null);
        settingsActionRow("Как работает перевод","Защита кода и Markdown",this::showTranslationInfo);
        settingsActionRow("Провайдер перевода",translatorDisplayName(),this::configureTranslatorSettings);
        settingsToggleRow("Автоперевод","Переводить иностранный контент при открытии",
                "translator_auto",false);
        settingsRow("Кэш переводов",translationCache.count()+" документов");
    }

    private void showSettingsNotifications(){
        setCurrentScreen(this::showSettingsNotifications);
        shell("Уведомления",false);
        settingsSubheader("События и наблюдение","Настройки реальных фоновых проверок",R.drawable.ic_bell);
        settingsActionRow("Наблюдаемые разработчики",
                trackingDb.statistics().optInt("developers",0)+" разработчиков",
                () -> navigate(this::showTracking));
        settingsActionRow("Мониторинг сборок GitHub",
                "APK, ZIP, завершение Actions и уведомления",
                () -> navigate(this::showBuildMonitorSettings));
        settingsActionRow("Центр сборок",
                "Последние запуски и доступные артефакты",
                () -> navigate(this::showBuildCenter));
        settingsActionRow("Фоновые проверки",
                "Интервал и типы уведомлений",() -> navigate(this::showTrackingSettings));
        settingsActionRow("Диагностика фона","JobScheduler и ограничения системы",
                this::showTrackingDiagnostics);
        settingsActionRow("Открыть активность","События доступных источников GitHub",
                () -> navigate(this::showNotifications));
        TextView hint=text("Отдельное управление push-уведомлениями Issues и PR пока не реализовано. "+
                "Настройки выше относятся к отслеживанию разработчиков и репозиториев.",11,false);
        hint.setTextColor(MUTED);
        card(hint);
    }

    private void showSettingsData(){
        setCurrentScreen(this::showSettingsData);
        shell("Данные и синхронизация",false);
        settingsSubheader("Локальные данные","Кэш, офлайн и резервные копии",R.drawable.ic_repository);
        settingsActionRow("Центр сборок и артефакты",
                "Сборки, APK, скачивание и локальный архив",
                () -> navigate(this::showBuildCenter));
        settingsActionRow("Мониторинг сборок",
                "Выбор проектов, фоновые проверки и уведомления",
                () -> navigate(this::showBuildMonitorSettings));
        settingsActionRow("Офлайн-кэш",cacheSizeText(),this::showCacheDialog);
        int pending=features.offlineQueue.count(OfflineActionQueue.PENDING);
        int conflicts=features.offlineQueue.count(OfflineActionQueue.CONFLICT);
        int failed=features.offlineQueue.count(OfflineActionQueue.FAILED);
        settingsActionRow("Офлайн-очередь",
                pending+" ожидают · "+conflicts+" конфликтов · "+failed+" ошибок",
                () -> navigate(this::showOfflineQueue));
        settingsActionRow("Резервная копия","Избранное, наблюдение и настройки",
                () -> navigate(this::showPortableBackup));
        settingsActionRow("Фоновые проверки","Интервал синхронизации наблюдения",
                () -> navigate(this::showTrackingSettings));
        settingsRow("Кэш объектов GitHub",entityCache.count()+" объектов");
        settingsRow("Остаток GitHub API",
                api.getRemaining()<0?"Не определён":String.valueOf(api.getRemaining()));
    }

    private void showSettingsSecurity(){
        setCurrentScreen(this::showSettingsSecurity);
        shell("Безопасность",false);
        settingsSubheader("Защита данных","Сеанс GitHub и локальная конфиденциальность",R.drawable.ic_lock);
        settingsRow("Защита токена",isSignedIn()?"Android Keystore":"Нет активного сеанса");
        settingsActionRow("Конфиденциальность и данные",
                "Экспорт и удаление локальных данных",() -> navigate(this::showPrivacyAndData));
        settingsActionRow("Разрешения GitHub App","Проверка доступа",
                this::openPrivateRepositoryAccess);
        if(isSignedIn())settingsActionRow("Выйти из GitHub","Завершить локальный сеанс",
                this::confirmSignOut);
        TextView hint=text("PIN-код и биометрическая блокировка самого приложения пока не реализованы. "+
                "Шифрование токена не заменяет блокировку входа в GitHubble.",11,false);
        hint.setTextColor(MUTED);
        card(hint);
    }

    private void showSettingsAbout(){
        setCurrentScreen(this::showSettingsAbout);
        shell("О приложении",false);
        settingsSubheader("GitHubble "+BuildConfig.VERSION_NAME,
                "Нативный Android-клиент GitHub",R.drawable.ic_settings);
        settingsRow("Версия",BuildConfig.VERSION_NAME);
        settingsRow("Канал",BuildConfig.RC_CHANNEL);
        settingsRow("GitHub App","GiHubble");
        settingsActionRow("Сведения о сборке","Release Candidate и состояние приложения",
                () -> navigate(this::showReleaseCandidateStatus));
        settingsActionRow("Экспорт диагностики","ZIP без OAuth-токена и API-ключей",
                this::exportDiagnostics);
        settingsActionRow("Диагностика фоновых задач","JobScheduler и MIUI",
                this::showTrackingDiagnostics);
    }

    private void showOfflineQueue(){
        rememberScreen("offline_queue",null);setCurrentScreen(this::showOfflineQueue);shell("Офлайн-очередь",false);settingsPanelHeader("Отложенные изменения","Безопасный повтор после восстановления сети",R.drawable.ic_history);
        List<OfflineActionQueue.Action> actions=features.offlineQueue.listAll();int pending=features.offlineQueue.count(OfflineActionQueue.PENDING),conflicts=features.offlineQueue.count(OfflineActionQueue.CONFLICT),failed=features.offlineQueue.count(OfflineActionQueue.FAILED);featureState.pendingOfflineActions=pending;featureState.conflicts=conflicts;
        LinearLayout summary=new LinearLayout(this);summary.setOrientation(LinearLayout.VERTICAL);summary.addView(text("Ожидают: "+pending+" · Конфликты: "+conflicts+" · Ошибки: "+failed,15,true));TextView note=text("Токены в очереди не сохраняются. Перед повторной записью GitHubble сравнивает версию объекта на GitHub.",11,false);note.setTextColor(MUTED);summary.addView(note);card(summary);
        Button send=button("Отправить готовые сейчас");send.setEnabled(!actions.isEmpty()&&features.offlineDispatcher.isOnline());send.setOnClickListener(v->processOfflineQueueNow());content.addView(send,new LinearLayout.LayoutParams(-1,dp(50)));
        if(actions.isEmpty()){card(text("Очередь пуста. Изменения отправляются сразу, когда сеть доступна.",13,false));return;}
        sectionTitle("Действия",null);for(OfflineActionQueue.Action action:actions)renderOfflineAction(action);
    }

    private void processOfflineQueueNow(){
        ProgressDialog progress=ProgressDialog.show(this,"Офлайн-очередь","Проверяем изменения и отправляем…",true,false);io.execute(()->{try{OfflineActionQueue.ProcessResult r=features.offlineQueue.processReady(api);runOnUiThread(()->{progress.dismiss();toast("Отправлено: "+r.completed+", конфликтов: "+r.conflicts+", отложено: "+r.deferred);showOfflineQueue();});}catch(Exception e){runOnUiThread(()->{progress.dismiss();errorDialog("Не удалось обработать очередь",e.getMessage());});}});
    }

    private void renderOfflineAction(OfflineActionQueue.Action action){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);String status=OfflineActionQueue.PENDING.equals(action.status)?"Ожидает сети":OfflineActionQueue.CONFLICT.equals(action.status)?"Конфликт":"Ошибка";TextView title=text(action.summary.isEmpty()?action.method+" "+action.url:action.summary,14,true);box.addView(title);TextView meta=text(status+" · попыток "+action.attempts+" · "+formatEpoch(action.createdAt),11,false);meta.setTextColor(OfflineActionQueue.CONFLICT.equals(action.status)?Color.rgb(190,120,20):OfflineActionQueue.FAILED.equals(action.status)?Color.rgb(190,55,60):MUTED);box.addView(meta);if(!action.lastError.isEmpty()){TextView error=text(action.lastError,11,false);error.setTextColor(MUTED);box.addView(error);}LinearLayout buttons=new LinearLayout(this);buttons.setPadding(0,dp(8),0,0);
        if(OfflineActionQueue.CONFLICT.equals(action.status)){Button compare=compactButton("Сравнить");compare.setOnClickListener(v->showOfflineConflictPreview(action));buttons.addView(compare,new LinearLayout.LayoutParams(0,dp(44),1));Button force=compactButton("Всё равно отправить");force.setOnClickListener(v->{features.offlineQueue.retry(action.id,true);processOfflineQueueNow();});LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(0,dp(44),1);fp.setMargins(dp(5),0,0,0);buttons.addView(force,fp);}else{Button retry=compactButton("Повторить");retry.setOnClickListener(v->{features.offlineQueue.retry(action.id,false);processOfflineQueueNow();});buttons.addView(retry,new LinearLayout.LayoutParams(0,dp(44),1));}
        Button discard=compactButton("Удалить");discard.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Удалить действие?").setMessage(action.summary).setPositiveButton("Удалить",(d,w)->{features.offlineQueue.discard(action.id);showOfflineQueue();}).setNegativeButton("Отмена",null).show());LinearLayout.LayoutParams dpv=new LinearLayout.LayoutParams(0,dp(44),1);dpv.setMargins(dp(5),0,0,0);buttons.addView(discard,dpv);box.addView(buttons);card(box);
    }

    private void showOfflineConflictPreview(OfflineActionQueue.Action action){
        setCurrentScreen(()->showOfflineConflictPreview(action));shell("Конфликт",true);heroBanner(action.summary,"Проверьте локальную и серверную версии",R.drawable.ic_history);
        sectionTitle("Локальное изменение",null);TextView local=text(prettyJson(action.body),12,false);local.setTypeface(android.graphics.Typeface.MONOSPACE);local.setTextIsSelectable(true);card(local);
        sectionTitle("Текущая версия GitHub",null);TextView server=text(action.serverSnapshot.isEmpty()?"Снимок недоступен":prettyJson(action.serverSnapshot),12,false);server.setTypeface(android.graphics.Typeface.MONOSPACE);server.setTextIsSelectable(true);card(server);
        Button force=button("Отправить локальную версию");force.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Перезаписать данные GitHub?").setMessage("Изменения на сервере могут быть потеряны.").setPositiveButton("Отправить",(d,w)->{features.offlineQueue.retry(action.id,true);processOfflineQueueNow();}).setNegativeButton("Отмена",null).show());content.addView(force,new LinearLayout.LayoutParams(-1,dp(50)));
        Button discard=button("Оставить версию GitHub");discard.setOnClickListener(v->{features.offlineQueue.discard(action.id);navigate(this::showOfflineQueue);});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(50));p.setMargins(0,dp(8),0,0);content.addView(discard,p);
    }

    private String prettyJson(String value){try{return new JSONObject(value).toString(2);}catch(Exception e){try{return new JSONArray(value).toString(2);}catch(Exception ignored){return value==null?"":value;}}}

    private void showPortableBackup(){
        rememberScreen("portable_backup",null);setCurrentScreen(this::showPortableBackup);shell("Резервная копия",false);settingsPanelHeader("Резервная копия","Перенос данных без токенов и API-ключей",R.drawable.ic_star);
        TextView details=text("Экспорт включает избранные репозитории, список наблюдаемых разработчиков и пользовательские настройки. OAuth-токен, ключ переводчика, профильный кэш и ключ подписи не экспортируются.",13,false);details.setTextColor(MUTED);card(details);
        Button export=button("Экспортировать JSON");export.setOnClickListener(v->startPortableExport());content.addView(export,new LinearLayout.LayoutParams(-1,dp(50)));
        Button importButton=button("Импортировать JSON");importButton.setOnClickListener(v->startPortableImport());LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(-1,dp(50));ip.setMargins(0,dp(8),0,0);content.addView(importButton,ip);
    }

    private void startPortableExport(){
        try{pendingPortableExport=features.portableSettings.exportData().toString(2);Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("application/json");intent.putExtra(Intent.EXTRA_TITLE,"GitHubble_backup_"+new java.text.SimpleDateFormat("yyyyMMdd_HHmm",Locale.ROOT).format(new Date())+".json");startActivityForResult(intent,REQUEST_EXPORT_PORTABLE);}catch(Exception e){errorDialog("Не удалось подготовить резервную копию",e.getMessage());}
    }

    private void startPortableImport(){Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("application/json");startActivityForResult(intent,REQUEST_IMPORT_PORTABLE);}

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);if(resultCode!=RESULT_OK||data==null||data.getData()==null){if(requestCode==REQUEST_REPOSITORY_FOLDER||requestCode==REQUEST_REPOSITORY_ZIP||requestCode==REQUEST_REPOSITORY_FILE)clearPendingRepositoryImport();return;}Uri uri=data.getData();
        if(requestCode==REQUEST_REPOSITORY_FOLDER||requestCode==REQUEST_REPOSITORY_ZIP||requestCode==REQUEST_REPOSITORY_FILE){handleRepositoryImportUri(requestCode,uri);return;}
        if(requestCode==REQUEST_EXPORT_PORTABLE){try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("Не удалось открыть файл");out.write(pendingPortableExport.getBytes(StandardCharsets.UTF_8));out.flush();pendingPortableExport="";toast("Резервная копия сохранена");}catch(Exception e){errorDialog("Ошибка экспорта",e.getMessage());}}
        else if(requestCode==REQUEST_EXPORT_USER_DATA){try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("Не удалось открыть файл");out.write(pendingUserDataExport.getBytes(StandardCharsets.UTF_8));out.flush();pendingUserDataExport="";toast("Данные пользователя экспортированы");}catch(Exception e){errorDialog("Ошибка экспорта данных",e.getMessage());}}
        else if(requestCode==REQUEST_IMPORT_PORTABLE){try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("Не удалось открыть файл");JSONObject root=new JSONObject(readAll(in));PortableSettingsManager.ImportResult r=features.portableSettings.importData(root);applyTheme();TrackingJobService.schedule(this,prefs.getInt("tracking_interval_hours",6));new AlertDialog.Builder(this).setTitle("Импорт завершён").setMessage("Добавлено избранных: "+r.favoritesAdded+"\nДобавлено разработчиков: "+r.developersAdded+"\nПрименено настроек: "+r.settingsApplied).setPositiveButton("ОК",(d,w)->showPortableBackup()).show();}catch(Exception e){errorDialog("Ошибка импорта",e.getMessage());}}
    }

    private void settingsRow(String title,String value){settingsActionRow(title,value,null);}
    private void settingsActionRow(String title,String value,Runnable action){
        if(isSettingsDetail()){
            nestedSettingsRow(title,value,settingsIcon(title),GREEN,action);
            return;
        }
        LinearLayout r=new LinearLayout(this);r.setGravity(Gravity.CENTER_VERTICAL);TextView t=text(title,15,true);r.addView(t,new LinearLayout.LayoutParams(0,dp(50),1));TextView v=text(value,13,false);v.setTextColor(MUTED);v.setGravity(Gravity.END);v.setMaxLines(2);r.addView(v,new LinearLayout.LayoutParams(dp(172),-2));if(action!=null){TextView arrow=text("›",24,false);arrow.setTextColor(MUTED);arrow.setGravity(Gravity.CENTER);r.addView(arrow,new LinearLayout.LayoutParams(dp(24),dp(50)));r.setOnClickListener(x->action.run());}card(r);}

    private void settingsToggleRow(String title,String subtitle,String key,boolean defaultValue){
        if(isSettingsDetail()){
            LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(11),dp(11),dp(11),dp(11));
            row.setBackground(round(CARD,15,BORDER,1));
            ImageView art=imageIcon(settingsIcon(title),Color.WHITE,10);
            art.setBackground(round(GREEN,10,0,0));
            row.addView(art,new LinearLayout.LayoutParams(dp(40),dp(40)));
            LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(dp(10),0,dp(4),0);
            TextView name=text(title,14,true);name.setPadding(0,0,0,0);labels.addView(name);
            TextView description=text(subtitle,11,false);description.setTextColor(MUTED);
            description.setPadding(0,dp(3),0,0);description.setMaxLines(2);
            labels.addView(description);
            row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
            Switch toggle=new Switch(this);
            toggle.setChecked(prefs.getBoolean(key,defaultValue));
            toggle.setOnCheckedChangeListener((v,on)->{
                if("build_auto_apk".equals(key))BuildMonitor.setAutoDownloadEnabled(this,on);
                else prefs.edit().putBoolean(key,on).apply();
            });
            row.addView(toggle);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
            p.setMargins(0,0,0,dp(8));content.addView(row,p);
            return;
        }
        LinearLayout r=new LinearLayout(this);r.setGravity(Gravity.CENTER_VERTICAL);LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);TextView t=text(title,15,true);t.setPadding(0,0,0,0);labels.addView(t);TextView sub=text(subtitle,11,false);sub.setTextColor(MUTED);sub.setPadding(0,dp(2),0,0);labels.addView(sub);r.addView(labels,new LinearLayout.LayoutParams(0,-2,1));Switch toggle=new Switch(this);toggle.setChecked(prefs.getBoolean(key,defaultValue));toggle.setOnCheckedChangeListener((buttonView,isChecked)->{if("build_auto_apk".equals(key))BuildMonitor.setAutoDownloadEnabled(this,isChecked);else prefs.edit().putBoolean(key,isChecked).apply();});r.addView(toggle);card(r);}

    private void showPrivacyAndData(){
        rememberScreen("privacy_data",null);setCurrentScreen(this::showPrivacyAndData);shell("Конфиденциальность и данные",false);
        settingsPanelHeader("Ваши данные","Локальные данные и конфиденциальность",R.drawable.ic_lock);
        LinearLayout summary=new LinearLayout(this);summary.setOrientation(LinearLayout.VERTICAL);
        addProfileDetail(summary,"OAuth-токен",isSignedIn()?"Зашифрован Android Keystore":"Не сохранён");
        addProfileDetail(summary,"Кэш GitHub",entityCache.count()+" объектов");
        addProfileDetail(summary,"Кэш переводов",translationCache.count()+" документов");
        addProfileDetail(summary,"Наблюдение",trackingDb.statistics().optInt("developers",0)+" разработчиков");
        addProfileDetail(summary,"Офлайн-очередь",features.offlineQueue.listAll().size()+" действий");
        card(summary);
        TextView policy=text("GitHubble передаёт запросы только GitHub и выбранному пользователем переводчику. Пароль GitHub приложению недоступен. OAuth-токен и API-ключ переводчика не входят в экспорт данных.",13,false);policy.setTextColor(MUTED);card(policy);
        Button export=button("Экспортировать мои данные");export.setOnClickListener(v->startUserDataExport());content.addView(export,new LinearLayout.LayoutParams(-1,dp(52)));
        Button delete=button("Удалить все локальные данные");delete.setTextColor(Color.rgb(190,55,60));delete.setBackground(round(CARD,14,Color.rgb(190,55,60),1));delete.setOnClickListener(v->confirmDeleteAllLocalData());LinearLayout.LayoutParams dpv=new LinearLayout.LayoutParams(-1,dp(52));dpv.setMargins(0,dp(10),0,0);content.addView(delete,dpv);
    }

    private void startUserDataExport(){
        try{pendingUserDataExport=userDataManager.exportUserData().toString(2);Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("application/json");intent.putExtra(Intent.EXTRA_TITLE,"GitHubble-user-data-"+System.currentTimeMillis()+".json");startActivityForResult(intent,REQUEST_EXPORT_USER_DATA);}catch(Exception e){errorDialog("Не удалось подготовить экспорт",e.getMessage());}
    }

    private void confirmDeleteAllLocalData(){
        new AlertDialog.Builder(this).setTitle("Удалить все локальные данные?").setMessage("Будут удалены OAuth-токен, профиль, избранное, наблюдение, кэш, переводы, офлайн-очередь и настройки. На GitHub ничего не удаляется. Действие нельзя отменить.").setPositiveButton("Удалить",(d,w)->{userDataManager.deleteAllLocalData();TrackingJobService.cancel(this);Intent restart=getPackageManager().getLaunchIntentForPackage(getPackageName());if(restart!=null){restart.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);startActivity(restart);}finishAffinity();}).setNegativeButton("Отмена",null).show();
    }

    private void showReleaseCandidateStatus(){
        rememberScreen("rc_status",null);setCurrentScreen(this::showReleaseCandidateStatus);shell("Release Candidate",false);
        heroBanner("GitHubble "+BuildConfig.VERSION_NAME,"Безопасная синхронизация аккаунта и репозиториев",R.drawable.ic_tools);
        JSONObject release=ReleaseIntegrity.inspect(this);JSONObject perf=performanceMonitor.snapshot();JSONObject migrations=SchemaMigrationRegistry.matrix();
        LinearLayout info=new LinearLayout(this);info.setOrientation(LinearLayout.VERTICAL);
        addProfileDetail(info,"Версия",release.optString("version_name",BuildConfig.VERSION_NAME));
        addProfileDetail(info,"Канал",release.optString("channel",BuildConfig.RC_CHANNEL));
        addProfileDetail(info,"Отладочная сборка",release.optBoolean("debuggable",false)?"Да":"Нет");
        addProfileDetail(info,"Cold start",perf.optLong("cold_start_ms",-1)+" мс");
        addProfileDetail(info,"Java heap",compactBytes(perf.optLong("java_heap_used_bytes",0)));
        addProfileDetail(info,"Схема наблюдения",String.valueOf(migrations.optInt("tracking_schema",0)));
        addProfileDetail(info,"Схема офлайн-очереди",String.valueOf(migrations.optInt("offline_queue_schema",0)));
        card(info);
        TextView freeze=text("Версия "+BuildConfig.VERSION_NAME+" · канал "+BuildConfig.RC_CHANNEL+". Device Flow, account-scoped cache и безопасная офлайн-очередь.",13,false);freeze.setTextColor(MUTED);card(freeze);
    }

    private String compactBytes(long bytes){if(bytes<1024)return bytes+" Б";if(bytes<1024L*1024L)return String.format(Locale.ROOT,"%.1f КБ",bytes/1024.0);return String.format(Locale.ROOT,"%.1f МБ",bytes/(1024.0*1024.0));}

    private String translatorDisplayName(){TranslationEngine.Config c=translationConfig();if(c.endpoint.isEmpty())return "Не настроен";String host="";try{host=Uri.parse(c.endpoint).getHost();}catch(Exception ignored){}return TranslationEngine.providerLabel(c.provider)+(host==null||host.isEmpty()?"":" · "+host);}
    private void configureTranslatorSettings(){
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(18),0,dp(18),0);
        String[] providers={"Универсальный JSON","OpenAI-совместимый","LibreTranslate"};Spinner provider=new Spinner(this);provider.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,providers));String current=secureStore.get("translator_provider");provider.setSelection("openai".equals(current)?1:"libretranslate".equals(current)?2:0);form.addView(provider);
        EditText endpoint=new EditText(this);endpoint.setSingleLine(true);endpoint.setHint("Endpoint API");endpoint.setText(secureStore.get("translator_endpoint"));form.addView(endpoint);
        EditText apiKey=new EditText(this);apiKey.setSingleLine(true);apiKey.setHint("API-ключ (необязательно)");apiKey.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);apiKey.setText(secureStore.get("translator_api_key"));form.addView(apiKey);
        EditText model=new EditText(this);model.setSingleLine(true);model.setHint("Модель, например gpt-4.1-mini");model.setText(secureStore.get("translator_model"));form.addView(model);
        TextView help=text("Generic: {text,target} → translatedText. OpenAI: /v1/chat/completions. LibreTranslate: /translate. Endpoint, ключ и модель хранятся зашифрованно через Android Keystore.",11,false);help.setTextColor(MUTED);form.addView(help);
        new AlertDialog.Builder(this).setTitle("Провайдер перевода").setView(form).setPositiveButton("Сохранить",(d,w)->{String p=provider.getSelectedItemPosition()==1?"openai":provider.getSelectedItemPosition()==2?"libretranslate":"generic";secureStore.put("translator_provider",p);secureStore.put("translator_endpoint",endpoint.getText().toString().trim());secureStore.put("translator_api_key",apiKey.getText().toString().trim());secureStore.put("translator_model",model.getText().toString().trim());toast("Провайдер сохранён");if("Настройки".equals(currentTitle))showSettings();}).setNeutralButton("Очистить",(d,w)->{secureStore.remove("translator_provider");secureStore.remove("translator_endpoint");secureStore.remove("translator_api_key");secureStore.remove("translator_model");translationCache.clear();if("Настройки".equals(currentTitle))showSettings();}).setNegativeButton("Отмена",null).show();
    }

    private String cacheSizeText(){long bytes=0;for(Map.Entry<String,?> entry:prefs.getAll().entrySet()){String key=entry.getKey();if(key.startsWith("cache_")||key.startsWith("translation_")||key.equals("github_profile_json")){Object value=entry.getValue();if(value!=null)bytes+=value.toString().getBytes(StandardCharsets.UTF_8).length;}}File avatar=new File(getFilesDir(),"github_avatar.png");if(avatar.isFile())bytes+=avatar.length();bytes+=translationCache.sizeBytes()+entityCache.sizeBytes();return bytes<1024?bytes+" Б":bytes<1024*1024?String.format(Locale.ROOT,"%.1f КБ",bytes/1024.0):String.format(Locale.ROOT,"%.1f МБ",bytes/(1024.0*1024.0));}
    private void showCacheDialog(){new AlertDialog.Builder(this).setTitle("Офлайн-кэш").setMessage("Сохранены профили, репозитории, Issues, Pull Requests, Releases, README, переводы и аватар. Сущностей: "+entityCache.count()+", переводов: "+translationCache.count()+". Общий объём: "+cacheSizeText()+".").setPositiveButton("Очистить всё",(d,w)->clearOfflineCache()).setNeutralButton("Только переводы",(d,w)->{translationCache.clear();toast("Кэш переводов очищен");showSettings();}).setNegativeButton("Отмена",null).show();}
    private void clearOfflineCache(){SharedPreferences.Editor editor=prefs.edit();for(String key:prefs.getAll().keySet())if(key.startsWith("cache_")||key.startsWith("translation_")||key.equals("github_profile_json")||key.equals("github_avatar_url"))editor.remove(key);editor.apply();translationCache.clear();entityCache.clear();File avatar=new File(getFilesDir(),"github_avatar.png");if(avatar.isFile())avatar.delete();toast("Офлайн-кэш очищен");showSettings();}

    private void exportDiagnostics(){
        ProgressDialog progress=ProgressDialog.show(this,"Диагностика","Формируем безопасный ZIP…",true,false);
        io.execute(()->{try{DiagnosticsExporter.Result result=DiagnosticsExporter.export(this,prefs,entityCache,translationCache,trackingDb,api);runOnUiThread(()->{progress.dismiss();new AlertDialog.Builder(this).setTitle("Диагностика экспортирована").setMessage(result.displayName+" сохранён в Downloads/GitHubble. Токены, API-ключи, пароли и signing key не включены.").setPositiveButton("Поделиться",(d,w)->{try{Intent share=new Intent(Intent.ACTION_SEND);share.setType("application/zip");share.putExtra(Intent.EXTRA_STREAM,result.uri);share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(share,"Отправить диагностику"));}catch(Exception e){toast("Файл сохранён в Downloads/GitHubble");}}).setNegativeButton("Закрыть",null).show();});}catch(Exception e){runOnUiThread(()->{progress.dismiss();errorDialog("Не удалось экспортировать диагностику",e.getMessage());});}});
    }

    private void confirmSignOut(){new AlertDialog.Builder(this).setTitle("Выйти из GitHub?").setMessage("Токен, приватный кэш и отложенные действия этого аккаунта будут удалены. Локальное избранное останется.").setPositiveButton("Выйти",(d,w)->{String account=currentAccountId();repositoryStore.clear(account);RepositoryActivityFeed.clearVisits(prefs,account);prefs.edit().remove("home_recent_activity_" + account).remove("activity_feed_cache_"+account).remove("activity_feed_refresh_"+account).apply();features.offlineQueue.clearAccount(api.accountScope());api.clearSessionCaches();oauth.signOut();prefs.edit().remove("github_profile_json").remove("github_avatar_url").remove("home_open_issues").remove("home_open_prs").remove("home_dashboard_last_refresh").remove("repository_access_settings_url").putInt("activity_new_count",0).apply();File avatar=new File(getFilesDir(),"github_avatar.png");if(avatar.isFile())avatar.delete();openRoot(this::showSettings);}).setNegativeButton("Отмена",null).show();}

    private void migrateSecureSettings(){
        String legacy=prefs.getString("translator_endpoint","");
        if(!legacy.isEmpty()&&secureStore.get("translator_endpoint").isEmpty())secureStore.put("translator_endpoint",legacy);
        if(!legacy.isEmpty())prefs.edit().remove("translator_endpoint").apply();
    }

    private long parseIsoEpoch(String iso){
        if(iso==null||iso.isEmpty())return 0L;
        try{return java.time.Instant.parse(iso).toEpochMilli();}catch(Exception ignored){return 0L;}
    }

    private boolean isSignedIn(){return oauth!=null&&oauth.hasUsableToken();}
    private String currentAccountId(){return api.accountScope();}
    private void accountRequiredCard(String message){TextView note=text(message,14,false);note.setTextColor(MUTED);card(note);Button login=button("Войти через GitHub");login.setOnClickListener(v->navigate(this::showProfile));content.addView(login,new LinearLayout.LayoutParams(-1,dp(50)));}
    private void openWeb(String url){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(Exception e){toast("Не удалось открыть ссылку");}}
    private String formatGitHubDate(String iso){if(iso==null||iso.isEmpty())return "";try{java.time.Instant instant=java.time.Instant.parse(iso);java.time.format.DateTimeFormatter formatter=java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm",new Locale("ru")).withZone(java.time.ZoneId.systemDefault());return formatter.format(instant);}catch(Exception e){return iso.length()>=10?iso.substring(0,10):iso;}}
    private String isoNow(){return java.time.Instant.now().toString();}

    private void applyTheme(){
        activeTheme=prefs.getString("theme","light");
        if("beige".equals(activeTheme)){
            BG=Color.rgb(251,247,235); SURFACE=Color.rgb(255,252,243); CARD=Color.rgb(255,250,237); SUBTLE=Color.rgb(246,241,214);
            TEXT=Color.rgb(54,47,34); MUTED=Color.rgb(112,102,78); GREEN=Color.rgb(86,158,45); BLUE=Color.rgb(75,132,47); BORDER=Color.rgb(226,216,184);
            tileColors=new int[]{Color.rgb(91,164,65),Color.rgb(69,139,214),Color.rgb(129,86,190),Color.rgb(232,142,45),Color.rgb(223,92,92),Color.rgb(222,177,30),Color.rgb(52,157,151),Color.rgb(112,108,91)};
        }else if("dark".equals(activeTheme)){
            BG=Color.rgb(24,34,38); SURFACE=Color.rgb(29,42,46); CARD=Color.rgb(34,49,53); SUBTLE=Color.rgb(38,59,51);
            TEXT=Color.rgb(242,247,244); MUTED=Color.rgb(174,190,183); GREEN=Color.rgb(140,215,79); BLUE=Color.rgb(129,211,83); BORDER=Color.rgb(58,76,72);
            tileColors=new int[]{Color.rgb(45,125,63),Color.rgb(39,95,155),Color.rgb(99,65,148),Color.rgb(160,87,25),Color.rgb(151,56,67),Color.rgb(151,122,20),Color.rgb(34,115,119),Color.rgb(67,83,82)};
        }else{
            BG=Color.rgb(249,250,247); SURFACE=Color.rgb(255,255,255); CARD=Color.rgb(255,255,255); SUBTLE=Color.rgb(239,247,233);
            TEXT=Color.rgb(28,34,31); MUTED=Color.rgb(98,111,103); GREEN=Color.rgb(78,168,57); BLUE=Color.rgb(65,145,52); BORDER=Color.rgb(224,231,222);
            tileColors=new int[]{Color.rgb(76,170,83),Color.rgb(49,132,220),Color.rgb(126,76,196),Color.rgb(242,139,45),Color.rgb(226,83,94),Color.rgb(232,183,29),Color.rgb(46,158,164),Color.rgb(94,108,104)};
        }
        getWindow().setStatusBarColor(SURFACE); getWindow().setNavigationBarColor(SURFACE);
        if(Build.VERSION.SDK_INT>=23){ int flags=getWindow().getDecorView().getSystemUiVisibility(); if(!"dark".equals(activeTheme)) flags|=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR; else flags&=~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR; getWindow().getDecorView().setSystemUiVisibility(flags);}
    }

    private String themeDisplayName(){ return "beige".equals(activeTheme)?"Бежевая":"dark".equals(activeTheme)?"Тёмная":"Светлая"; }

    private void showThemeChooser(){
        setCurrentScreen(this::showThemeChooser);shell("Оформление",false);
        settingsPanelHeader("Тема оформления","Выберите цветовую схему GitHubble",R.drawable.ic_tools);
        String[] ids={"light","beige","dark"};
        String[] titles={"Светлая","Бежевая","Тёмная"};
        String[] descriptions={"Белая и зелёная","Тёплая, бежевая и зелёная","Графитовая и зелёная"};
        for(int i=0;i<ids.length;i++){
            final String theme=ids[i];
            nestedSettingsRow(titles[i]+(theme.equals(activeTheme)?"  ✓":""),
                    descriptions[i],R.drawable.ic_tools,theme.equals(activeTheme)?GREEN:0xFF7D6E9F,()->{
                        prefs.edit().putString("theme",theme).apply();
                        switchLauncherIcon(theme);
                        applyTheme();
                        showThemeChooser();
                    });
        }
    }

    private void showCardSizeChooser(){
        setCurrentScreen(this::showCardSizeChooser);shell("Размер карточек",false);
        settingsPanelHeader("Размер карточек",
                "Влияет на плитки и рекомендации главной страницы",R.drawable.ic_tools);
        String density=prefs.getString("ui_card_density","compact");
        nestedSettingsRow("Компактный"+("compact".equals(density)?"  ✓":""),
                "Больше информации на одном экране",R.drawable.ic_repository,GREEN,()->{
                    prefs.edit().putString("ui_card_density","compact").apply();showCardSizeChooser();
                });
        nestedSettingsRow("Свободный"+("spacious".equals(density)?"  ✓":""),
                "Увеличенные плитки и карточки рекомендаций",R.drawable.ic_repository,GREEN,()->{
                    prefs.edit().putString("ui_card_density","spacious").apply();showCardSizeChooser();
                });
        TextView hint=text("Изменение применяется при следующем открытии главной страницы.",11,false);
        hint.setTextColor(MUTED);card(hint);
    }

    private ComponentName launcherAliasComponent(String suffix){
        String activityClass=MainActivity.class.getName();
        int dot=activityClass.lastIndexOf('.');
        String namespace=dot>=0?activityClass.substring(0,dot):activityClass;
        return new ComponentName(getPackageName(),namespace+"."+suffix);
    }

    private void switchLauncherIcon(String theme){
        PackageManager pm=getPackageManager();
        ComponentName[] aliases={
            launcherAliasComponent("LightIcon"),
            launcherAliasComponent("BeigeIcon"),
            launcherAliasComponent("DarkIcon")
        };
        int selectedIndex="beige".equals(theme)?1:"dark".equals(theme)?2:0;
        for(int i=0;i<aliases.length;i++){
            try{
                pm.setComponentEnabledSetting(
                    aliases[i],
                    i==selectedIndex?PackageManager.COMPONENT_ENABLED_STATE_ENABLED:PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                );
            }catch(IllegalArgumentException ignored){
                // Never crash the app if a build variant changes launcher aliases.
            }
        }
    }

    /**
     * One-time migration from the post-v27 adaptive icon resources back to the
     * pre-v27 full-colour smiling-cat icons. Cycling through another alias forces
     * MIUI/HyperOS launchers to invalidate the previously cached black icon.
     */
    private void restoreLegacyThemeIconAfterUpgrade(){
        final String migrationKey="legacy_theme_icons_restored_v31";
        if(prefs.getBoolean(migrationKey,false)) return;

        PackageManager pm=getPackageManager();
        ComponentName[] aliases={
            launcherAliasComponent("LightIcon"),
            launcherAliasComponent("BeigeIcon"),
            launcherAliasComponent("DarkIcon")
        };
        int selectedIndex="beige".equals(activeTheme)?1:"dark".equals(activeTheme)?2:0;
        int temporaryIndex=(selectedIndex+1)%aliases.length;

        try{
            // Enable a temporary launcher component first so there is always one icon.
            pm.setComponentEnabledSetting(
                aliases[temporaryIndex],
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            );
            pm.setComponentEnabledSetting(
                aliases[selectedIndex],
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            );
            prefs.edit().putBoolean(migrationKey,true).apply();
            new Handler(Looper.getMainLooper()).postDelayed(() -> switchLauncherIcon(activeTheme),350L);
        }catch(IllegalArgumentException error){
            // Launcher alias migration is cosmetic and must never prevent startup.
            prefs.edit().putBoolean(migrationKey,true).apply();
        }
    }

    private String effectiveGitHubClientId(){
        return BuildConfig.GITHUB_CLIENT_ID == null ? "" : BuildConfig.GITHUB_CLIENT_ID.trim();
    }

    private void startSmartLogin(){
        String clientId=effectiveGitHubClientId();
        if(clientId.isEmpty()){
            errorDialog("Вход недоступен","Client ID приложения GiHubble не настроен в этой сборке.");
            return;
        }
        startGitHubDeviceFlow(clientId);
    }

    private void startGitHubDeviceFlow(String clientId){
        final ProgressDialog progress=new ProgressDialog(this);
        progress.setTitle("Подготовка входа");
        progress.setMessage("Получаем одноразовый код у GitHub…");
        progress.setCancelable(false);
        progress.show();
        io.execute(() -> {
            try{
                OAuthManager.DeviceSession session=oauth.beginDeviceFlow(clientId,"");
                runOnUiThread(() -> {
                    progress.dismiss();
                    showDeviceLogin(session,clientId);
                });
            }catch(Exception e){
                runOnUiThread(() -> {
                    progress.dismiss();
                    new AlertDialog.Builder(this)
                        .setTitle("Не удалось начать вход")
                        .setMessage((e instanceof java.net.SocketTimeoutException || e instanceof java.net.ConnectException || e instanceof java.net.UnknownHostException)
                            ? "Не удалось подключиться к GitHub. Проверьте интернет, VPN и разрешение доступа к сети для GitHubble.\n\n"+e.getMessage()
                            : e.getMessage()+"\n\nУбедитесь, что у GitHub App GiHubble включён Device Flow.")
                        .setNegativeButton("Закрыть",null)
                        .show();
                });
            }
        });
    }

    private void showDeviceLogin(OAuthManager.DeviceSession session,String clientId){
        final AtomicBoolean cancelled=new AtomicBoolean(false);
        final String pasteCode=normalizeGitHubDeviceCode(session.userCode);
        DeviceCodeAssist.begin(this,session.userCode,
            session.startedAtMillis + session.expiresInSeconds * 1000L);
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18),dp(2),dp(18),dp(2));

        TextView explanation=text("Войдите на официальной странице GitHub любым доступным способом. GitHubble не получает ваш пароль.",14,false);
        explanation.setTextColor(MUTED);
        explanation.setPadding(dp(2),dp(2),dp(2),dp(2));
        box.addView(explanation);

        TextView code=text(session.userCode,28,true);
        code.setGravity(Gravity.CENTER);
        code.setTextColor(GREEN);
        code.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        code.setLetterSpacing(0.06f);
        code.setMinHeight(dp(72));
        code.setPadding(dp(12),dp(14),dp(12),dp(14));
        code.setBackground(round(CARD,14,BORDER,1));
        LinearLayout.LayoutParams codeParams=new LinearLayout.LayoutParams(-1,dp(72));
        codeParams.setMargins(dp(8),dp(12),dp(8),dp(12));
        box.addView(code,codeParams);

        TextView hint=text("Код целиком скопирован. На странице GitHub удерживайте первое поле и выберите «Вставить». Если вставка недоступна, воспользуйтесь кнопкой «Скопировать код» в этом окне. Автозаполнение через Android можно включить отдельно.",12,false);
        hint.setTextColor(MUTED);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(2),dp(2),dp(2),dp(2));
        box.addView(hint);

        TextView status=text("Ожидаем подтверждение…",13,true);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0,dp(8),0,dp(4));
        box.addView(status);

        LinearLayout actions=new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0,dp(4),0,0);

        Button confirm=new Button(this);
        confirm.setText("Подтверждение");
        confirm.setAllCaps(false);
        confirm.setTextSize(11);
        confirm.setSingleLine(true);
        confirm.setMinWidth(0);
        confirm.setMinimumWidth(0);
        confirm.setPadding(dp(2),0,dp(2),0);

        Button cancel=new Button(this);
        cancel.setText("Отмена");
        cancel.setAllCaps(false);
        cancel.setTextSize(11);
        cancel.setSingleLine(true);
        cancel.setMinWidth(0);
        cancel.setMinimumWidth(0);
        cancel.setPadding(dp(2),0,dp(2),0);

        Button copy=new Button(this);
        copy.setText("Скопировать код");
        copy.setAllCaps(false);
        copy.setTextSize(11);
        copy.setSingleLine(true);
        copy.setMinWidth(0);
        copy.setMinimumWidth(0);
        copy.setPadding(dp(2),0,dp(2),0);

        LinearLayout.LayoutParams actionParams=new LinearLayout.LayoutParams(0,dp(44),1f);
        actions.addView(confirm,new LinearLayout.LayoutParams(actionParams));
        actions.addView(cancel,new LinearLayout.LayoutParams(actionParams));
        actions.addView(copy,new LinearLayout.LayoutParams(actionParams));
        box.addView(actions,new LinearLayout.LayoutParams(-1,dp(48)));

        TextView manualCodeHint=text("Скопируйте восьмизначный код и вставьте его на официальной странице GitHub.",12,false);
        manualCodeHint.setTextColor(MUTED);
        box.addView(manualCodeHint);

        copyGitHubDeviceCode(session,false);
        code.setOnClickListener(v -> copyGitHubDeviceCode(session,true));

        AlertDialog dialog=new AlertDialog.Builder(this)
            .setTitle("Войти через GitHub")
            .setView(box)
            .create();

        confirm.setOnClickListener(v -> {
            copyGitHubDeviceCode(session,false);
            openOfficialGitHubLogin(session.verificationUri);
        });
        copy.setOnClickListener(v -> copyGitHubDeviceCode(session,true));
        cancel.setOnClickListener(v -> {
            cancelled.set(true);
            DeviceCodeAssist.clear(this);
            dialog.dismiss();
        });
        dialog.setOnCancelListener(x -> {
            cancelled.set(true);
            DeviceCodeAssist.clear(this);
        });
        dialog.show();
        if(dialog.getWindow()!=null){
            int maxDialogWidth=dp(430);
            int availableWidth=getResources().getDisplayMetrics().widthPixels-dp(32);
            dialog.getWindow().setLayout(Math.min(maxDialogWidth,availableWidth),android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }
        copyGitHubDeviceCode(session,false);
        openOfficialGitHubLogin(session.verificationUri);
        pollDeviceAuthorization(session,clientId,status,dialog,cancelled);
    }

    private String normalizeGitHubDeviceCode(String value){return DeviceCodeFormatter.normalize(value);}

    private void copyGitHubDeviceCode(OAuthManager.DeviceSession session,boolean showMessage){
        boolean copied=DeviceCodeAssist.copy(this);
        if(showMessage)toast(copied
            ? "Скопированы все 8 символов. Удерживайте первое поле → Вставить."
            : "Код GitHub истёк или буфер обмена недоступен");
    }

    private void startGitHubRegistration(){
        registrationCustomTabActive=true;
        registrationCustomTabWasPaused=false;
        prefs.edit()
            .putBoolean(PREF_REGISTRATION_PENDING,true)
            .putLong(PREF_REGISTRATION_LAUNCHED_AT,System.currentTimeMillis())
            .apply();
        try{
            boolean customTab=GitHubCustomTabs.open(this,Uri.parse("https://github.com/signup"),GREEN,"dark".equals(activeTheme));
            if(!customTab)toast("Chrome Custom Tab недоступен — открыта официальная страница GitHub в браузере");
        }catch(Exception e){
            registrationCustomTabActive=false;
            registrationCustomTabWasPaused=false;
            prefs.edit().putBoolean(PREF_REGISTRATION_PENDING,false).apply();
            toast("Не удалось открыть регистрацию GitHub: "+e.getMessage());
        }
    }

    private void showRegistrationReturnDialog(){
        if(registrationReturnDialogVisible||isFinishing()||isDestroyed())return;
        registrationReturnDialogVisible=true;
        AlertDialog dialog=new AlertDialog.Builder(this)
            .setTitle("Регистрация GitHub")
            .setMessage("Аккаунт уже создан? Продолжите подключение GiHubble. GitHub откроет официальный Device Flow, а GitHubble автоматически получит токен после подтверждения.")
            .setPositiveButton("Продолжить подключение",(d,w)->startSmartLogin())
            .setNeutralButton("Открыть регистрацию снова",(d,w)->startGitHubRegistration())
            .setNegativeButton("Позже",null)
            .create();
        dialog.setOnDismissListener(d->registrationReturnDialogVisible=false);
        dialog.show();
    }

    private void openOfficialGitHubLogin(String verificationUri){
        try{
            boolean customTab=GitHubCustomTabs.open(this,Uri.parse(verificationUri),
                GREEN,"dark".equals(activeTheme));
            if(!customTab)toast("Открыта официальная страница GitHub в браузере. Код скопирован; используйте обычную вставку.");
        }catch(Exception e){toast("Не удалось открыть подтверждение GitHub: "+e.getMessage());}
    }

    private void pollDeviceAuthorization(OAuthManager.DeviceSession session,String clientId,TextView status,AlertDialog dialog,AtomicBoolean cancelled){
        io.execute(() -> {
            int interval=session.intervalSeconds;
            while(!cancelled.get()&&!session.isExpired()){
                try{Thread.sleep(interval*1000L);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}
                if(cancelled.get())return;
                try{
                    OAuthManager.PollResult result=oauth.pollDeviceFlow(clientId,session,interval);
                    interval=result.nextIntervalSeconds;
                    OAuthManager.PollState state=result.state;
                    runOnUiThread(() -> {if(dialog.isShowing())status.setText(result.message);});
                    if(state==OAuthManager.PollState.AUTHORIZED){
                        DeviceCodeAssist.clear(this);
                        runOnUiThread(() -> {
                            if(dialog.isShowing())dialog.dismiss();
                            loadAndStoreGitHubProfile();
                            openRoot(this::showProfile);
                            toast("GitHub успешно подключён");
                            returnToGitHubbleAfterAuthorization();
                        });
                        return;
                    }
                    if(state==OAuthManager.PollState.DENIED||state==OAuthManager.PollState.EXPIRED||state==OAuthManager.PollState.ERROR){
                        DeviceCodeAssist.clear(this);
                        runOnUiThread(() -> {
                            if(dialog.isShowing())dialog.dismiss();
                            new AlertDialog.Builder(this).setTitle("Вход не завершён").setMessage(result.message).setPositiveButton("ОК",null).show();
                        });
                        return;
                    }
                }catch(Exception e){
                    runOnUiThread(() -> {if(dialog.isShowing())status.setText("Проблема сети. Повторяем…");});
                }
            }
            DeviceCodeAssist.clear(this);
            if(!cancelled.get())runOnUiThread(() -> {if(dialog.isShowing())dialog.dismiss();toast("Срок действия кода GitHub истёк");});
        });
    }

    private void returnToGitHubbleAfterAuthorization(){
        prefs.edit().putBoolean(PREF_AUTH_JUST_COMPLETED, true).apply();
        Intent intent=new Intent(this,MainActivity.class);
        intent.putExtra(EXTRA_OPEN_PROFILE_AFTER_AUTH,true);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try{startActivity(intent);}catch(Exception ignored){}
        try{
            ActivityManager manager=(ActivityManager)getSystemService(ACTIVITY_SERVICE);
            if(manager!=null)manager.moveTaskToFront(getTaskId(),ActivityManager.MOVE_TASK_WITH_HOME);
        }catch(Exception ignored){}
    }

    private void loadAndStoreGitHubProfile(){
        io.execute(() -> {
            try{
                JSONObject user=api.getJson("https://api.github.com/user");
                cacheGitHubProfile(user);
                downloadAvatar(user.optString("avatar_url",""));
                refreshNotificationCount();
                runOnUiThread(() -> {if("Профиль".equals(currentTitle))renderProfilePage(false);});
            }catch(Exception ignored){}
        });
    }

    private void handleIntent(Intent i){
        if(i != null && BuildMonitor.OPEN_ACTION.equals(i.getAction())){
            i.setAction(null);
            openRoot(this::showBuildCenter);
            return;
        }
        if(i != null && "app.githubble.mobile.OPEN_TRACKING".equals(i.getAction())) {
            i.setAction(null);
            openRoot(this::showTracking);
            return;
        }
        if(i != null && "app.githubble.mobile.OPEN_COPILOT_TASK".equals(i.getAction())) {
            String task=i.getStringExtra("copilot_task_id");
            String full=i.getStringExtra("copilot_repository");
            i.setAction(null);
            if(task!=null&&!task.isEmpty())openRoot(()->showCopilotTaskDetail(full==null?"":full,task));
            else openRoot(this::showCopilotTasks);
        }
    }

    @Override protected void onSaveInstanceState(Bundle outState){
        if(currentScroll!=null&&screenState!=null)screenState.setScroll(currentScroll.getScrollY());
        if(screenState!=null)screenState.save(outState);
        featureState.save(outState);
        super.onSaveInstanceState(outState);
    }

    private void rememberScreen(String route, JSONObject args){
        if(screenState!=null)screenState.remember(route,args==null?new JSONObject():args);
    }

    private JSONObject routeArgs(Object... pairs){
        JSONObject value=new JSONObject();
        try{for(int i=0;i+1<pairs.length;i+=2)value.put(String.valueOf(pairs[i]),pairs[i+1]);}catch(Exception ignored){}
        return value;
    }

    private void restoreRepositoryAdmin(String full){if(full==null||full.isEmpty()){showHome();return;}ProgressDialog p=ProgressDialog.show(this,"Управление","Восстанавливаем экран…",true,false);io.execute(()->{try{JSONObject repo=getJson("https://api.github.com/repos/"+full);runOnUiThread(()->{p.dismiss();showRepositoryAdmin(full,repo);});}catch(Exception e){runOnUiThread(()->{p.dismiss();showRepository(full);});}});}

    private void restorePullChecks(String full,int number){if(full==null||full.isEmpty()||number<=0){showHome();return;}ProgressDialog p=ProgressDialog.show(this,"Проверки","Восстанавливаем экран…",true,false);io.execute(()->{try{JSONObject pull=getJson("https://api.github.com/repos/"+full+"/pulls/"+number);runOnUiThread(()->{p.dismiss();showPullChecks(full,number,pull);});}catch(Exception e){runOnUiThread(()->{p.dismiss();showPullDetail(full,number);});}});}

    private void restoreScreenFromState(){
        if(screenState==null)return;
        JSONObject a=screenState.args();String route=screenState.route();
        switch(route){
            case "search": showSearch(); break;
            case "profile": showProfile(); break;
            case "profile_editor": showNativeProfileEditor(); break;
            case "settings": showSettings(); break;
            case "privacy_data": showPrivacyAndData(); break;
            case "rc_status": showReleaseCandidateStatus(); break;
            case "tracking": showTracking(); break;
            case "repository": showRepository(a.optString("full")); break;
            case "repo_issues": showRepoIssues(a.optString("full")); break;
            case "repo_pulls": showRepoPulls(a.optString("full")); break;
            case "repo_releases": showRepoReleases(a.optString("full")); break;
            case "issue": showIssueDetail(a.optString("full"),a.optInt("number")); break;
            case "pull": showPullDetail(a.optString("full"),a.optInt("number")); break;
            case "commit": showCommitDetail(a.optString("full"),a.optString("sha")); break;
            case "pull_checks": restorePullChecks(a.optString("full"),a.optInt("number")); break;
            case "repository_admin": restoreRepositoryAdmin(a.optString("full")); break;
            case "copilot_repo": showCopilotRepository(a.optString("full")); break;
            case "copilot_tasks": showCopilotTasksPage(1,true,a.optString("states",""),a.optBoolean("archived",false)); break;
            case "copilot_task": showCopilotTaskDetail(a.optString("full",""),a.optString("task","")); break;
            case "copilot_agents": showCopilotCustomAgents(a.optString("full",""),a.optString("branch","main")); break;
            case "copilot_permissions": showCopilotPermissionMatrix(a.optString("full","")); break;
            case "offline_queue": showOfflineQueue(); break;
            case "portable_backup": showPortableBackup(); break;
            case "developer": showDeveloperProfile(a.optString("login")); break;
            default: showHome(); break;
        }
    }

    @Override protected void onDestroy(){
        stopCopilotForegroundPolling();
        accountInventoryCancelled.set(true);
        if(accountInventoryFuture!=null)accountInventoryFuture.cancel(true);
        inventoryIo.shutdownNow();
        io.shutdownNow();
        try{if(translationCache!=null)translationCache.close();}catch(Exception ignored){}
        try{if(trackingDb!=null)trackingDb.close();}catch(Exception ignored){}
        try{if(entityCache!=null)entityCache.close();}catch(Exception ignored){}
        try{if(features!=null&&features.offlineQueue!=null)features.offlineQueue.close();}catch(Exception ignored){}
        super.onDestroy();
    }

    private JSONArray getArray(String url)throws Exception{return api.getArray(url);}
    private String getText(String url)throws Exception{return api.getText(url);}
    private JSONObject getJson(String url)throws Exception{return api.getJson(url);}
    private static String readAll(InputStream in)throws Exception{ByteArrayOutputStream o=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))>0)o.write(b,0,n);return o.toString("UTF-8");}
    private static String limit(String s,int n){return s.length()>n?s.substring(0,n)+"\n…":s;}
    private void errorDialog(String title,String message){new AlertDialog.Builder(this).setTitle(title).setMessage(message==null?"Неизвестная ошибка":message).setPositiveButton("ОК",null).show();}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
}
