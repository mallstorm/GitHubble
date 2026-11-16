package app.githubble.mobile.agent;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Registry of built-in (APK assets) and user skills (files dir). Enabled flags live in preferences. */
public final class AgentSkills {
    public interface DecisionSink { void record(String account, String workspaceId, String chat, String text); }
    /** Set by the UI; writes an ADR-style decision into project memory. */
    public static volatile DecisionSink decisions;
    private static volatile AgentSkills instance;
    private final Context context;
    private final SharedPreferences prefs;
    private final File userDir;
    private List<SkillDocument> builtin;

    private AgentSkills(Context context, SharedPreferences prefs) {
        this.context = context.getApplicationContext(); this.prefs = prefs;
        this.userDir = new File(this.context.getFilesDir(), "skills"); userDir.mkdirs();
    }
    public static AgentSkills install(Context context, SharedPreferences prefs) { return instance = new AgentSkills(context, prefs); }
    public static AgentSkills get() { return instance; }

    private synchronized List<SkillDocument> builtin() {
        if (builtin != null) return builtin;
        List<SkillDocument> out = new ArrayList<>();
        try {
            String[] files = context.getAssets().list("skills");
            if (files != null) for (String file : files) {
                if (!file.endsWith(".md")) continue;
                try (InputStream in = context.getAssets().open("skills/" + file)) {
                    out.add(SkillDocument.parse(new String(readAll(in), StandardCharsets.UTF_8), true));
                } catch (Exception broken) { /* a damaged asset must not take the agent down */ }
            }
        } catch (Exception ignored) { }
        return builtin = out;
    }
    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n;
        while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); if (out.size() > 64000) throw new IllegalArgumentException("Слишком большой навык"); }
        return out.toByteArray();
    }
    public synchronized List<SkillDocument> user() {
        List<SkillDocument> out = new ArrayList<>();
        File[] files = userDir.listFiles();
        if (files != null) java.util.Arrays.sort(files);
        if (files != null) for (File f : files) {
            if (!f.getName().endsWith(".md") || f.length() > 64000) continue;
            try { out.add(SkillDocument.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8), false)); } catch (Exception skip) { }
        }
        return out;
    }
    public List<SkillDocument> all() { List<SkillDocument> out = new ArrayList<>(builtin()); out.addAll(user()); return out; }
    public boolean enabled(String name) { return prefs.getBoolean("skill_enabled_" + name, true); }
    public void setEnabled(String name, boolean value) { prefs.edit().putBoolean("skill_enabled_" + name, value).apply(); }
    public List<SkillDocument> enabledSkills() {
        List<SkillDocument> out = new ArrayList<>(); for (SkillDocument s : all()) if (enabled(s.name)) out.add(s); return out;
    }
    public String catalog() { return SkillDocument.formatCatalog(enabledSkills()); }
    public SkillDocument find(String name) {
        for (SkillDocument s : enabledSkills()) if (s.name.equalsIgnoreCase(name == null ? "" : name.trim())) return s;
        return null;
    }
    /** Saves a user skill; built-in names are reserved. */
    public synchronized void saveUser(SkillDocument doc) throws Exception {
        for (SkillDocument b : builtin()) if (b.name.equals(doc.name)) throw new IllegalArgumentException("Имя занято встроенным навыком");
        if (user().size() >= 40 && !new File(userDir, doc.name + ".md").exists()) throw new IllegalArgumentException("Не больше 40 своих навыков");
        Files.write(new File(userDir, doc.name + ".md").toPath(), doc.toMarkdown().getBytes(StandardCharsets.UTF_8));
    }
    public synchronized boolean deleteUser(String name) { return SkillDocument.validName(name) && new File(userDir, name + ".md").delete(); }
    public String exportUser() {
        StringBuilder sb = new StringBuilder(); for (SkillDocument s : user()) sb.append(s.toMarkdown()).append("\n\n=====\n\n"); return sb.toString();
    }
    /** "/name rest" → [skill, rest]; null when the message is not a skill command. */
    public String[] command(String message) {
        if (message == null || !message.startsWith("/")) return null;
        int sp = message.indexOf(' ');
        String name = (sp < 0 ? message.substring(1) : message.substring(1, sp)).trim();
        SkillDocument s = find(name);
        return s == null ? null : new String[]{s.name, sp < 0 ? "" : message.substring(sp + 1).trim()};
    }
}
