package app.githubble.mobile.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import app.githubble.mobile.agent.AgentProjectStore;

/**
 * Native GitHubble AI Workspace surface. Phase 1: real account-scoped project/repository
 * operations and official Copilot tasks; OmniChat LLM transports are not wired yet.
 */
public final class AgentChatScreen {
    public interface Actions {
        void newProject();
        void createRepository();
        void attachRepository();
        void openRepository();
        void showProviderCatalog();
        void configureProvider();
        void selectProvider();
        void disconnectProvider();
        void discoverModels();
        void clearConversation();
        void showCopilotTasks();
        void startWorkflow();
        void showBuildArtifacts();
        void selectExecutor(boolean copilot);
        void selectMode(boolean agentMode);
        void send(String prompt, boolean agentMode, boolean copilot);
    }

    private final Activity activity;
    private final LinearLayout root;
    private final int background, surface, subtle, text, muted, accent, border;
    private final Actions actions;

    public AgentChatScreen(Activity activity, LinearLayout root,
                           int background, int surface, int subtle, int text,
                           int muted, int accent, int border, Actions actions) {
        this.activity = activity;
        this.root = root;
        this.background = background;
        this.surface = surface;
        this.subtle = subtle;
        this.text = text;
        this.muted = muted;
        this.accent = accent;
        this.border = border;
        this.actions = actions;
    }

