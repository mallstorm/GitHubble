package app.githubble.mobile.agent.core;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Classifies a failed CI step/log into a {@link FailureClass}. Environment problems are
 * recognised first so infrastructure faults never lead to pointless source edits (TZ 9.2).
 */
public final class BuildFailureClassifier {
    private BuildFailureClassifier() {}

    public static final class Result {
        public final FailureClass failureClass;
        /** Normalised first error line: equal signatures mean "the same error again". */
        public final String signature;
        Result(FailureClass c, String s) { failureClass = c; signature = s; }
    }

    private static final Pattern KOTLIN_ERROR_LINE = Pattern.compile("(?m)^e: ");
    private static final Pattern NUMBERS = Pattern.compile("[0-9]+");
    private static final Pattern HEX = Pattern.compile("\\b[0-9a-f]{7,40}\\b");
    private static final Pattern PATHS = Pattern.compile("(/[\\w.@+-]+)+");

    private static boolean has(String text, String... needles) {
        for (String n : needles) if (text.contains(n)) return true;
        return false;
    }

    public static Result classify(String stepName, String logExcerpt) {
        String step = stepName == null ? "" : stepName.toLowerCase(Locale.ROOT);
        String log = logExcerpt == null ? "" : logExcerpt.toLowerCase(Locale.ROOT);
        String all = step + "\n" + log;
        return new Result(decide(step, log, all), signature(logExcerpt));
    }

    private static FailureClass decide(String step, String log, String all) {
        // 1. Platform/environment first.
        if (has(all, "recent account payments have failed", "spending limit needs to be increased",
                "usage limit", "minutes quota"))
            return FailureClass.BILLING_OR_QUOTA_BLOCK; // owner action; the agent never touches billing
        if (has(all, "api rate limit exceeded", "secondary rate limit", "rate limit exceeded"))
            return FailureClass.API_RATE_LIMIT;
        if (has(all, "runner has received a shutdown signal", "lost communication with the server",
                "hosted runner encountered an error", "no space left on device",
                "the runner has been", "the operation was canceled"))
            return FailureClass.RUNNER_FAILURE;
        if (has(all, "resource not accessible by integration", "permission denied (publickey)",
                "refusing to allow", "must have admin rights", "insufficient permissions"))
            return FailureClass.PERMISSION_ERROR;
        if (has(all, "unable to upload artifact", "failed to createartifact", "artifact storage quota",
                "unable to finalize artifact", "error: create artifact"))
            return FailureClass.ARTIFACT_PUBLISHING_FAILURE;
        if (has(all, "keystore was tampered", "failed to read key", "keystore file", "apksigner",
                "invalid keystore format", "cannot recover key", "signing config"))
            return FailureClass.SIGNING_FAILURE;
        if (has(all, "connection reset", "connection timed out", "read timed out", "connect timed out",
                "503 service unavailable", "502 bad gateway", "504 gateway", "tls handshake",
                "temporary failure in name resolution", "could not get 5", "unexpected end of stream",
                "premature eof", "network is unreachable"))
            return FailureClass.INFRASTRUCTURE_FAILURE;
        // 2. Workflow definition.
        if (has(all, "invalid workflow file", "unable to resolve action", "unrecognized named-value",
                "yaml syntax error", "unexpected value '", "workflow is not valid"))
            return FailureClass.WORKFLOW_CONFIGURATION_ERROR;
        // 3. Android toolchain.
        if (has(all, "sdk location not found", "failed to install the following android sdk packages",
                "license for package", "ndk at", "did not have a source.properties", "no version of ndk",
                "installed build tools revision", "platforms;android-"))
            return FailureClass.ANDROID_SDK_ERROR;
        if (has(all, "manifest merger failed", "processdebugmanifest", "processreleasemanifest")
                || (has(all, "androidmanifest.xml") && has(all, "error")))
            return FailureClass.MANIFEST_ERROR;
        if (has(all, "android resource linking failed", "linkdebugresources", "linkreleaseresources",
                "aapt: error", "aapt2", "resource not found") && !has(all, "compilation failed"))
            return FailureClass.RESOURCE_LINKING_ERROR;
        // 4. Dependencies and Gradle configuration.
        if (has(all, "could not resolve", "could not find ", "failed to resolve", "could not download",
                "no matching variant", "no matching configuration"))
            return FailureClass.DEPENDENCY_RESOLUTION_ERROR;
        if (has(all, "a problem occurred configuring", "could not determine the dependencies of task",
                "plugin [id:", "was not found in any of the following sources", "unsupported gradle",
                "build file '", "could not compile build file", "unsupported class file major version",
                "settings file"))
            return FailureClass.GRADLE_CONFIGURATION_ERROR;
        // 5. Tests before compilation words, because test reports contain "error" too.
        if (has(all, "there were failing tests", "tests completed,", "unit test failed", "testdebugunittest failed",
                "> test ") && has(all, "failed", "failing"))
            return FailureClass.TEST_FAILURE;
        // 6. Source compilation.
        if (KOTLIN_ERROR_LINE.matcher(log).find() || has(all, "compiledebugkotlin", "compilereleasekotlin", "compiledebugjavawithjavac",
                "compilereleasejavawithjavac", "unresolved reference", "cannot find symbol",
                "incompatible types", ".kt: (", "error: ';' expected",
                "class, interface, enum, or record expected", "does not override"))
            return FailureClass.KOTLIN_JAVA_ERROR;
        if (has(all, "compilation failed", "compilation error", "error: ", "undefined reference",
                "fatal error:", "ld: error"))
            return FailureClass.COMPILATION_ERROR;
        return FailureClass.UNKNOWN;
    }

    /** First meaningful error line with volatile parts (numbers, hashes, paths) removed. */
    public static String signature(String log) {
        if (log == null) return "";
        for (String raw : log.split("\n")) {
            String line = raw.trim();
            String lower = line.toLowerCase(Locale.ROOT);
            if (line.isEmpty()) continue;
            if (lower.startsWith("e: ") || lower.contains("error") || lower.contains("failed")
                    || lower.contains("exception") || lower.contains("could not")) {
                String norm = HEX.matcher(line).replaceAll("#");
                norm = PATHS.matcher(norm).replaceAll("/p");
                norm = NUMBERS.matcher(norm).replaceAll("0");
                return norm.length() > 160 ? norm.substring(0, 160) : norm;
            }
        }
        return "";
    }
}
