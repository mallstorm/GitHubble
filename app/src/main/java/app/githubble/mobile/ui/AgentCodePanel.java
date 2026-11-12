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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.githubble.mobile.agent.AgentRun;

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
    }

    private static final int MAX_FILES = 400;
    private static final int MAX_FILE_BYTES = 200 * 1024;

    private final Activity activity;
    private final Source source;
    private final int text, muted, accent, subtle, border, surface;
    private final Dialog dialog;
    private final LinearLayout body;
    private final List<TextView> tabs = new ArrayList<>();
    private int tab;

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
        this.tab = Math.max(0, Math.min(2, initialTab));
        dialog = new Dialog(activity);
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(surface);

        LinearLayout head = new LinearLayout(activity);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(6), dp(6), 0);
        TextView title = label("Код", 18, true, text);
        head.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView close = label("✕", 18, false, text);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("Закрыть");
        close.setOnClickListener(v -> dialog.dismiss());
        head.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        panel.addView(head);

        LinearLayout bar = new LinearLayout(activity);
        bar.setPadding(dp(12), dp(2), dp(12), dp(8));
        String[] names = {"Изменения", "Файлы", "Журнал"};
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            TextView chip = label(names[i], 13, true, text);
            chip.setGravity(Gravity.CENTER);
            chip.setOnClickListener(v -> select(index));
            tabs.add(chip);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(38), 1);
            if (i > 0) params.leftMargin = dp(6);
            bar.addView(chip, params);
        }
        panel.addView(bar);
        View line = new View(activity);
        line.setBackgroundColor(border);
        panel.addView(line, new LinearLayout.LayoutParams(-1, 1));

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(10), dp(12), dp(20));
        scroll.addView(body);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.dimAmount = .34f;
            window.setAttributes(lp);
        }
        select(tab);
        dialog.show();
        if (window != null) {
            window.setGravity(Gravity.END | Gravity.TOP);
            window.setLayout(Math.min(dp(400), (int) (activity.getResources().getDisplayMetrics().widthPixels * .94f)),
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
        if (index == 0) renderChanges();
        else if (index == 1) renderFiles();
        else renderJournal();
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
            empty("Пока нет изменений.\nКогда агент изменит файлы, их diff появится здесь.");
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
            TextView meta = label("+" + file.optInt("added") + " −" + file.optInt("removed")
                    + " · " + (saved ? "сохранено" : "предложено"), 11, false, muted);
            row.addView(meta);
            if (!id.isEmpty()) row.setOnClickListener(v -> source.openDiff(id));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = dp(6);
            body.addView(row, params);
        }
    }

    private void renderFiles() {
        File root = source.workspaceRoot();
        if (root == null || !root.isDirectory()) {
            empty("У проекта нет локального Workspace.\nСоздайте или подключите проект в чате, и файлы появятся здесь.");
            return;
        }
        List<String> files = new ArrayList<>();
        collect(root, root, files);
        Collections.sort(files);
        if (files.isEmpty()) {
            empty("Workspace пуст.");
            return;
        }
        TextView summary = label("Workspace · " + files.size() + (files.size() >= MAX_FILES ? "+" : "") + " файл.", 12, true, muted);
        summary.setPadding(dp(2), 0, 0, dp(8));
        body.addView(summary);
        for (String path : files) {
            final File file = new File(root, path);
            TextView row = label(path, 13, false, text);
            row.setTypeface(Typeface.MONOSPACE);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackground(shape(subtle, 10, border, 1));
            row.setOnClickListener(v -> openFile(root, file, path));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = dp(5);
            body.addView(row, params);
        }
    }

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
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(view);
        new android.app.AlertDialog.Builder(activity).setTitle(path).setView(scroll)
                .setPositiveButton("Закрыть", null).show();
    }

    private void renderJournal() {
        List<JSONObject> operations = operations();
        if (operations.isEmpty()) {
            empty("Журнал пуст.\nОперации агента появятся здесь по мере выполнения.");
            return;
        }
        int from = Math.max(0, operations.size() - 80);
        for (int i = operations.size() - 1; i >= from; i--) {
            JSONObject op = operations.get(i);
            String state = op.optString("state");
            boolean failed = "failed".equals(state) || "unknown".equals(state) || "denied".equals(state);
            boolean running = "running".equals(state);
            StringBuilder out = new StringBuilder("$ ").append(AgentRun.label(op.optString("name")));
            if (!op.optString("target").isEmpty()) out.append(' ').append(op.optString("target"));
            out.append('\n').append(running ? "… выполняется" : failed ? "✗ " + (op.optString("error").isEmpty() ? state : op.optString("error"))
                    : "✓ " + Math.max(1, op.optLong("elapsed_ms") / 100) / 10.0 + " с");
            TextView row = label(out.toString(), 11, false, failed ? 0xFFC75B66 : text);
            row.setTypeface(Typeface.MONOSPACE);
            row.setTextIsSelectable(true);
            row.setPadding(dp(12), dp(9), dp(12), dp(9));
            row.setBackground(shape(subtle, 10, border, 1));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = dp(5);
            body.addView(row, params);
        }
    }

    private void empty(String message) {
        TextView view = label(message, 14, false, muted);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(16), dp(80), dp(16), 0);
        body.addView(view);
    }

    private TextView label(String value, int size, boolean bold, int color) {
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
