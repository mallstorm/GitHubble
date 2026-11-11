package app.githubble.mobile.agent.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Agent Supervisor (TZ 6): a claim such as "the PR exists" or "the APK is ready" is accepted only
 * when independent {@link Evidence} confirms it. Findings say which task state must be revisited.
 */
public final class Supervisor {
    public enum Severity { INFO, WARNING, BLOCKER }

    public static final class Finding {
        public final Severity severity;
        public final String check, message;
        /** State the task should return to so the problem can be corrected; null if none. */
        public final TaskState returnTo;
        Finding(Severity s, String c, String m, TaskState r) { severity = s; check = c; message = m; returnTo = r; }
    }

    public static final class Verdict {
        public final List<Finding> findings = new ArrayList<>();
        public boolean accepted() {
            for (Finding f : findings) if (f.severity == Severity.BLOCKER) return false;
            return true;
        }
        /** Earliest state any blocker asks to return to (so no step is skipped), or null. */
        public TaskState returnTo() {
            TaskState best = null;
            for (Finding f : findings)
                if (f.severity == Severity.BLOCKER && f.returnTo != null && (best == null || f.returnTo.ordinal() < best.ordinal()))
                    best = f.returnTo;
            return best;
        }
        void add(Severity s, String c, String m, TaskState r) { findings.add(new Finding(s, c, m, r)); }
        void block(String c, String m, TaskState r) { add(Severity.BLOCKER, c, m, r); }
        public String summary() {
            StringBuilder sb = new StringBuilder();
            for (Finding f : findings) sb.append(f.severity).append(' ').append(f.check).append(": ").append(f.message).append('\n');
            return sb.toString();
        }
    }

    /** What the agent claims to have done. Fields left empty/zero are not claimed. */
    public static final class Claims {
        public String repo = "", branch = "", commitSha = "", artifactName = "";
        public int pullNumber;
        public long runId;
        /** Report from {@link ArtifactVerifier}; null when no APK is claimed. */
        public ArtifactVerifier.Report apkReport;
        public boolean claimsBuildDone;
        /** Check that the run produced a non-empty, unexpired artifact even without an APK report. */
        public boolean requireArtifact;
    }

    private final Evidence evidence;

    public Supervisor(Evidence evidence) { this.evidence = evidence; }

    public Verdict verifyDelivery(Claims c) {
        Verdict v = new Verdict();
        try {
            if (!c.branch.isEmpty()) {
                String head = evidence.branchHead(c.repo, c.branch);
                if (head == null) v.add(Severity.WARNING, "branch", "не удалось проверить ветку " + c.branch, null);
                else if (head.isEmpty()) v.block("branch", "ветка " + c.branch + " не существует", TaskState.EXECUTING);
                else if (!c.commitSha.isEmpty() && !head.equalsIgnoreCase(c.commitSha)
                        && !Boolean.TRUE.equals(evidence.commitExists(c.repo, c.commitSha)))
                    v.block("commit", "заявленный коммит " + c.commitSha + " отсутствует в репозитории", TaskState.EXECUTING);
            } else if (!c.commitSha.isEmpty()) {
                Boolean exists = evidence.commitExists(c.repo, c.commitSha);
                if (exists == null) v.add(Severity.WARNING, "commit", "не удалось проверить коммит", null);
                else if (!exists) v.block("commit", "коммит " + c.commitSha + " не найден", TaskState.EXECUTING);
            }
            if (c.pullNumber > 0) {
                Evidence.PullInfo pr = evidence.pullRequest(c.repo, c.pullNumber);
                if (pr == null) v.add(Severity.WARNING, "pull-request", "не удалось проверить PR #" + c.pullNumber, null);
                else if (!pr.exists) v.block("pull-request", "PR #" + c.pullNumber + " не существует", TaskState.DELIVERING);
                else if (!c.commitSha.isEmpty() && !pr.headSha.isEmpty() && !pr.headSha.equalsIgnoreCase(c.commitSha))
                    v.add(Severity.WARNING, "pull-request", "HEAD PR отличается от заявленного коммита", null);
            }
            if (c.runId > 0) {
                Evidence.RunInfo run = evidence.workflowRun(c.repo, c.runId);
                if (run == null) v.add(Severity.WARNING, "workflow", "не удалось получить запуск " + c.runId, null);
                else if (!"completed".equals(run.status)) v.block("workflow", "сборка ещё не завершена: " + run.status, TaskState.BUILDING);
                else if (!"success".equals(run.conclusion)) v.block("workflow", "сборка завершилась: " + run.conclusion, TaskState.BUILDING);
                else if (!c.commitSha.isEmpty() && !run.headSha.isEmpty() && !run.headSha.equalsIgnoreCase(c.commitSha))
                    v.block("workflow", "успешная сборка относится к другому коммиту " + run.headSha, TaskState.BUILDING);
                if (run != null && "completed".equals(run.status) && "success".equals(run.conclusion)
                        && (c.claimsBuildDone || c.requireArtifact || !c.artifactName.isEmpty()))
                    checkArtifacts(c, v);
            } else if (c.claimsBuildDone) {
                v.block("workflow", "заявлена готовая сборка без запуска workflow", TaskState.BUILDING);
            }
            if (c.claimsBuildDone) {
                if (c.apkReport == null) v.block("apk", "нет проверенного APK: успешный workflow сам по себе не доставка", TaskState.VERIFYING);
                else if (!c.apkReport.ok()) v.block("apk", "APK не прошёл проверку:\n" + c.apkReport.summary(), TaskState.BUILDING);
            }
        } catch (Exception e) {
            v.add(Severity.WARNING, "evidence", "источник проверки недоступен: " + e.getClass().getSimpleName(), null);
        }
        // Unverifiable claims must not be reported as success.
        boolean unverified = false;
        for (Finding f : v.findings) if (f.severity == Severity.WARNING) unverified = true;
        if (unverified && c.claimsBuildDone) v.block("unverified", "часть заявленных результатов не подтверждена", TaskState.VERIFYING);
        return v;
    }

