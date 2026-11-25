package app.githubble.mobile;

import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import app.githubble.mobile.network.GitHubApiClient;
import app.githubble.mobile.ui.SheetDialog;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * "Бюджеты и расходы": official GitHub usage for the month with the free allowance, local GitHubble limits,
 * and full budget management for organizations (the only place GitHub exposes budgets through its REST API).
 * Personal-account budgets exist on github.com only, so those get a precise deep link instead of a fake editor.
 */
final class BudgetScreens {
    static final String BUDGETS_PAGE = "https://github.com/settings/billing/budgets";
    private static final int AMBER = 0xFFE0A100, RED = 0xFFC34F48;
    private static final String[][] PRODUCTS = {
        {"Actions", "actions"}, {"Packages", "packages"}, {"Codespaces", "codespaces"},
        {"Copilot", "copilot"}, {"Git LFS", "git_lfs"}};

    private final MainActivity a;

    BudgetScreens(MainActivity activity) { a = activity; }

    private static String money(double value) { return "$" + String.format(Locale.US, "%.2f", value); }

    private static String number(double value) {
        double shown = Math.abs(value) >= 100 ? Math.rint(value) : Math.rint(value * 10d) / 10d;
        String text = shown == Math.rint(shown) ? String.format(Locale.US, "%,d", (long) shown) : String.format(Locale.US, "%,.1f", shown);
        return text.replace(',', ' ').replace('.', ',');
    }

    private static String unit(String raw) {
        String u = raw == null ? "" : raw.toLowerCase(Locale.ROOT).replace(" ", "").replace("-", "");
        if (u.startsWith("min")) return "мин";
        if (u.contains("gigabytehour")) return "ГБ·ч";
        if (u.contains("gigabytemonth")) return "ГБ·мес";
        if (u.startsWith("gigabyte") || u.equals("gb")) return "ГБ";
        if (u.startsWith("request")) return "запр.";
        if (u.startsWith("seat") || u.startsWith("user") || u.startsWith("licen")) return "лиц.";
        return raw == null ? "" : raw;
    }

