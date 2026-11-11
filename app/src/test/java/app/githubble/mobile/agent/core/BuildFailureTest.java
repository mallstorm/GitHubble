package app.githubble.mobile.agent.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class BuildFailureTest {
    private static FailureClass cls(String step, String log) { return BuildFailureClassifier.classify(step, log).failureClass; }

    @Test public void classifiesSourceProblems() {
        assertEquals(FailureClass.KOTLIN_JAVA_ERROR, cls("Build", "Execution failed for task ':app:compileDebugKotlin'.\ne: file:///a/B.kt:(3, 4): Unresolved reference: foo"));
        assertEquals(FailureClass.KOTLIN_JAVA_ERROR, cls("Build", "error: cannot find symbol\n  symbol: class Foo"));
        assertEquals(FailureClass.DEPENDENCY_RESOLUTION_ERROR, cls("Build", "Could not resolve com.example:lib:1.0.\nRequired by: project :app"));
        assertEquals(FailureClass.MANIFEST_ERROR, cls("Build", "Manifest merger failed with multiple errors"));
        assertEquals(FailureClass.RESOURCE_LINKING_ERROR, cls("Build", "Android resource linking failed\nerror: resource drawable/x not found"));
        assertEquals(FailureClass.TEST_FAILURE, cls("Run tests", "> Task :app:testDebugUnitTest FAILED\nThere were failing tests."));
        assertEquals(FailureClass.GRADLE_CONFIGURATION_ERROR, cls("Build", "A problem occurred configuring project ':app'."));
        assertEquals(FailureClass.WORKFLOW_CONFIGURATION_ERROR, cls("Set up job", "Invalid workflow file: .github/workflows/a.yml#L10"));
        assertEquals(FailureClass.ANDROID_SDK_ERROR, cls("Build", "SDK location not found. Define ANDROID_HOME"));
    }

    @Test public void failedJobWithoutFailedStepIsAPlatformProblem() {
        assertEquals(FailureClass.RUNNER_FAILURE, cls("Android checks job-without-failed-step ", ""));
        // But a real error in the log still wins.
        assertEquals(FailureClass.KOTLIN_JAVA_ERROR, cls("b job-without-failed-step ", "e: x.kt:(1, 1): Unresolved reference: q"));
    }

    @Test public void kotlinErrorNeedsRealErrorLine() {
        assertEquals(FailureClass.KOTLIN_JAVA_ERROR, cls("Build", "e: file:///a/B.kt:(3, 4): boom"));
        assertEquals(FailureClass.UNKNOWN, cls("Build", "file name: /tmp/x\nok"));
    }

    @Test public void environmentBeatsSourceHeuristics() {
        // The log also mentions compile words, but the real cause is the platform.
        assertEquals(FailureClass.INFRASTRUCTURE_FAILURE, cls("Build", "compileDebugKotlin\nCould not GET https://x: Read timed out"));
        assertEquals(FailureClass.RUNNER_FAILURE, cls("Build", "The runner has received a shutdown signal."));
        assertEquals(FailureClass.API_RATE_LIMIT, cls("x", "API rate limit exceeded for user"));
        assertEquals(FailureClass.PERMISSION_ERROR, cls("x", "Resource not accessible by integration"));
        assertEquals(FailureClass.SIGNING_FAILURE, cls("Sign", "keystore was tampered with, or password was incorrect"));
        assertEquals(FailureClass.ARTIFACT_PUBLISHING_FAILURE, cls("Upload", "Unable to upload artifact(s)"));
        assertEquals(FailureClass.BILLING_OR_QUOTA_BLOCK, cls("x", "The job was not started because recent account payments have failed or your spending limit needs to be increased."));
        assertEquals(FailureClass.UNKNOWN, cls("x", "all good"));
    }

    @Test public void signatureIgnoresVolatileParts() {
        String a = BuildFailureClassifier.signature("e: /home/runner/work/app/A.kt: (12, 4): Unresolved reference: foo 9f3a1c2");
        String b = BuildFailureClassifier.signature("e: /tmp/other/A.kt: (99, 7): Unresolved reference: foo 11aa22bb");
        assertEquals(a, b);
        assertFalse(a.isEmpty());
    }

    private static BuildFailureClassifier.Result r(String log) { return BuildFailureClassifier.classify("Build", log); }

    @Test public void sourceFailureGetsBoundedFixes() {
        RetryPolicy.Limits l = new RetryPolicy.Limits(); l.maxAutoFixes = 2; l.sameErrorLimit = 5;
        RetryPolicy p = new RetryPolicy(l);
        assertTrue(p.tryStartBuild());
        assertEquals(RetryPolicy.Action.FIX_SOURCE, p.onFailure(r("error: cannot find symbol A"), 0).action);
        assertEquals(RetryPolicy.Action.FIX_SOURCE, p.onFailure(r("error: cannot find symbol B"), 0).action);
        RetryPolicy.Decision d = p.onFailure(r("error: cannot find symbol C"), 0);
        assertEquals(RetryPolicy.Action.FAIL, d.action);
        assertEquals(TaskState.FAILED, d.nextState);
    }

    @Test public void repeatedIdenticalErrorStopsTheLoop() {
        RetryPolicy p = new RetryPolicy(new RetryPolicy.Limits());
        BuildFailureClassifier.Result same = r("e: A.kt: (1, 1): Unresolved reference: foo");
        assertEquals(RetryPolicy.Action.FIX_SOURCE, p.onFailure(same, 0).action);
        assertEquals(RetryPolicy.Action.FIX_SOURCE, p.onFailure(same, 0).action);
        assertEquals(RetryPolicy.Action.FAIL, p.onFailure(same, 0).action);
    }

    @Test public void transientFailuresBackOffExponentiallyThenWait() {
        RetryPolicy.Limits l = new RetryPolicy.Limits(); l.baseDelayMs = 1000; l.maxTransientRetries = 3;
        RetryPolicy p = new RetryPolicy(l);
        BuildFailureClassifier.Result t = r("Connection reset by peer");
        RetryPolicy.Decision d1 = p.onFailure(t, 0), d2 = p.onFailure(t, 0), d3 = p.onFailure(t, 0);
        assertEquals(RetryPolicy.Action.RETRY_AFTER, d1.action);
        assertEquals(1000, d1.delayMs); assertEquals(2000, d2.delayMs); assertEquals(4000, d3.delayMs);
        assertEquals(TaskState.BUILDING, d1.nextState);
        assertEquals(RetryPolicy.Action.WAIT_EXTERNAL, p.onFailure(t, 0).action);
    }

    @Test public void retryAfterHeaderIsHonouredOrEscalated() {
        RetryPolicy.Limits l = new RetryPolicy.Limits(); l.baseDelayMs = 1000; l.maxDelayMs = 60_000;
        RetryPolicy p = new RetryPolicy(l);
        assertEquals(30_000, p.onFailure(r("API rate limit exceeded"), 30_000).delayMs);
        assertEquals(RetryPolicy.Action.WAIT_EXTERNAL, p.onFailure(r("API rate limit exceeded"), 3_600_000).action);
    }

    @Test public void ownerActionsAreNeverBypassed() {
        RetryPolicy p = new RetryPolicy(new RetryPolicy.Limits());
        for (String log : new String[] {"Resource not accessible by integration", "keystore was tampered",
                "recent account payments have failed", "SDK location not found"}) {
            RetryPolicy.Decision d = p.onFailure(r(log), 0);
            assertEquals(log, RetryPolicy.Action.WAIT_EXTERNAL, d.action);
            assertEquals(TaskState.WAITING_EXTERNAL, d.nextState);
        }
        assertEquals(0, p.autoFixes());
    }

    @Test public void buildBudgetIsEnforced() {
        RetryPolicy.Limits l = new RetryPolicy.Limits(); l.maxBuildRuns = 2;
        RetryPolicy p = new RetryPolicy(l);
        assertTrue(p.tryStartBuild()); assertTrue(p.tryStartBuild()); assertFalse(p.tryStartBuild());
        assertEquals(RetryPolicy.Action.FAIL, p.onFailure(r("error: cannot find symbol"), 0).action);
    }
}
