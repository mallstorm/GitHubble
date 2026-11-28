package app.githubble.mobile.repository;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Builds .github/dependabot.yml for the ecosystems the user ticks. */
public final class DependabotConfig {
    private DependabotConfig() {}

    public static final String[][] ECOSYSTEMS = {
        {"github-actions", "GitHub Actions (workflow-файлы)"}, {"gradle", "Gradle (Android, Java, Kotlin)"}, {"maven", "Maven"}, {"npm", "npm / yarn / pnpm"},
        {"pip", "Python (pip)"}, {"docker", "Docker"}, {"cargo", "Rust (cargo)"}, {"gomod", "Go modules"}, {"composer", "PHP (composer)"},
        {"bundler", "Ruby (bundler)"}, {"nuget", ".NET (NuGet)"}, {"swift", "Swift Package Manager"}, {"terraform", "Terraform"},
    };
    public static final String[] INTERVALS = {"daily", "weekly", "monthly"};

    public static final class Entry {
        public final String ecosystem, directory; public final int limit;
        public Entry(String ecosystem, String directory, int limit) { this.ecosystem = ecosystem; this.directory = directory; this.limit = limit; }
    }

    /** Guesses ecosystems from the names found in the repository root. */
    public static Set<String> detect(Set<String> names) {
        Set<String> out = new LinkedHashSet<>();
        for (String n : names) {
            if (n.equals("build.gradle") || n.equals("build.gradle.kts") || n.equals("settings.gradle") || n.equals("settings.gradle.kts")) out.add("gradle");
            else if (n.equals("pom.xml")) out.add("maven");
            else if (n.equals("package.json")) out.add("npm");
            else if (n.equals("requirements.txt") || n.equals("pyproject.toml") || n.equals("Pipfile") || n.equals("setup.py")) out.add("pip");
            else if (n.equals("Dockerfile") || n.startsWith("Dockerfile.")) out.add("docker");
            else if (n.equals("Cargo.toml")) out.add("cargo");
            else if (n.equals("go.mod")) out.add("gomod");
            else if (n.equals("composer.json")) out.add("composer");
            else if (n.equals("Gemfile")) out.add("bundler");
            else if (n.endsWith(".csproj") || n.endsWith(".sln")) out.add("nuget");
            else if (n.equals("Package.swift")) out.add("swift");
            else if (n.endsWith(".tf")) out.add("terraform");
        }
        return out;
    }

    public static String checkDirectory(String raw) {
        String d = raw == null ? "" : raw.trim();
        if (d.isEmpty()) d = "/";
        if (!d.startsWith("/") || d.contains("..") || d.contains("\"") || d.contains("\n")) throw new IllegalArgumentException("Папка — путь от корня, например / или /app");
        return d;
    }

    public static String generate(List<Entry> entries, String interval) {
        boolean valid = false;
        for (String i : INTERVALS) if (i.equals(interval)) valid = true;
        if (!valid) throw new IllegalArgumentException("Недопустимая частота");
        if (entries.isEmpty()) throw new IllegalArgumentException("Выберите хотя бы одну экосистему");
        StringBuilder b = new StringBuilder("version: 2\nupdates:\n");
        for (Entry e : entries) {
            boolean known = false;
            for (String[] eco : ECOSYSTEMS) if (eco[0].equals(e.ecosystem)) known = true;
            if (!known) throw new IllegalArgumentException("Неизвестная экосистема " + e.ecosystem);
            if (e.limit < 0 || e.limit > 50) throw new IllegalArgumentException("Лимит открытых pull request — от 0 до 50");
            b.append("  - package-ecosystem: \"").append(e.ecosystem).append("\"\n").append("    directory: \"").append(checkDirectory(e.directory)).append("\"\n")
                .append("    schedule:\n      interval: \"").append(interval).append("\"\n").append("    open-pull-requests-limit: ").append(e.limit).append('\n');
        }
        return b.toString();
    }
}
