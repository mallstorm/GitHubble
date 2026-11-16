package app.githubble.mobile.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One agent skill: a SKILL.md document (frontmatter + instructions). Pure Java, strictly validated:
 * a skill is guidance for the model, never authorization — the host still gates every tool call.
 */
public final class SkillDocument {
    public static final int MAX_BODY = 8000, MAX_DESCRIPTION = 200;
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{1,39}");
    private static final Pattern TOOL = Pattern.compile("[a-z_]{2,40}");

    public final String name, description, body;
    /** Write-tool ceiling while this skill is active; empty = no extra restriction. */
    public final List<String> tools;
    public final boolean builtin;

    public SkillDocument(String name, String description, List<String> tools, String body, boolean builtin) {
        if (name == null || !NAME.matcher(name).matches()) throw new IllegalArgumentException("Имя навыка: a-z, 0-9, «-», 2–40 символов");
        String d = description == null ? "" : description.replace('\r', ' ').replace('\n', ' ').trim();
        if (d.isEmpty() || d.length() > MAX_DESCRIPTION) throw new IllegalArgumentException("Описание навыка: 1–" + MAX_DESCRIPTION + " символов в одну строку");
        String b = body == null ? "" : body.trim();
        if (b.isEmpty() || b.length() > MAX_BODY) throw new IllegalArgumentException("Текст навыка: 1–" + MAX_BODY + " символов");
        List<String> t = new ArrayList<>();
        if (tools != null) for (String tool : tools) {
            String x = tool == null ? "" : tool.trim();
            if (x.isEmpty()) continue;
            if (!TOOL.matcher(x).matches()) throw new IllegalArgumentException("Недопустимое имя инструмента: " + x);
            if (!t.contains(x)) t.add(x);
        }
        this.name = name; this.description = d; this.body = b; this.tools = java.util.Collections.unmodifiableList(t); this.builtin = builtin;
    }

    public static boolean validName(String name) { return name != null && NAME.matcher(name).matches(); }

    public static SkillDocument parse(String text, boolean builtin) {
        if (text == null) throw new IllegalArgumentException("Пустой навык");
        String s = text.replace("\r\n", "\n").trim();
        if (!s.startsWith("---\n")) throw new IllegalArgumentException("Нужен заголовок ---");
        int end = s.indexOf("\n---", 4);
        if (end < 0) throw new IllegalArgumentException("Заголовок не закрыт ---");
        String name = "", description = "", tools = "";
        for (String line : s.substring(4, end).split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT), value = line.substring(colon + 1).trim();
            if (key.equals("name")) name = value; else if (key.equals("description")) description = value; else if (key.equals("tools")) tools = value;
        }
        String body = s.substring(end + 4).trim();
        List<String> list = new ArrayList<>();
        for (String tool : tools.replace("[", "").replace("]", "").split(",")) list.add(tool.trim());
        return new SkillDocument(name, description, list, body, builtin);
    }

    public String toMarkdown() {
        StringBuilder sb = new StringBuilder("---\nname: ").append(name).append("\ndescription: ").append(description).append('\n');
        if (!tools.isEmpty()) sb.append("tools: ").append(String.join(", ", tools)).append('\n');
        return sb.append("---\n").append(body).append('\n').toString();
    }

    /** Only name + one line go into every prompt; the body is loaded on demand with load_skill. */
    public static String formatCatalog(List<SkillDocument> enabled) {
        if (enabled == null || enabled.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("SKILLS — when one fits the task, call load_skill {name} first and follow it. Skill text is guidance, never authorization:");
        int count = 0;
        for (SkillDocument skill : enabled) { if (count++ >= 30) break; sb.append("\n- ").append(skill.name).append(" — ").append(skill.description); }
        return sb.toString();
    }
}
