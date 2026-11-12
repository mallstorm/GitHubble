package app.githubble.mobile.agent;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Runs one user-typed command with the system shell inside a Workspace folder. */
public final class LocalShell {
    private LocalShell() {}

    public static final class Result {
        public final String output;
        public final int exitCode;
        public final boolean timedOut;
        public final boolean truncated;

        Result(String output, int exitCode, boolean timedOut, boolean truncated) {
            this.output = output;
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.truncated = truncated;
        }
    }

    public static String shellPath() {
        return new File("/system/bin/sh").canExecute() ? "/system/bin/sh" : "/bin/sh";
    }

    public static Result run(File directory, String command, long timeoutMs, int maxBytes) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(shellPath(), "-c", command)
                .directory(directory).redirectErrorStream(true);
        builder.environment().put("HOME", directory.getAbsolutePath());
        builder.environment().put("TMPDIR", directory.getAbsolutePath());
        builder.environment().put("PATH", "/system/bin:/system/xbin:/usr/bin:/bin");
        builder.environment().put("TERM", "dumb");
        final Process process = builder.start();
        process.getOutputStream().close();
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        final boolean[] truncated = {false};
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    synchronized (captured) {
                        int room = maxBytes - captured.size();
                        if (room > 0) captured.write(buffer, 0, Math.min(room, n));
                        if (n > room) truncated[0] = true;
                    }
                }
            } catch (Exception ignored) {
            }
        }, "local-shell-reader");
        reader.setDaemon(true);
        reader.start();
        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(1, TimeUnit.SECONDS);
        }
        reader.join(500);
        String text;
        synchronized (captured) {
            text = new String(captured.toByteArray(), StandardCharsets.UTF_8);
        }
        return new Result(text, finished ? process.exitValue() : -1, !finished, truncated[0]);
    }
}
