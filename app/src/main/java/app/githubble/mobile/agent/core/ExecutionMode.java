package app.githubble.mobile.agent.core;

import app.githubble.mobile.agent.AgentPermissionPolicy;

/** Hubble Git Agent control modes (TZ 5). Pure policy: no Android, no I/O. */
public enum ExecutionMode {
    AUTO, SEMI, MANUAL;

    /** Actions are classified by the host, never by model text. */
    public enum Action {
        /** Reading files, GitHub metadata, logs. */
        READ,
        /** Writing inside the local Workspace (checkpointed). */
        LOCAL_WRITE,
        /** Commit/push to a working (non-protected) branch, PR creation/update, issues, comments. */
        WORKING_BRANCH_WRITE,
        /** Dispatching or re-running CI. */
        BUILD,
        /** Making a project public, publishing a new repository's code. */
        PUBLISH,
        /** Merge into the protected/default branch, or a release meant to be final. */
        MERGE_PROTECTED,
        /** Repo deletion, user-data deletion, secrets/permissions/billing, disabling checks,
         *  direct write to a protected branch, irreversible production release. */
        DANGEROUS
    }

    public enum Decision { ALLOW, NEED_APPROVAL }

    /**
     * @param stageApproved the user pre-approved the whole current stage (SEMI only)
     * @param releasePreApproved the user pre-approved the final release (SEMI only)
     */
    public Decision decide(Action action, boolean stageApproved, boolean releasePreApproved) {
        if (action == null) throw new IllegalArgumentException("action");
        // Dangerous operations always need their own approval, whatever the mode (TZ 5.1).
        if (action == Action.DANGEROUS) return Decision.NEED_APPROVAL;
        if (action == Action.READ) return Decision.ALLOW;
        switch (this) {
            case AUTO:
                return Decision.ALLOW;
            case SEMI:
                if (action == Action.MERGE_PROTECTED) {
                    return releasePreApproved ? Decision.ALLOW : Decision.NEED_APPROVAL;
                }
                if (action == Action.PUBLISH) return Decision.NEED_APPROVAL;
                return Decision.ALLOW; // LOCAL_WRITE, WORKING_BRANCH_WRITE, BUILD inside the approved plan
            case MANUAL:
            default:
                // Advisory mode: every mutation needs explicit consent. A stage approval is
                // a SEMI feature and deliberately does not widen MANUAL.
                return Decision.NEED_APPROVAL;
        }
    }

    /** Inverse of {@link #permissionLevel()}; READ_ONLY has no mode (writes are impossible) and maps to MANUAL. */
    public static ExecutionMode forLevel(int level) {
        switch (AgentPermissionPolicy.normalize(level)) {
            case AgentPermissionPolicy.GITHUB_AUTO: return AUTO;
            case AgentPermissionPolicy.LOCAL_AUTO: return SEMI;
            default: return MANUAL;
        }
    }

    /** Maps onto the existing permission levels so the policy is extended, not duplicated. */
    public int permissionLevel() {
        switch (this) {
            case AUTO: return AgentPermissionPolicy.GITHUB_AUTO;
            case SEMI: return AgentPermissionPolicy.LOCAL_AUTO;
            case MANUAL:
            default: return AgentPermissionPolicy.REVIEW;
        }
    }
}
