package app.githubble.mobile.agent.core;

/** Build/CI failure taxonomy (TZ 9.2) with the handling each class allows. */
public enum FailureClass {
    COMPILATION_ERROR(true, false),
    KOTLIN_JAVA_ERROR(true, false),
    DEPENDENCY_RESOLUTION_ERROR(true, false),
    GRADLE_CONFIGURATION_ERROR(true, false),
    ANDROID_SDK_ERROR(false, false),
    MANIFEST_ERROR(true, false),
    RESOURCE_LINKING_ERROR(true, false),
    TEST_FAILURE(true, false),
    WORKFLOW_CONFIGURATION_ERROR(true, false),
    PERMISSION_ERROR(false, false),
    API_RATE_LIMIT(false, true),
    RUNNER_FAILURE(false, true),
    INFRASTRUCTURE_FAILURE(false, true),
    SIGNING_FAILURE(false, false),
    BILLING_OR_QUOTA_BLOCK(false, false),
    ARTIFACT_PUBLISHING_FAILURE(false, true),
    UNKNOWN(false, false);

    /** Changing project sources can plausibly fix it. */
    public final boolean sourceFixable;
    /** Re-running unchanged code can plausibly succeed (transient). */
    public final boolean transientFailure;

    FailureClass(boolean sourceFixable, boolean transientFailure) {
        this.sourceFixable = sourceFixable;
        this.transientFailure = transientFailure;
    }
}
