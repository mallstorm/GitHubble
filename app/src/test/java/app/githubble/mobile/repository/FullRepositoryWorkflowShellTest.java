package app.githubble.mobile.repository;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * The generated Actions workflows are shell and Python programs that only run on GitHub.
 * Syntax checks alone missed a real defect (a grep under pipefail aborted the first
 * backup step), so these tests also execute the Git-only steps against local repositories.
 */
public final class FullRepositoryWorkflowShellTest {
    private static final String STEP_FETCH = "Fetch complete Git history and all refs";

    private static final class Result {
        final int code;
        final String output;
        Result(int code, String output) { this.code = code; this.output = output; }
    }

    private static boolean has(String tool, String versionArg) {
        try {
            Process p = new ProcessBuilder(tool, versionArg).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception missing) {
            return false;
        }
    }

    private static Result run(File dir, Map<String, String> env, String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(dir).redirectErrorStream(true);
        if (env != null) builder.environment().putAll(env);
        Process process = builder.start();
        byte[] out = process.getInputStream().readAllBytes();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Command timed out: " + String.join(" ", command));
        }
        return new Result(process.exitValue(), new String(out, StandardCharsets.UTF_8));
    }

    /** Step name to its `run: |` script, YAML indentation removed. */
    static Map<String, String> runSteps(String yaml) {
        Map<String, String> steps = new LinkedHashMap<>();
        String[] lines = yaml.split("\n", -1);
        String name = null;
        for (int i = 0; i < lines.length; i++) {
            Matcher named = Pattern.compile("^      - name: (.*)$").matcher(lines[i]);
            if (named.matches()) { name = named.group(1).trim(); continue; }
            if (name != null && lines[i].equals("        run: |")) {
                StringBuilder script = new StringBuilder();
                int j = i + 1;
                while (j < lines.length && (lines[j].isEmpty() || lines[j].startsWith("          "))) {
                    script.append(lines[j].isEmpty() ? "" : lines[j].substring(10)).append('\n');
                    j++;
                }
                steps.put(name, script.toString());
                name = null;
                i = j - 1;
            }
        }
        return steps;
    }

    private static List<String> pythonBlocks(String script) {
        List<String> blocks = new ArrayList<>();
        Matcher m = Pattern.compile("python3 -?[^\\n]*<<'PY'\\n(.*?)\\nPY\\n", Pattern.DOTALL)
                .matcher(script + "\n");
        while (m.find()) blocks.add(m.group(1) + "\n");
        return blocks;
    }

    @Test public void everyRunStepIsValidBashAndEmbeddedPythonCompiles() throws Exception {
        assumeTrue(has("bash", "--version"));
        boolean python = has("python3", "--version");
        Path tmp = Files.createTempDirectory("wf-syntax");
        int steps = 0, blocks = 0;
        for (String yaml : new String[] {
                FullRepositoryBackupWorkflow.yaml(), FullRepositoryRestoreWorkflow.yaml()}) {
            for (Map.Entry<String, String> step : runSteps(yaml).entrySet()) {
                Path file = tmp.resolve("step" + (steps++) + ".sh");
                Files.writeString(file, step.getValue());
                Result syntax = run(tmp.toFile(), null, "bash", "-n", file.toString());
                assertEquals(step.getKey() + ": " + syntax.output, 0, syntax.code);
                if (!python) continue;
                for (String block : pythonBlocks(step.getValue())) {
                    Path py = tmp.resolve("block" + (blocks++) + ".py");
                    Files.writeString(py, block);
                    Result compiled = run(tmp.toFile(), null, "python3", "-I", "-m", "py_compile", py.toString());
                    assertEquals(step.getKey() + ": " + compiled.output, 0, compiled.code);
                }
            }
        }
        assertTrue("Expected to find run steps", steps >= 10);
    }

    private static void git(File dir, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-c"); command.add("user.email=t@example.com");
        command.add("-c"); command.add("user.name=t");
        command.add("-c"); command.add("commit.gpgsign=false");
        command.add("-c"); command.add("init.defaultBranch=main");
        for (String a : args) command.add(a);
        Result r = run(dir, null, command.toArray(new String[0]));
        assertEquals(String.join(" ", command) + "\n" + r.output, 0, r.code);
    }

    private Result runFetchStep(boolean extraSourceChange) throws Exception {
        assumeTrue(has("bash", "--version"));
        assumeTrue(has("git", "--version"));
        Path tmp = Files.createTempDirectory("wf-fetch");
        File work = tmp.resolve("work").toFile();
        assertTrue(work.mkdirs());
        git(work, "init", "-q");
        Files.writeString(work.toPath().resolve("a.txt"), "one\n");
        git(work, "add", "."); git(work, "commit", "-q", "-m", "c1");
        Result head = run(work, null, "git", "rev-parse", "HEAD");
        String sourceSha = head.output.trim();
        Path flow = work.toPath().resolve(".github/workflows");
        Files.createDirectories(flow);
        Files.writeString(flow.resolve("githubble-full-backup.yml"), "name: x\n");
        if (extraSourceChange) Files.writeString(work.toPath().resolve("a.txt"), "two\n");
        git(work, "add", "."); git(work, "commit", "-q", "-m", "bootstrap");
        git(work, "branch", "-M", "main");

        File remotes = tmp.resolve("remotes/owner").toFile();
        assertTrue(remotes.mkdirs());
        git(tmp.toFile(), "clone", "-q", "--bare", work.getAbsolutePath(),
                new File(remotes, "repo.git").getAbsolutePath());

        File runnerTemp = tmp.resolve("runner").toFile();
        assertTrue(runnerTemp.mkdirs());
        File askpass = new File(runnerTemp, "ask.sh");
        Files.writeString(askpass.toPath(), "#!/bin/sh\nexit 1\n");
        assertTrue(askpass.setExecutable(true));

        String script = runSteps(FullRepositoryBackupWorkflow.yaml()).get(STEP_FETCH);
        assertNotNull("Step not found: " + STEP_FETCH, script);
        Path scriptFile = tmp.resolve("fetch.sh");
        Files.writeString(scriptFile, script);

        Map<String, String> env = new LinkedHashMap<>();
        env.put("GITHUB_REPOSITORY", "owner/repo");
        env.put("RUNNER_TEMP", runnerTemp.getAbsolutePath());
        env.put("SOURCE_SHA", sourceSha);
        env.put("SOURCE_BRANCH", "main");
        env.put("GH_TOKEN", "unused");
        env.put("GH_BACKUP_ASKPASS", askpass.getAbsolutePath());
        env.put("GIT_CONFIG_COUNT", "1");
        env.put("GIT_CONFIG_KEY_0", "url.file://" + tmp.resolve("remotes") + "/.insteadOf");
        env.put("GIT_CONFIG_VALUE_0", "https://github.com/");
        Result result = run(tmp.toFile(), env, "bash", scriptFile.toString());
        if (result.code == 0) {
            String refs = Files.readString(runnerTemp.toPath().resolve("git-refs.txt"));
            assertTrue(refs, refs.contains("refs/heads/main " + sourceSha));
        }
        return result;
    }

    @Test public void fetchStepSucceedsWhenOnlyTheTemporaryWorkflowDiffers() throws Exception {
        Result r = runFetchStep(false);
        assertEquals(r.output, 0, r.code);
    }

    @Test public void fetchStepRefusesWhenTheRepositoryChangedMeanwhile() throws Exception {
        Result r = runFetchStep(true);
        assertEquals(r.output, 40, r.code);
        assertTrue(r.output, r.output.contains("Repository changed while preparing backup"));
    }
}
