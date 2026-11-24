package app.githubble.mobile.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.text.InputType;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.githubble.mobile.agent.AgentRun;
import app.githubble.mobile.agent.LocalShell;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Right-hand "Код" drawer of the Hubble Agent chat. Three compact tabs built only from data the agent
 * already produces: file changes with diffs, the Workspace file tree and the journal of agent operations.
 */
public final class AgentCodePanel {
    public interface Source {
        JSONArray messages();
        /** Root of the active local Workspace, or null when the project has none. */
        File workspaceRoot();
        void openDiff(String id);
        default boolean taskFiles(){return false;}
        default void taskPage(int offset,java.util.function.Consumer<JSONObject> done,java.util.function.Consumer<String> error){}
        default void taskRead(String path,int start,java.util.function.Consumer<JSONObject> done,java.util.function.Consumer<String> error){}
        default void reviewTask(){}
        /** Snapshot of the run that is working right now (task_state, preview, elapsed_ms), or null when idle. */
        default JSONObject liveRun(){return null;}
        /** Short "repository · branch" line under the title. */
        default String subtitle(){return "";}
        /** Opens the Projects page so the user can create or pick the project folder. */
        default void openProjects(){}
    }

    private static final int MAX_FILES = 400;
    private static final int MAX_FILE_BYTES = 200 * 1024;

    private final Activity activity;
    private final Source source;
    private final int text, muted, accent, subtle, border, surface;
    private final Dialog dialog;
    private final LinearLayout body;
    private final ScrollView scroll;
    private final LinearLayout inputBar;
    private EditText commandInput;
    private boolean shellBusy;
    private static final java.util.Map<String, List<String[]>> HISTORY = new java.util.HashMap<>();
    private static final int[] CODE_COLORS = {0xFF16211F, 0xFFD7E3E0, 0xFF48C9A6, 0xFFE07A7A};
    private final List<TextView> tabs = new ArrayList<>();
    private final android.os.Handler ticker = new android.os.Handler(android.os.Looper.getMainLooper());
    private String signature = "";
    private TextView titleView;
    private int tab;
    private int taskOffset;

    public static void show(Activity activity, Source source, int text, int muted, int accent, int subtle,
                            int border, int surface, int initialTab) {
        new AgentCodePanel(activity, source, text, muted, accent, subtle, border, surface, initialTab);
    }

    private AgentCodePanel(Activity activity, Source source, int text, int muted, int accent, int subtle,
                           int border, int surface, int initialTab) {
        this.activity = activity;
        this.source = source;
        this.text = text;
        this.muted = muted;
        this.accent = accent;
        this.subtle = subtle;
        this.border = border;
        this.surface = surface;
        this.tab = initialTab < 0 ? autoTab() : Math.max(0, Math.min(3, initialTab));
        dialog = new Dialog(activity);
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(surface);

        LinearLayout head = new LinearLayout(activity);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(6), dp(6), 0);
        LinearLayout titleBox = new LinearLayout(activity);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setGravity(Gravity.CENTER_VERTICAL);
        titleView = label("Рабочий стол агента", 17, true, text);
        titleBox.addView(titleView);
        String sub = source.subtitle();
        if (sub != null && !sub.isEmpty()) {
            TextView subtitle = label(sub, 11.5f, false, muted);
            subtitle.setSingleLine();
            subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            titleBox.addView(subtitle);
        }
        head.addView(titleBox, new LinearLayout.LayoutParams(0, dp(52), 1));
        TextView close = label("✕", 18, false, text);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("Закрыть");
        close.setOnClickListener(v -> dialog.dismiss());
        head.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        panel.addView(head);

