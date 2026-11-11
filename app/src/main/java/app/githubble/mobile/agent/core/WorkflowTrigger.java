package app.githubble.mobile.agent.core;

import java.util.regex.Pattern;

/** Static check that a workflow file can be started by hand (TZ 9: no blind dispatch). */
public final class WorkflowTrigger {
    private static final Pattern TOKEN = Pattern.compile("(?<![A-Za-z0-9_])workflow_dispatch(?![A-Za-z0-9_])");
    private static final Pattern JOBS = Pattern.compile("(?m)^jobs\\s*:");

    private WorkflowTrigger() {}

    /** True when the trigger section (everything before the top-level {@code jobs:}) names workflow_dispatch. */
    public static boolean hasDispatch(String yaml) {
        if (yaml == null || yaml.isEmpty()) return false;
        StringBuilder clean = new StringBuilder();
        for (String line : yaml.split("\r?\n", -1)) {
            String t = line.trim();
            if (t.startsWith("#")) continue;
            int hash = line.indexOf(" #");
            clean.append(hash >= 0 ? line.substring(0, hash) : line).append('\n');
        }
        String text = clean.toString();
        java.util.regex.Matcher jobs = JOBS.matcher(text);
        if (jobs.find()) text = text.substring(0, jobs.start());
        return TOKEN.matcher(text).find();
    }
}