    private void checkArtifacts(Claims c, Verdict v) throws Exception {
        List<Evidence.ArtifactInfo> list = evidence.artifacts(c.repo, c.runId);
        if (list == null) { v.add(Severity.WARNING, "artifact", "не удалось получить список артефактов", null); return; }
        boolean found = false;
        for (Evidence.ArtifactInfo a : list) {
            if (a.expired || a.sizeBytes <= 0) continue;
            if (c.artifactName.isEmpty() ? a.name.toLowerCase(Locale.ROOT).contains("apk") : a.name.equals(c.artifactName)) found = true;
        }
        if (!found) v.block("artifact", "workflow успешен, но ожидаемого артефакта"
            + (c.artifactName.isEmpty() ? "" : " «" + c.artifactName + "»") + " нет (или он пуст/просрочен)", TaskState.BUILDING);
    }

    // ---- change review (TZ 6.2) ----

    private static final Pattern[] SECRETS = {
        Pattern.compile("gh[pousr]_[A-Za-z0-9]{30,}"),
        Pattern.compile("github_pat_[A-Za-z0-9_]{40,}"),
        Pattern.compile("AKIA[0-9A-Z]{16}"),
        Pattern.compile("-----BEGIN (RSA |EC |OPENSSH |PGP )?PRIVATE KEY-----"),
        Pattern.compile("(?i)(api[_-]?key|secret|passw(or)?d|token)\\s*[=:]\\s*['\"][A-Za-z0-9+/_\\-]{16,}['\"]"),
    };

    public static final class Change {
        public Set<String> plannedPaths;                 // null: no plan to compare with
        public List<String> changedPaths = new ArrayList<>();
        public List<String> deletedPaths = new ArrayList<>();
        public int allowedDeletions = 0;
        public List<String> addedText = new ArrayList<>();   // added lines/contents
        public Set<String> permissionsBefore, permissionsAfter;
        public boolean testsFailed, buildFailed;
        public List<String> unmetRequirements = new ArrayList<>();
    }

    /** Post-change quality gate. Blockers send the task back to EXECUTING. */
    public Verdict reviewChange(Change ch) {
        Verdict v = new Verdict();
        if (ch.plannedPaths != null)
            for (String p : ch.changedPaths)
                if (!ch.plannedPaths.contains(p))
                    v.block("plan", "изменён файл вне утверждённого плана: " + p, TaskState.EXECUTING);
        if (ch.deletedPaths.size() > ch.allowedDeletions)
            v.block("deletion", "удалено файлов: " + ch.deletedPaths.size() + ", разрешено: " + ch.allowedDeletions, TaskState.EXECUTING);
        for (String path : ch.changedPaths)
            if (path.startsWith(".github/workflows/"))
                v.add(Severity.WARNING, "workflow-file", "изменён workflow: " + path + " (проверьте permissions и триггеры)", null);
        for (String text : ch.addedText)
            for (Pattern p : SECRETS)
                if (p.matcher(text).find()) {
                    v.block("secret", "в изменениях обнаружен возможный секрет (шаблон " + p.pattern().substring(0, Math.min(18, p.pattern().length())) + "…)", TaskState.EXECUTING);
                    break;
                }
        if (ch.permissionsBefore != null && ch.permissionsAfter != null) {
            Set<String> added = new java.util.TreeSet<>(ch.permissionsAfter);
            added.removeAll(ch.permissionsBefore);
            if (!added.isEmpty()) v.block("permissions", "повышены требуемые permissions: " + added, TaskState.EXECUTING);
        }
        if (ch.testsFailed) v.block("tests", "тесты не проходят", TaskState.EXECUTING);
        if (ch.buildFailed) v.block("build", "сборка не проходит", TaskState.EXECUTING);
        for (String r : ch.unmetRequirements) v.block("requirement", "не выполнено требование: " + r, TaskState.EXECUTING);
        return v;
    }
}