    public void render(String accountLabel, AgentProjectStore.Project project,
                       JSONArray messages, int providerCount, boolean selectedCopilot, boolean agentMode,
                       String providerLabel, String modelLabel, boolean connected) {
        root.removeAllViews();
        root.setPadding(dp(4), dp(4), dp(4), dp(18));

        LinearLayout header = panel();
        TextView heading = label("AI Workspace", 22, true);
        header.addView(heading);
        header.addView(label("Нативный агентный чат GitHubble", 12, false));
        TextView account = label("GitHub: " + accountLabel, 12, true);
        account.setTextColor(accent);
        header.addView(account);
        root.addView(header, margin());

        LinearLayout projectBox = panel();
        projectBox.addView(label(project.name.isEmpty() ? "Проект пока не создан" : project.name, 17, true));
        projectBox.addView(label(project.repository.isEmpty()
                ? "Репозиторий не подключён" : project.repository + " · " + project.branch, 12, false));
        LinearLayout projectActions = buttons();
        action(projectActions, "Новый проект", actions::newProject);
        action(projectActions, "Создать репо", actions::createRepository);
        projectBox.addView(projectActions);
        LinearLayout linkActions = buttons();
        action(linkActions, "Привязать репо", actions::attachRepository);
        if (!project.repository.isEmpty()) action(linkActions, "Открыть репо", actions::openRepository);
        projectBox.addView(linkActions);
        root.addView(projectBox, margin());

        LinearLayout providers = panel();
        providers.addView(label("Подключения OmniChat: каталог " + providerCount + " провайдеров", 13, true));
        providers.addView(label(connected ? "Активно: " + providerLabel + " · " + modelLabel
                : "AI API не подключён. Выберите провайдера и сохраните ключ/модель.", 12, connected));
        providers.addView(label("API-подключения работают напрямую. ChatGPT OAuth и Subscription Proxy переносятся отдельно.", 11, false));
        LinearLayout connectionActions = buttons();
        action(connectionActions, "Подключить AI", actions::configureProvider);
        action(connectionActions, "Выбрать AI", actions::selectProvider);
        providers.addView(connectionActions);
        LinearLayout extraProviderActions = buttons();
        action(extraProviderActions, "Все провайдеры", actions::showProviderCatalog);
        action(extraProviderActions, "Отключить", actions::disconnectProvider);
        providers.addView(extraProviderActions);
        action(providers, "Получить список моделей", actions::discoverModels);
        root.addView(providers, margin());

        LinearLayout mode = panel();
        mode.addView(label("Режим", 14, true));
        Spinner conversationMode = new Spinner(activity);
        conversationMode.setAdapter(new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Чат", "Агент"}));
        conversationMode.setSelection(agentMode ? 1 : 0);
        conversationMode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                actions.selectMode(position == 1);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        mode.addView(conversationMode, new LinearLayout.LayoutParams(-1, dp(48)));
        mode.addView(label("Исполнитель", 14, true));
        Spinner engine = new Spinner(activity);
        String[] options = {
                "GitHubble AI · OmniChat API",
                "GitHub Copilot Cloud (официальный API)"
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_dropdown_item, options);
        engine.setAdapter(adapter);
        engine.setSelection(selectedCopilot ? 1 : 0);
        engine.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                actions.selectExecutor(position == 1);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        mode.addView(engine, new LinearLayout.LayoutParams(-1, dp(48)));
        mode.addView(label("AI отвечает через настроенную модель. Команды GitHub выполняются отдельными подтверждаемыми действиями.", 11, false));
        root.addView(mode, margin());

        LinearLayout history = panel();
        LinearLayout historyHeader = buttons();
        historyHeader.addView(label("Диалог проекта", 16, true),
                new LinearLayout.LayoutParams(0, -2, 1));
        action(historyHeader, "Новый диалог", actions::clearConversation);
        history.addView(historyHeader);
        if (messages.length() == 0) {
            history.addView(label("Создайте проект, привяжите репозиторий или поручите задачу Copilot.", 12, false));
        } else {
            for (int i = 0; i < messages.length(); i++) {
                JSONObject item = messages.optJSONObject(i);
                if (item == null) continue;
                boolean mine = "user".equals(item.optString("role"));
                LinearLayout bubble = new LinearLayout(activity);
                bubble.setOrientation(LinearLayout.VERTICAL);
                bubble.setPadding(dp(12), dp(9), dp(12), dp(9));
                bubble.setBackground(round(mine ? subtle : background, 13, border));
                TextView author = label(mine ? "Вы" : "GitHubble", 11, true);
                author.setTextColor(mine ? accent : muted);
                bubble.addView(author);
                TextView message = label(item.optString("body"), 13, false);
                message.setTextColor(text);
                message.setTextIsSelectable(true);
                bubble.addView(message);
                LinearLayout.LayoutParams bubbleLp = new LinearLayout.LayoutParams(-1, -2);
                bubbleLp.bottomMargin = dp(8);
                history.addView(bubble, bubbleLp);
            }
        }
        root.addView(history, margin());

        LinearLayout compose = panel();
        EditText input = new EditText(activity);
        input.setHint("Напишите сообщение или задачу…");
        input.setHintTextColor(muted);
        input.setTextColor(text);
        input.setTextSize(14);
        input.setMinLines(2);
        input.setMaxLines(8);
        input.setGravity(Gravity.TOP);
        compose.addView(input, new LinearLayout.LayoutParams(-1, -2));
        Button send = new Button(activity);
        send.setText("Отправить");
        send.setAllCaps(false);
        send.setOnClickListener(v -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) { input.setError("Введите сообщение"); return; }
            actions.send(value, conversationMode.getSelectedItemPosition() == 1,
                    engine.getSelectedItemPosition() == 1);
        });
        compose.addView(send, new LinearLayout.LayoutParams(-1, dp(48)));
        root.addView(compose, margin());

        LinearLayout more = buttons();
        action(more, "Задачи Copilot", actions::showCopilotTasks);
        if (!project.repository.isEmpty()) {
            action(more, "GitHub Actions", actions::startWorkflow);
            action(more, "APK / артефакты", actions::showBuildArtifacts);
        }
        root.addView(more, margin());
    }

    private LinearLayout panel() {
        LinearLayout view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(13), dp(12), dp(13), dp(12));
        view.setBackground(round(surface, 16, border));
        return view;
    }

    private LinearLayout buttons() {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private void action(LinearLayout container, String title, Runnable run) {
        Button button = new Button(activity);
        button.setText(title);
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setOnClickListener(v -> run.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(45), 1);
        lp.rightMargin = dp(5);
        container.addView(button, lp);
    }

    private TextView label(String value, int size, boolean strong) {
        TextView t = new TextView(activity);
        t.setText(value);
        t.setTextColor(strong ? text : muted);
        t.setTextSize(size);
        if (strong) t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(dp(2), dp(3), dp(2), dp(4));
        return t;
    }

    private LinearLayout.LayoutParams margin() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        return lp;
    }

    private GradientDrawable round(int color, int radiusDp, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(1), stroke);
        return d;
    }

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}