    static boolean offline(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof java.net.UnknownHostException || t instanceof java.net.ConnectException
                    || t instanceof java.net.SocketTimeoutException || t instanceof javax.net.ssl.SSLException) return true;
            String message = t.getMessage();
            if (message != null && (message.contains("Unable to resolve host") || message.contains("Failed to connect"))) return true;
        }
        return false;
    }

    /** Network failures (DNS, VPN switch, weak signal) are retried twice with a short pause before anything is shown. */
    private static <T> T retry(java.util.concurrent.Callable<T> call) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try { return call.call(); }
            catch (Exception error) {
                if (!offline(error) || attempt >= 2) throw error;
                try { Thread.sleep(1500L * (attempt + 1)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw error; }
            }
        }
    }

    private static int allowanceMinutes(String plan) {
        String p = plan == null ? "" : plan.toLowerCase(Locale.ROOT);
        if (p.contains("enterprise")) return 50000;
        if (p.contains("team") || p.contains("pro")) return 3000;
        return 2000;
    }

    private int level(double used, double limit) {
        if (limit <= 0) return a.GREEN;
        double share = used / limit;
        return share >= 1 ? RED : share >= .8 ? AMBER : a.GREEN;
    }

    private LinearLayout line(String title, String sub, String right, int rightColor) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, a.dp(7), 0, a.dp(7));
        LinearLayout labels = new LinearLayout(a);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView name = a.text(title, 14, true);
        labels.addView(name);
        if (sub != null && !sub.isEmpty()) {
            TextView small = a.text(sub, 11, false);
            small.setTextColor(a.MUTED);
            labels.addView(small);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = a.text(right, 14, true);
        value.setTextColor(rightColor);
        value.setGravity(Gravity.END);
        row.addView(value, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private ProgressBar gauge(double used, double limit) { return gauge(used, limit, level(used, limit)); }

    private ProgressBar gauge(double used, double limit, int color) {
        ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        int percent = limit <= 0 ? 0 : (int) Math.min(100, Math.round(used * 100d / limit));
        a.styleBillingProgress(bar, percent, color);
        return bar;
    }

    private void addGauge(LinearLayout card, String title, double used, double limit, String unit) {
        String right = limit > 0 ? number(used) + " / " + number(limit) + " " + unit : number(used) + " " + unit;
        card.addView(line(title, limit > 0 ? Math.round(used * 100d / limit) + "% лимита" : "лимит не задан", right, level(used, limit)));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, a.dp(8));
        p.setMargins(0, 0, 0, a.dp(6));
        card.addView(gauge(used, limit), p);
    }

    private String friendly(Exception error) {
        if (offline(error)) return "Нет связи с GitHub. Проверьте интернет или VPN и нажмите «Повторить».";
        String text = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        if (error instanceof GitHubApiClient.ApiException) {
            int code = ((GitHubApiClient.ApiException) error).statusCode;
            if (code == 403 || code == 404)
                return "Нет доступа (HTTP " + code + "). Нужна роль владельца или billing manager организации и право GitHub App «Administration: write» в этой организации.";
        }
        return text;
    }

    void show() {
        LocalDate now = LocalDate.now(ZoneOffset.UTC);
        show(now.getYear(), now.getMonthValue());
    }

    void show(final int year, final int month) {
        a.setCurrentScreen(() -> show(year, month));
        a.shell("Бюджеты и расходы", false);
        a.settingsPanelHeader("Бюджеты и расходы", "Расходы GitHub, лимиты и бюджеты организаций", R.drawable.ic_settings);
        if (!a.isSignedIn()) { a.accountRequiredCard("Подключите GitHub для просмотра расходов."); return; }
        final long generation = a.screenGeneration.get();
        final String account = a.currentAccountId();
        final LinearLayout host = a.content;
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        final boolean current = year == today.getYear() && month == today.getMonthValue();

        // Period switcher
        LinearLayout nav = new LinearLayout(a);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        Button prev = a.button("‹");
        Button next = a.button("›");
        next.setEnabled(!current);
        TextView label = a.text(year + "-" + String.format(Locale.US, "%02d", month) + " UTC", 15, true);
        label.setGravity(Gravity.CENTER);
        nav.addView(prev, new LinearLayout.LayoutParams(a.dp(56), a.dp(44)));
        nav.addView(label, new LinearLayout.LayoutParams(0, a.dp(44), 1));
        nav.addView(next, new LinearLayout.LayoutParams(a.dp(56), a.dp(44)));
        LocalDate before = LocalDate.of(year, month, 1).minusMonths(1), after = LocalDate.of(year, month, 1).plusMonths(1);
        prev.setOnClickListener(v -> show(before.getYear(), before.getMonthValue()));
        next.setOnClickListener(v -> show(after.getYear(), after.getMonthValue()));
        a.content.addView(nav);

        // Usage card (filled asynchronously)
        a.sectionTitle("Расходы за месяц", "Официальные данные GitHub Billing");
        final LinearLayout usage = a.billingSurface();
        final TextView usageState = a.text("Загружаем отчёт GitHub…", 12, false);
        usageState.setTextColor(a.MUTED);
        usage.addView(usageState);
        a.card(usage);

        // Local limits
        a.sectionTitle("Лимиты GitHubble", "Следят за расходами и предупреждают; сам счёт GitHub они не останавливают");
        final LinearLayout limits = a.billingSurface();
        final EditText moneyField = new EditText(a), minutesField = new EditText(a);
        moneyField.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        minutesField.setInputType(InputType.TYPE_CLASS_NUMBER);
        moneyField.setHint("Бюджет, $ в месяц (пусто — выключен)");
        minutesField.setHint("Минуты Actions в месяц (пусто — выключен)");
        for (EditText field : new EditText[]{moneyField, minutesField}) {
            field.setTextColor(a.TEXT);
            field.setHintTextColor(a.MUTED);
            field.setSingleLine(true);
        }
        final String moneyKey = a.billingCacheKey("budget_money"), minutesKey = a.billingCacheKey("budget_minutes");
        float savedMoney = a.prefs.getFloat(moneyKey, 0f);
        int savedMinutes = a.prefs.getInt(minutesKey, 0);
        if (savedMoney > 0) moneyField.setText(savedMoney == Math.rint(savedMoney) ? String.valueOf((long) savedMoney) : String.valueOf(savedMoney));
        if (savedMinutes > 0) minutesField.setText(String.valueOf(savedMinutes));
        limits.addView(moneyField);
        limits.addView(minutesField);
        Button save = a.button("Сохранить лимиты");
        save.setOnClickListener(v -> {
            try {
                String m = moneyField.getText().toString().trim().replace(',', '.'), t = minutesField.getText().toString().trim();
                float money = m.isEmpty() ? 0f : Float.parseFloat(m);
                int minutes = t.isEmpty() ? 0 : Integer.parseInt(t);
                if (money < 0 || minutes < 0) throw new NumberFormatException();
                a.prefs.edit().putFloat(moneyKey, money).putInt(minutesKey, minutes).apply();
                a.toast("Лимиты сохранены");
                show(year, month);
            } catch (NumberFormatException bad) { a.toast("Введите неотрицательные числа"); }
        });
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(-1, a.dp(46));
        saveParams.topMargin = a.dp(8);
        limits.addView(save, saveParams);
        final LinearLayout gauges = new LinearLayout(a);
        gauges.setOrientation(LinearLayout.VERTICAL);
        limits.addView(gauges);
        a.card(limits);

        // Organizations
        a.sectionTitle("Бюджеты GitHub организаций", "Просмотр и изменение прямо здесь");
        final LinearLayout orgs = a.billingSurface();
        final TextView orgState = a.text("Ищем организации, где вы администратор…", 12, false);
        orgState.setTextColor(a.MUTED);
        orgs.addView(orgState);
        a.card(orgs);

        // Personal account: the web page is the only place
        a.sectionTitle("Личный аккаунт", null);
        TextView note = a.text("Для личных аккаунтов GitHub не отдаёт бюджеты через API: лимит расходов («Budgets and alerts») меняется только на github.com. Кнопка откроет именно эту страницу.", 12, false);
        note.setTextColor(a.MUTED);
        note.setPadding(a.dp(2), 0, a.dp(2), a.dp(6));
        a.content.addView(note);
        a.settingsActionRow("Budgets and alerts", "Открыть на GitHub", () -> a.openSecureGitHubPage(BUDGETS_PAGE));
        a.settingsActionRow("Billing & payment", "Платёжные данные и план", () -> a.openSecureGitHubPage("https://github.com/settings/billing"));

        final String cacheKey = a.billingCacheKey("usage_" + year + "_" + month);
        a.io.execute(() -> {
            JSONObject data = null;
            Exception failure = null;
            try { data = fetchUsage(year, month); a.prefs.edit().putString(cacheKey, data.toString()).apply(); }
            catch (Exception error) {
                failure = error;
                try { String saved = a.prefs.getString(cacheKey, ""); if (!saved.isEmpty()) data = new JSONObject(saved).put("stale", true); }
                catch (Exception ignored) { data = null; }
            }
            final JSONObject shown = data;
            final Exception problem = failure;
            a.runOnUiThread(() -> {
                if (generation != a.screenGeneration.get() || host != a.content || !account.equals(a.currentAccountId())) return;
                usage.removeAllViews();
                if (shown == null) {
                    TextView text = a.text("Отчёт недоступен: " + (problem == null ? "нет данных" : friendly(problem)), 12, false);
                    text.setTextColor(problem != null && offline(problem) ? AMBER : RED);
                    usage.addView(text);
                    Button again = a.button("Повторить");
                    again.setOnClickListener(v -> show(year, month));
                    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, a.dp(44));
                    p.topMargin = a.dp(8);
                    usage.addView(again, p);
                    return;
                }
                try { renderUsage(usage, gauges, shown, problem, moneyKey, minutesKey, () -> show(year, month)); }
                catch (Exception broken) { usage.addView(a.text("Отчёт не разобран: " + broken.getMessage(), 12, false)); }
            });
        });
        a.io.execute(() -> loadOrganizations(generation, host, account, orgs, orgState));
    }

    /** One consistent snapshot: user plan, month summary by SKU and (when GitHub allows) per-repository gross. */
    private JSONObject fetchUsage(int year, int month) throws Exception {
        JSONObject profile = retry(() -> a.api.getJsonFresh("https://api.github.com/user"));
        final String login = profile.optString("login", "");
        if (!login.matches("[A-Za-z0-9-]+")) throw new IllegalStateException("Не удалось определить аккаунт GitHub");
        JSONObject planJson = profile.optJSONObject("plan");
        final String base = "https://api.github.com/users/" + login + "/settings/billing/usage";
        JSONObject summary = retry(() -> a.api.getJson(base + "/summary?year=" + year + "&month=" + month));
        if (summary.optJSONArray("usageItems") == null) throw new IllegalStateException("GitHub Billing API не вернул usageItems");
        JSONObject out = new JSONObject().put("plan", planJson == null ? "" : planJson.optString("name", ""))
                .put("summary", summary).put("at", System.currentTimeMillis());
        try {
            JSONObject detail = retry(() -> a.api.getJson(base + "?year=" + year + "&month=" + month));
            JSONArray rows = detail.optJSONArray("usageItems");
            Map<String, double[]> perRepo = new LinkedHashMap<>();
            for (int i = 0; rows != null && i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                String repo = row.optString("repositoryName", "");
                if (repo.isEmpty()) repo = "без репозитория";
                double[] sum = perRepo.get(repo);
                if (sum == null) { sum = new double[2]; perRepo.put(repo, sum); }
                sum[0] += row.optDouble("grossAmount", 0);
                sum[1] += row.optDouble("netAmount", 0);
            }
            JSONArray list = new JSONArray();
            for (Map.Entry<String, double[]> e : perRepo.entrySet())
                list.put(new JSONObject().put("repo", e.getKey()).put("gross", e.getValue()[0]).put("net", e.getValue()[1]));
            out.put("repos", list);
        } catch (Exception detailFailure) { out.put("reposError", friendly(detailFailure)); }
        return out;
    }

    private void renderUsage(LinearLayout usage, LinearLayout gauges, JSONObject data, Exception problem,
                             String moneyKey, String minutesKey, Runnable reload) throws Exception {
        String plan = data.optString("plan", "");
        JSONArray items = data.getJSONObject("summary").getJSONArray("usageItems");
        Map<String, double[]> byProduct = new LinkedHashMap<>();
        Map<String, List<String>> skuLines = new LinkedHashMap<>();
        double gross = 0, discount = 0, net = 0, minutes = 0, freeMinutes = 0, billedMinutes = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String product = item.optString("product", "?"), sku = item.optString("sku", ""), unitName = unit(item.optString("unitType", ""));
            double g = item.optDouble("grossAmount", 0), d = item.optDouble("discountAmount", 0), n = item.optDouble("netAmount", 0);
            double q = item.optDouble("grossQuantity", 0), free = item.optDouble("discountQuantity", 0), billed = item.optDouble("netQuantity", 0);
            double[] sum = byProduct.get(product);
            if (sum == null) { sum = new double[3]; byProduct.put(product, sum); skuLines.put(product, new ArrayList<>()); }
            sum[0] += g; sum[1] += d; sum[2] += n;
            gross += g; discount += d; net += n;
            if ("Actions".equalsIgnoreCase(product) && "мин".equals(unitName)) { minutes += q; freeMinutes += free; billedMinutes += billed; }
            if (q > 0) skuLines.get(product).add(sku + " · " + number(q) + " " + unitName + (free > 0 ? " (бесплатно " + number(free) + ")" : ""));
        }
        if (data.optBoolean("stale")) {
            String when = new java.text.SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(new java.util.Date(data.optLong("at")));
            TextView stale = a.text("Нет связи с GitHub — показаны сохранённые данные от " + when + ".", 11, true);
            stale.setTextColor(AMBER);
            usage.addView(stale);
            Button again = a.button("Повторить");
            again.setOnClickListener(v -> reload.run());
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, a.dp(40));
            p.setMargins(0, a.dp(6), 0, a.dp(6));
            usage.addView(again, p);
        }
        usage.addView(line("К оплате (Net)", "Тариф: " + (plan.isEmpty() ? "—" : plan), money(net), net > 0 ? RED : a.GREEN));
        usage.addView(line("Использование (Gross)", "до скидок и бесплатных лимитов", money(gross), a.TEXT));
        usage.addView(line("Скидка GitHub", net <= 0 && gross > 0 ? "всё покрыто бесплатным лимитом" : "бесплатный лимит тарифа", (discount > 0 ? "−" : "") + money(Math.abs(discount)), a.GREEN));
        if (minutes > 0) {
            usage.addView(line("Минуты Actions", "всего " + number(minutes) + " · бесплатно " + number(freeMinutes) + " · оплачено " + number(billedMinutes),
                    number(minutes) + " мин", billedMinutes > 0 ? RED : a.GREEN));
            LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, a.dp(8));
            gp.setMargins(0, 0, 0, a.dp(4));
            usage.addView(gauge(billedMinutes, minutes, billedMinutes > 0 ? RED : a.GREEN), gp);
            TextView included = a.text("В тариф включено " + number(allowanceMinutes(plan)) + " мин; всё сверх этого GitHub покрывает скидкой или блокирует по бюджету.", 10, false);
            included.setTextColor(a.MUTED);
            usage.addView(included);
        }
        for (Map.Entry<String, double[]> entry : byProduct.entrySet()) {
            double[] s = entry.getValue();
            usage.addView(line(entry.getKey(), s[0] == 0 ? "нет расходов" : "gross " + money(s[0]) + " · скидка " + money(Math.abs(s[1])), money(s[2]), s[2] > 0 ? RED : a.GREEN));
            for (String sku : skuLines.get(entry.getKey())) {
                TextView small = a.text("   " + sku, 10, false);
                small.setTextColor(a.MUTED);
                usage.addView(small);
            }
        }
        if (byProduct.isEmpty()) usage.addView(a.text("За этот месяц расходов нет.", 12, false));
        JSONArray repos = data.optJSONArray("repos");
        if (repos != null && repos.length() > 0) {
            List<JSONObject> sorted = new ArrayList<>();
            for (int i = 0; i < repos.length(); i++) sorted.add(repos.getJSONObject(i));
            sorted.sort((x, y) -> Double.compare(y.optDouble("gross"), x.optDouble("gross")));
            TextView title = a.text("По репозиториям (Gross)", 13, true);
            title.setPadding(0, a.dp(12), 0, a.dp(2));
            usage.addView(title);
            double top = Math.max(0.0001, sorted.get(0).optDouble("gross"));
            double other = 0;
            for (int i = 0; i < sorted.size(); i++) {
                JSONObject r = sorted.get(i);
                if (i >= 8) { other += r.optDouble("gross"); continue; }
                usage.addView(line(r.optString("repo"), r.optDouble("net") > 0 ? "к оплате " + money(r.optDouble("net")) : "оплата $0.00", money(r.optDouble("gross")), a.TEXT));
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, a.dp(5));
                rp.setMargins(0, 0, 0, a.dp(2));
                usage.addView(gauge(r.optDouble("gross"), top, 0xFF7A43D1), rp);
            }
            if (other > 0) usage.addView(line("Остальные", (sorted.size() - 8) + " репозиториев", money(other), a.MUTED));
        } else if (data.has("reposError")) {
            TextView small = a.text("Разбивка по репозиториям недоступна: " + data.optString("reposError"), 10, false);
            small.setTextColor(a.MUTED);
            usage.addView(small);
        }
        TextView foot = a.text("Данные GitHub Billing могут обновляться с задержкой. Минуты считаются по приватным репозиториям; для публичных Actions бесплатны.", 10, false);
        foot.setTextColor(a.MUTED);
        foot.setPadding(0, a.dp(8), 0, 0);
        usage.addView(foot);

        gauges.removeAllViews();
        float moneyLimit = a.prefs.getFloat(moneyKey, 0f);
        int minutesLimit = a.prefs.getInt(minutesKey, 0);
        if (moneyLimit > 0) addGauge(gauges, "Расходы (Net)", net, moneyLimit, "$");
        if (minutesLimit > 0) addGauge(gauges, "Минуты Actions", minutes, minutesLimit, "мин");
        boolean over = (moneyLimit > 0 && net >= moneyLimit) || (minutesLimit > 0 && minutes >= minutesLimit);
        boolean near = (moneyLimit > 0 && net >= moneyLimit * .8) || (minutesLimit > 0 && minutes >= minutesLimit * .8);
        if (over || near) {
            TextView warn = a.text(over ? "Лимит GitHubble превышен." + (net <= 0 ? " Платных списаний при этом нет: GitHub покрывает расход бесплатным лимитом." : " GitHub уже начисляет оплату: проверьте бюджеты на сайте.")
                    : "Расход подошёл к 80% лимита GitHubble.", 12, true);
            warn.setTextColor(over && net > 0 ? RED : AMBER);
            warn.setPadding(0, a.dp(8), 0, 0);
            gauges.addView(warn);
        }
    }

    private void loadOrganizations(long generation, LinearLayout host, String account, LinearLayout orgs, TextView orgState) {
        final List<String> admin = new ArrayList<>();
        try {
            JSONArray memberships = retry(() -> a.api.getArray("https://api.github.com/user/memberships/orgs?state=active&per_page=100"));
            for (int i = 0; memberships != null && i < memberships.length(); i++) {
                JSONObject m = memberships.optJSONObject(i);
                JSONObject org = m == null ? null : m.optJSONObject("organization");
                if (org != null && "admin".equals(m.optString("role"))) admin.add(org.optString("login"));
            }
        } catch (Exception error) {
            a.runOnUiThread(() -> {
                if (generation != a.screenGeneration.get() || host != a.content) return;
                orgState.setText("Организации недоступны: " + friendly(error));
                orgState.setTextColor(offline(error) ? AMBER : RED);
            });
            return;
        }
        if (admin.isEmpty()) {
            a.runOnUiThread(() -> {
                if (generation != a.screenGeneration.get() || host != a.content) return;
                orgState.setText("Организаций, где вы владелец, нет. У личного аккаунта бюджеты GitHub меняются только на сайте.");
            });
            return;
        }
        for (final String org : admin) {
            JSONArray budgets = null;
            String failure = "";
            try {
                JSONObject response = retry(() -> a.api.getJsonFresh("https://api.github.com/organizations/" + org + "/settings/billing/budgets?per_page=100"));
                budgets = response.optJSONArray("budgets");
            } catch (Exception error) { failure = friendly(error); }
            final JSONArray list = budgets;
            final String problem = failure;
            a.runOnUiThread(() -> {
                if (generation != a.screenGeneration.get() || host != a.content || !account.equals(a.currentAccountId())) return;
                orgState.setVisibility(View.GONE);
                renderOrganization(orgs, org, list, problem);
            });
        }
    }

    private void renderOrganization(final LinearLayout orgs, final String org, JSONArray budgets, String problem) {
        TextView title = a.text(org, 15, true);
        title.setPadding(0, a.dp(8), 0, a.dp(2));
        orgs.addView(title);
        if (!problem.isEmpty()) {
            TextView error = a.text(problem, 11, false);
            error.setTextColor(RED);
            orgs.addView(error);
            return;
        }
        if (budgets == null || budgets.length() == 0) {
            TextView none = a.text("Бюджетов нет.", 12, false);
            none.setTextColor(a.MUTED);
            orgs.addView(none);
        }
        for (int i = 0; budgets != null && i < budgets.length(); i++) {
            final JSONObject budget = budgets.optJSONObject(i);
            if (budget == null) continue;
            String product = budget.optString("budget_product_sku", budget.optString("budget_type", "?"));
            String scope = budget.optString("budget_scope", "");
            String entity = budget.optString("budget_entity_name", "");
            boolean stop = budget.optBoolean("prevent_further_usage");
            LinearLayout row = line(product + (entity.isEmpty() ? "" : " · " + entity),
                    scope + " · " + (stop ? "блокирует при превышении" : "только предупреждает"),
                    money(budget.optDouble("budget_amount", 0)), stop ? RED : a.TEXT);
            row.setOnClickListener(v -> editBudget(org, budget));
            orgs.addView(row);
        }
        Button add = a.button("Добавить бюджет для " + org);
        add.setOnClickListener(v -> createBudget(org));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, a.dp(44));
        p.topMargin = a.dp(6);
        orgs.addView(add, p);
    }

    private LinearLayout budgetForm(EditText amount, CheckBox stop, Spinner product) {
        LinearLayout form = new LinearLayout(a);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = a.dp(20);
        form.setPadding(pad, a.dp(4), pad, 0);
        if (product != null) form.addView(product);
        amount.setInputType(InputType.TYPE_CLASS_NUMBER);
        amount.setSingleLine(true);
        amount.setHint("Сумма, $ (целое число)");
        amount.setTextColor(a.TEXT);
        amount.setHintTextColor(a.MUTED);
        form.addView(amount);
        stop.setText("Блокировать использование при превышении");
        stop.setTextColor(a.TEXT);
        form.addView(stop);
        return form;
    }

    private void editBudget(final String org, final JSONObject budget) {
        final String id = budget.optString("id", "");
        if (!id.matches("[A-Za-z0-9-]{8,64}")) { a.toast("У бюджета нет корректного id"); return; }
        final String url = "https://api.github.com/organizations/" + org + "/settings/billing/budgets/" + id;
        final EditText amount = new EditText(a);
        final CheckBox stop = new CheckBox(a);
        amount.setText(String.valueOf((long) budget.optDouble("budget_amount", 0)));
        stop.setChecked(budget.optBoolean("prevent_further_usage"));
        new SheetDialog.Builder(a).setTitle("Бюджет " + budget.optString("budget_product_sku", "") + " · " + org)
                .setView(budgetForm(amount, stop, null))
                .setPositiveButton("Сохранить", (d, w) -> {
                    final long value;
                    try { value = Long.parseLong(amount.getText().toString().trim()); }
                    catch (NumberFormatException bad) { a.toast("Введите целую сумму"); return; }
                    final boolean prevent = stop.isChecked();
                    a.io.execute(() -> {
                        try {
                            a.api.patchJson(url, new JSONObject().put("budget_amount", value).put("prevent_further_usage", prevent));
                            a.runOnUiThread(() -> { a.toast("Бюджет обновлён"); show(); });
                        } catch (Exception error) { a.runOnUiThread(() -> a.errorDialog("Бюджет не изменён", friendly(error))); }
                    });
                })
                .setNeutralButton("Удалить", (d, w) -> new SheetDialog.Builder(a).setTitle("Удалить бюджет?")
                        .setMessage("Бюджет " + budget.optString("budget_product_sku", "") + " организации " + org + " будет удалён на GitHub.")
                        .setPositiveButton("Удалить", (d2, w2) -> a.io.execute(() -> {
                            try {
                                a.api.delete(url);
                                a.runOnUiThread(() -> { a.toast("Бюджет удалён"); show(); });
                            } catch (Exception error) { a.runOnUiThread(() -> a.errorDialog("Бюджет не удалён", friendly(error))); }
                        }))
                        .setNegativeButton("Отмена", null).show())
                .setNegativeButton("Отмена", null).show();
    }

    private void createBudget(final String org) {
        final EditText amount = new EditText(a);
        final CheckBox stop = new CheckBox(a);
        final Spinner product = new Spinner(a);
        List<String> names = new ArrayList<>();
        for (String[] p : PRODUCTS) names.add(p[0]);
        product.setAdapter(new ArrayAdapter<>(a, android.R.layout.simple_spinner_dropdown_item, names));
        stop.setChecked(true);
        new SheetDialog.Builder(a).setTitle("Новый бюджет · " + org)
                .setView(budgetForm(amount, stop, product))
                .setPositiveButton("Создать", (d, w) -> {
                    final long value;
                    try { value = Long.parseLong(amount.getText().toString().trim()); }
                    catch (NumberFormatException bad) { a.toast("Введите целую сумму"); return; }
                    final String sku = PRODUCTS[Math.max(0, Math.min(PRODUCTS.length - 1, product.getSelectedItemPosition()))][1];
                    final boolean prevent = stop.isChecked();
                    a.io.execute(() -> {
                        try {
                            JSONObject body = new JSONObject().put("budget_amount", value).put("prevent_further_usage", prevent)
                                    .put("budget_scope", "organization").put("budget_entity_name", "").put("budget_type", "ProductPricing")
                                    .put("budget_product_sku", sku)
                                    .put("budget_alerting", new JSONObject().put("will_alert", false).put("alert_recipients", new JSONArray()));
                            a.api.postJson("https://api.github.com/organizations/" + org + "/settings/billing/budgets", body, true);
                            a.runOnUiThread(() -> { a.toast("Бюджет создан"); show(); });
                        } catch (Exception error) { a.runOnUiThread(() -> a.errorDialog("Бюджет не создан", friendly(error))); }
                    });
                })
                .setNegativeButton("Отмена", null).show();
    }
}