        LinearLayout bar = new LinearLayout(activity);
        bar.setPadding(dp(10), dp(2), dp(10), dp(8));
        String[] names = {"Журнал", "Изменения", "Файлы", "Терминал"};
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            TextView chip = label(names[i], 12.5f, true, text);
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine();
            chip.setOnClickListener(v -> select(index));
            tabs.add(chip);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(38), 1);
            if (i > 0) params.leftMargin = dp(5);
            bar.addView(chip, params);
        }
        panel.addView(bar);
        View line = new View(activity);
        line.setBackgroundColor(border);
        panel.addView(line, new LinearLayout.LayoutParams(-1, 1));

        scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(10), dp(12), dp(20));
        scroll.addView(body);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        inputBar = new LinearLayout(activity);
        inputBar.setGravity(Gravity.CENTER_VERTICAL);
        inputBar.setPadding(dp(10), dp(6), dp(6), dp(6));
        inputBar.setVisibility(View.GONE);
        TextView prompt = label("$", 15, true, CODE_COLORS[2]);
        prompt.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        inputBar.addView(prompt);
        commandInput = new EditText(activity);
        commandInput.setSingleLine(true);
        commandInput.setHint("команда, например ls -la");
        commandInput.setHintTextColor(muted);
        commandInput.setTextColor(text);
        commandInput.setTextSize(14);
        commandInput.setTypeface(Typeface.MONOSPACE);
        commandInput.setBackgroundColor(Color.TRANSPARENT);
        commandInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        commandInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        commandInput.setOnEditorActionListener((v, action, event) -> { runTyped(); return true; });
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, dp(44), 1);
        inputLp.leftMargin = dp(8);
        inputBar.addView(commandInput, inputLp);
        TextView run = label("▶", 18, true, accent);
        run.setGravity(Gravity.CENTER);
        run.setContentDescription("Выполнить");
        run.setOnClickListener(v -> runTyped());
        inputBar.addView(run, new LinearLayout.LayoutParams(dp(44), dp(44)));
        View barLine = new View(activity);
        barLine.setBackgroundColor(border);
        panel.addView(barLine, new LinearLayout.LayoutParams(-1, 1));
        panel.addView(inputBar);

        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.dimAmount = .34f;
            window.setAttributes(lp);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        select(tab);
        dialog.setOnDismissListener(d -> ticker.removeCallbacksAndMessages(null));
        dialog.show();
        schedule();
        if (window != null) {
            window.setGravity(Gravity.END | Gravity.TOP);
            window.setLayout(Math.min(dp(520), (int) (activity.getResources().getDisplayMetrics().widthPixels * .94f)),
                    WindowManager.LayoutParams.MATCH_PARENT);
        }
    }

    private void select(int index) {
        tab = index;
        for (int i = 0; i < tabs.size(); i++) {
            TextView chip = tabs.get(i);
            boolean on = i == index;
            chip.setTextColor(on ? Color.WHITE : text);
            chip.setBackground(shape(on ? accent : subtle, 19, border, on ? 0 : 1));
        }
        body.removeAllViews();
        inputBar.setVisibility(index == 3 && source.workspaceRoot() != null ? View.VISIBLE : View.GONE);
        signature = signature();
        if (index == 0) renderJournal();
        else if (index == 1) renderChanges();
        else if (index == 2) renderFiles();
        else renderTerminal();
        refreshTabs();
    }

    // ------------------------------------------------------------ live updates

    private int autoTab() {
        if (source.liveRun() != null) return 0;
        return changedFiles().isEmpty() ? 0 : 1;
    }

    private void schedule() {
        ticker.postDelayed(() -> {
            if (!dialog.isShowing()) return;
            String now = signature();
            if (!now.equals(signature) && (tab == 0 || tab == 1)) {
                int y = scroll.getScrollY();
                select(tab);
                scroll.post(() -> scroll.scrollTo(0, y));
            } else {
                refreshTabs();
            }
            schedule();
        }, 1200);
    }

    /** Cheap change detector for the journal: operation count, last state and the live run's stage. */
    private String signature() {
        List<JSONObject> ops = operations();
        JSONObject live = source.liveRun();
        JSONObject last = ops.isEmpty() ? null : ops.get(ops.size() - 1);
        return ops.size() + "|" + (last == null ? "" : last.optString("state")) + "|"
                + (live == null ? "idle" : live.optString("task_state") + live.optString("preview").length() / 40
                + (live.optLong("elapsed_ms") / 3000));
    }

    private void refreshTabs() {
        boolean live = source.liveRun() != null;
        int files = changedFiles().size();
        String[] base = {"Журнал", "Изменения", "Файлы", "Терминал"};
        for (int i = 0; i < tabs.size(); i++) {
            String name = base[i];
            if (i == 0 && live) name = "● Журнал";
            if (i == 1 && files > 0) name = "Изменения " + files;
            tabs.get(i).setText(name);
        }
    }

    private Map<String, JSONObject> changedFiles() {
        Map<String, JSONObject> latest = new LinkedHashMap<>();
        for (JSONObject op : operations()) {
            JSONArray files = op.optJSONArray("files");
            for (int i = 0; files != null && i < files.length(); i++) {
                JSONObject file = files.optJSONObject(i);
                if (file == null || file.optString("path").isEmpty()) continue;
                latest.remove(file.optString("path"));
                latest.put(file.optString("path"), file);
            }
        }
        return latest;
    }

    private List<JSONObject> operations() {
        List<JSONObject> all = new ArrayList<>();
        JSONArray messages = source.messages();
        for (int i = 0; messages != null && i < messages.length(); i++) {
            JSONObject entry = messages.optJSONObject(i);
            JSONObject run = entry == null ? null : entry.optJSONObject("run");
            JSONArray operations = run == null ? null : run.optJSONArray("operations");
            for (int k = 0; operations != null && k < operations.length(); k++) {
                JSONObject op = operations.optJSONObject(k);
                if (op != null) all.add(op);
            }
        }
        return all;
    }

    private void renderChanges() {
        Map<String, JSONObject> latest = new LinkedHashMap<>();
        Map<String, Boolean> applied = new LinkedHashMap<>();
        for (JSONObject op : operations()) {
            JSONArray files = op.optJSONArray("files");
            for (int i = 0; files != null && i < files.length(); i++) {
                JSONObject file = files.optJSONObject(i);
                if (file == null || file.optString("path").isEmpty()) continue;
                latest.remove(file.optString("path"));
                latest.put(file.optString("path"), file);
                applied.put(file.optString("path"), op.optBoolean("applied"));
            }
        }
        if (latest.isEmpty()) {
            empty("Пока нет изменений.\nКогда агент изменит файлы, их diff появится здесь.", null, null);
            return;
        }
        List<String> paths = new ArrayList<>(latest.keySet());
        Collections.reverse(paths);
        int added = 0, removed = 0;
        for (String path : paths) {
            added += latest.get(path).optInt("added");
            removed += latest.get(path).optInt("removed");
        }
        TextView summary = label(paths.size() + " файл. · +" + added + " −" + removed, 12, true, muted);
        summary.setPadding(dp(2), 0, 0, dp(8));
        body.addView(summary);
        if (source.taskFiles()) {
            TextView review = label("Проверить и принять изменения", 14, true, Color.WHITE);
            review.setGravity(Gravity.CENTER);
            review.setBackground(shape(accent, 14, border, 0));
            review.setOnClickListener(v -> source.reviewTask());
            LinearLayout.LayoutParams reviewLp = new LinearLayout.LayoutParams(-1, dp(46));
            reviewLp.bottomMargin = dp(10);
            body.addView(review, reviewLp);
        }
        for (String path : paths) {
            JSONObject file = latest.get(path);
            final String id = file.optString("diff_id");
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(12), dp(9), dp(12), dp(9));
            row.setBackground(shape(subtle, 12, border, 1));
            TextView name = label(path, 13, true, text);
            name.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            row.addView(name);
            boolean saved = Boolean.TRUE.equals(applied.get(path));
            android.text.SpannableStringBuilder meta = new android.text.SpannableStringBuilder("+" + file.optInt("added"));
            meta.setSpan(new android.text.style.ForegroundColorSpan(0xFF2E9E6B), 0, meta.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int from = meta.length();
            meta.append("  −").append(String.valueOf(file.optInt("removed")));
            meta.setSpan(new android.text.style.ForegroundColorSpan(0xFFC75B66), from, meta.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int tail = meta.length();
            meta.append("  ·  ").append(saved ? "сохранено" : "предложено");
            meta.setSpan(new android.text.style.ForegroundColorSpan(muted), tail, meta.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            TextView metaView = label("", 11.5f, true, muted);
            metaView.setText(meta);
            row.addView(metaView);
            if (!id.isEmpty()) row.setOnClickListener(v -> source.openDiff(id));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = dp(6);
            body.addView(row, params);
        }
    }

    private void renderFiles() {
        if(source.taskFiles()){renderTaskFiles();return;}

        File root = source.workspaceRoot();
        if (root == null || !root.isDirectory()) {
            empty("У этого диалога нет папки проекта.\nСоздайте проект — папка появится сама, и здесь откроются её файлы.", "Открыть проекты", source::openProjects);
            return;
        }
        List<String> files = new ArrayList<>();
        collect(root, root, files);
        Collections.sort(files);
        if (files.isEmpty()) {
            empty("Папка проекта пока пуста.\nФайлы появятся, когда агент или вы их добавите.", null, null);
            return;
        }
        TextView summary = label("Workspace · " + files.size() + (files.size() >= MAX_FILES ? "+" : "") + " файл.", 12, true, muted);
        summary.setPadding(dp(2), 0, 0, dp(8));
        body.addView(summary);
        String lastDir = null;
        for (String path : files) {
            final File file = new File(root, path);
            int slash = path.lastIndexOf('/');
            String dir = slash < 0 ? "" : path.substring(0, slash);
            if (!dir.equals(lastDir)) {
                TextView header = label(dir.isEmpty() ? "корень" : dir, 11.5f, true, muted);
                header.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                header.setPadding(dp(4), dir.equals("") || lastDir == null ? 0 : dp(10), 0, dp(4));
                body.addView(header);
                lastDir = dir;
            }
            TextView row = label(slash < 0 ? path : path.substring(slash + 1), 13, false, text);
            row.setTypeface(Typeface.MONOSPACE);
            row.setPadding(dp(12), dp(9), dp(12), dp(9));
            row.setBackground(shape(subtle, 10, border, 1));
            row.setOnClickListener(v -> openFile(root, file, path));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = dp(4);
            body.addView(row, params);
        }
    }

    private void renderTaskFiles(){
        empty("Загружаю файлы текущей задачи…", null, null);
        source.taskPage(taskOffset,page->{
            if(!dialog.isShowing()||tab!=2)return;body.removeAllViews();
            TextView review=label("Изменения задачи · diff и принятие",13,true,accent);review.setPadding(dp(10),dp(12),dp(10),dp(12));
            review.setOnClickListener(v->source.reviewTask());body.addView(review);
            JSONArray files=page.optJSONArray("files");if(files==null)return;
            for(int i=0;i<files.length();i++){
                String path=files.optString(i);TextView row=label(path,13,false,text);row.setTypeface(Typeface.MONOSPACE);row.setPadding(dp(12),dp(10),dp(12),dp(10));
                row.setOnClickListener(v->taskFile(path,1));body.addView(row);
            }
            if(taskOffset>0){TextView previous=label("← Предыдущие файлы",13,true,accent);previous.setOnClickListener(v->{taskOffset=Math.max(0,taskOffset-100);body.removeAllViews();renderTaskFiles();});body.addView(previous);}
            if(!page.isNull("next_offset")){TextView next=label("Следующие файлы →",13,true,accent);next.setPadding(dp(12),dp(12),dp(12),dp(12));next.setOnClickListener(v->{taskOffset=page.optInt("next_offset");body.removeAllViews();renderTaskFiles();});body.addView(next);}
        },message->{if(dialog.isShowing()&&tab==2){body.removeAllViews();empty(message,null,null);}});
    }
    private void taskFile(String path,int start){source.taskRead(path,start,page->{
        if(!dialog.isShowing())return;
        TextView value=label(page.optString("content","Файл не существует"),12,false,text);value.setTypeface(Typeface.MONOSPACE);value.setTextIsSelectable(true);value.setPadding(dp(12),dp(12),dp(12),dp(12));
        ScrollView viewer=new ScrollView(activity);viewer.addView(value);
        android.app.AlertDialog.Builder popup=new android.app.AlertDialog.Builder(activity).setTitle(path+" · "+page.optInt("start_line")+"–"+page.optInt("end_line")).setView(viewer).setNegativeButton("Закрыть",null);
        if(page.optBoolean("truncated")&&page.optInt("end_line")>=start)popup.setPositiveButton("Следующие строки",(d,w)->taskFile(path,page.optInt("end_line")+1));
        if(start>1)popup.setNeutralButton("Предыдущие строки",(d,w)->taskFile(path,Math.max(1,start-200)));popup.show();
    },message->{if(dialog.isShowing())new android.app.AlertDialog.Builder(activity).setMessage(message).setPositiveButton("ОК",null).show();});}

    private void collect(File root, File dir, List<String> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (out.size() >= MAX_FILES) return;
            String name = child.getName();
            if (name.startsWith(".")) continue;
            if (child.isDirectory()) collect(root, child, out);
            else out.add(child.getAbsolutePath().substring(root.getAbsolutePath().length() + 1));
        }
    }

    private void openFile(File root, File file, String path) {
        String content;
        try {
            if (!file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator))
                throw new SecurityException("Путь вне Workspace");
            if (file.length() > MAX_FILE_BYTES) throw new IllegalStateException("Файл больше 200 КБ");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
            byte[] bytes = out.toByteArray();
            for (byte b : bytes) if (b == 0) throw new IllegalStateException("Бинарный файл");
            content = new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            content = "Не удалось показать файл: " + e.getMessage();
        }
        TextView view = label(content, 12, false, text);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        view.setPadding(dp(16), dp(8), dp(16), dp(8));
        ScrollView viewer = new ScrollView(activity);
        viewer.addView(view);
        new android.app.AlertDialog.Builder(activity).setTitle(path).setView(viewer)
                .setPositiveButton("Закрыть", null).show();
    }

    private List<String[]> history() {
        File root = source.workspaceRoot();
        String key = root == null ? "" : root.getAbsolutePath();
        List<String[]> list = HISTORY.get(key);
        if (list == null) { list = new ArrayList<>(); HISTORY.put(key, list); }
        return list;
    }

    private void renderTerminal() {
        File root = source.workspaceRoot();
        if (root == null || !root.isDirectory()) {
            empty("Терминал работает в папке проекта.\nСоздайте проект — и команды будут выполняться в его папке.", "Открыть проекты", source::openProjects);
            return;
        }
        HorizontalScrollView chipsScroll = new HorizontalScrollView(activity);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(activity);
        String[] quick = {"ls -la", "pwd", "find . -type f | head -40", "cat README.md", "grep -rn TODO .", "df -h ."};
        for (String command : quick) {
            TextView chip = label(command, 12, false, text);
            chip.setTypeface(Typeface.MONOSPACE);
            chip.setPadding(dp(12), dp(8), dp(12), dp(8));
            chip.setBackground(shape(subtle, 16, border, 1));
            chip.setOnClickListener(v -> execute(command));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            params.rightMargin = dp(6);
            chips.addView(chip, params);
        }
        chipsScroll.addView(chips);
        LinearLayout.LayoutParams chipsLp = new LinearLayout.LayoutParams(-1, -2);
        chipsLp.bottomMargin = dp(8);
        body.addView(chipsScroll, chipsLp);
        TextView where = label("Папка проекта · системная оболочка без сети и git", 11, false, muted);
        where.setPadding(dp(2), 0, 0, dp(8));
        body.addView(where);
        List<String[]> entries = history();
        if (entries.isEmpty()) {
            TextView hint = label("Введите команду внизу или нажмите на готовую.", 13, false, muted);
            hint.setPadding(dp(2), dp(4), 0, 0);
            body.addView(hint);
        }
        for (int i = entries.size() - 1; i >= 0; i--) addTerminalCard(entries.get(i));
    }

    private void addTerminalCard(String[] entry) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder("$ " + entry[0]);
        sb.setSpan(new android.text.style.ForegroundColorSpan(CODE_COLORS[2]), 0, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        String output = entry[1].trim();
        if (!output.isEmpty()) {
            sb.append('\n');
            int start = sb.length();
            sb.append(output);
            boolean error = "1".equals(entry[2]);
            sb.setSpan(new android.text.style.ForegroundColorSpan(error ? CODE_COLORS[3] : CODE_COLORS[1]), start, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        TextView card = new TextView(activity);
        card.setText(sb);
        card.setTypeface(Typeface.MONOSPACE);
        card.setTextSize(11.5f);
        card.setTextIsSelectable(true);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackground(shape(CODE_COLORS[0], 12, border, 1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(8);
        body.addView(card, params);
    }

    private void runTyped() {
        String command = commandInput.getText().toString().trim();
        if (command.isEmpty()) return;
        commandInput.setText("");
        execute(command);
    }

    private void execute(String command) {
        File root = source.workspaceRoot();
        if (root == null || shellBusy) return;
        shellBusy = true;
        final List<String[]> entries = history();
        new Thread(() -> {
            String output;
            boolean error;
            try {
                LocalShell.Result result = LocalShell.run(root, command, 20000, 24 * 1024);
                output = result.output + (result.truncated ? "\n… вывод обрезан" : "")
                        + (result.timedOut ? "\n… остановлено через 20 с" : "")
                        + (result.exitCode > 0 ? "\n[код " + result.exitCode + "]" : "");
                error = result.timedOut || result.exitCode != 0;
            } catch (Exception e) {
                output = "Не удалось запустить: " + e.getMessage();
                error = true;
            }
            final String shown = output;
            final boolean failed = error;
            activity.runOnUiThread(() -> {
                shellBusy = false;
                entries.add(new String[]{command, shown, failed ? "1" : "0"});
                while (entries.size() > 40) entries.remove(0);
                if (dialog.isShowing() && tab == 3) {
                    body.removeAllViews();
                    renderTerminal();
                    scroll.post(() -> scroll.fullScroll(View.FOCUS_UP));
                }
            });
        }, "workspace-terminal").start();
    }

    private void renderJournal() {
        JSONObject live = source.liveRun();
        if (live != null) body.addView(liveCard(live));
        JSONArray messages = source.messages();
        // Group operations by the request that caused them, newest first.
        List<String> titles = new ArrayList<>();
        List<List<JSONObject>> groups = new ArrayList<>();
        List<JSONObject> runs = new ArrayList<>();
        String request = "";
        for (int i = 0; messages != null && i < messages.length(); i++) {
            JSONObject entry = messages.optJSONObject(i);
            if (entry == null) continue;
            String role = entry.optString("role");
            if ("user".equals(role)) { request = entry.optString("body"); continue; }
            JSONObject run = entry.optJSONObject("run");
            JSONArray operations = run == null ? null : run.optJSONArray("operations");
            if (operations == null || operations.length() == 0) continue;
            List<JSONObject> list = new ArrayList<>();
            for (int k = 0; k < operations.length(); k++) { JSONObject op = operations.optJSONObject(k); if (op != null) list.add(op); }
            titles.add(request);
            groups.add(list);
            runs.add(run);
        }
        if (groups.isEmpty()) {
            if (live == null) empty("Журнал пуст.\nЗдесь по шагам видно, что делает агент: чтение файлов, правки, запросы к GitHub, сборки.", null, null);
            return;
        }
        int shownGroups = 0;
        for (int g = groups.size() - 1; g >= 0 && shownGroups < 12; g--, shownGroups++) {
            List<JSONObject> list = groups.get(g);
            int failed = 0;
            for (JSONObject op : list) if (isFailed(op)) failed++;
            String title = titles.get(g).replace('\n', ' ').trim();
            if (title.length() > 70) title = title.substring(0, 69) + "…";
            LinearLayout card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(10), dp(12), dp(6));
            card.setBackground(shape(subtle, 14, border, 1));
            TextView head = label(title.isEmpty() ? "Работа агента" : title, 13, true, text);
            head.setMaxLines(2);
            head.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(head);
            JSONObject run = runs.get(g);
            String meta = list.size() + " " + (list.size() == 1 ? "шаг" : "шагов")
                    + (failed > 0 ? " · ошибок: " + failed : "")
                    + (run.optLong("elapsed_ms") > 0 ? " · " + AgentRun.time(run.optLong("elapsed_ms")) : "");
            TextView metaView = label(meta, 11.5f, false, failed > 0 ? 0xFFC75B66 : muted);
            metaView.setPadding(0, dp(2), 0, dp(6));
            card.addView(metaView);
            int from = Math.max(0, list.size() - 40);
            for (int i = list.size() - 1; i >= from; i--) card.addView(stepRow(list.get(i)));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = dp(10);
            body.addView(card, params);
        }
    }

    private boolean isFailed(JSONObject op) {
        String state = op.optString("state");
        return "failed".equals(state) || "unknown".equals(state) || "denied".equals(state);
    }

    /** One timeline row: status dot, plain-language title, target, duration, error text. */
    private View stepRow(JSONObject op) {
        boolean failed = isFailed(op), running = "running".equals(op.optString("state"));
        int color = failed ? 0xFFC75B66 : running ? 0xFFE0A030 : 0xFF2E9E6B;
        LinearLayout row = new LinearLayout(activity);
        row.setPadding(0, dp(5), 0, dp(5));
        View dot = new View(activity);
        GradientDrawable dotShape = new GradientDrawable();
        dotShape.setShape(GradientDrawable.OVAL);
        dotShape.setColor(color);
        dot.setBackground(dotShape);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(9), dp(9));
        dotLp.topMargin = dp(5);
        dotLp.rightMargin = dp(10);
        row.addView(dot, dotLp);
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        TextView title = label(AgentRun.label(op.optString("name")), 13, true, text);
        column.addView(title);
        String target = op.optString("target");
        if (!target.isEmpty()) {
            TextView t = label(target, 11.5f, false, muted);
            t.setTypeface(Typeface.MONOSPACE);
            t.setMaxLines(2);
            t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            column.addView(t);
        }
        if (failed) {
            String error = op.optString("error").isEmpty() ? op.optString("state") : op.optString("error");
            TextView e = label(error, 11.5f, false, 0xFFC75B66);
            e.setMaxLines(4);
            e.setEllipsize(android.text.TextUtils.TruncateAt.END);
            column.addView(e);
        }
        row.addView(column, new LinearLayout.LayoutParams(0, -2, 1));
        TextView time = label(running ? "…" : Math.max(1, op.optLong("elapsed_ms") / 100) / 10.0 + " с", 11, false, muted);
        time.setPadding(dp(8), dp(2), 0, 0);
        row.addView(time);
        row.setOnClickListener(v -> {
            JSONArray files = op.optJSONArray("files");
            JSONObject first = files == null ? null : files.optJSONObject(0);
            if (first != null && !first.optString("diff_id").isEmpty()) source.openDiff(first.optString("diff_id"));
        });
        return row;
    }

    /** Live header: what the agent is doing right now and the beginning of the answer it is writing. */
    private View liveCard(JSONObject live) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(shape(subtle, 16, accent, 1));
        TextView head = label("● " + app.githubble.mobile.agent.AgentTaskLabels.activity(live.optString("task_state")) + "…", 15, true, accent);
        card.addView(head);
        head.animate().alpha(.45f).setDuration(700).withEndAction(() -> head.animate().alpha(1f).setDuration(700).start()).start();
        JSONArray ops = live.optJSONArray("operations");
        int count = ops == null ? 0 : ops.length();
        TextView meta = label(AgentRun.time(live.optLong("elapsed_ms")) + " · " + app.githubble.mobile.agent.AgentTaskLabels.actions(count), 11.5f, false, muted);
        meta.setPadding(0, dp(2), 0, 0);
        card.addView(meta);
        String preview = live.optString("preview").replace('\n', ' ').trim();
        if (!preview.isEmpty()) {
            if (preview.length() > 220) preview = "…" + preview.substring(preview.length() - 219);
            TextView thought = label(preview, 12.5f, false, text);
            thought.setPadding(0, dp(8), 0, 0);
            card.addView(thought);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(12);
        card.setLayoutParams(params);
        return card;
    }

    private void empty(String message, String actionLabel, Runnable action) {
        TextView view = label(message, 14, false, muted);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(16), dp(70), dp(16), 0);
        body.addView(view);
        if (actionLabel != null && action != null) {
            TextView button = label(actionLabel, 14, true, Color.WHITE);
            button.setGravity(Gravity.CENTER);
            button.setBackground(shape(accent, 16, border, 0));
            button.setOnClickListener(v -> { dialog.dismiss(); action.run(); });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(220), dp(46));
            params.gravity = Gravity.CENTER_HORIZONTAL;
            params.topMargin = dp(18);
            body.addView(button, params);
        }
    }

    private TextView label(String value, float size, boolean bold, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(size);
        if (bold) view.setTypeface(null, Typeface.BOLD);
        return view;
    }

    private GradientDrawable shape(int color, int radius, int edge, int stroke) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        if (stroke > 0) background.setStroke(dp(stroke), edge);
        return background;
    }

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + .5f);
    }
}
