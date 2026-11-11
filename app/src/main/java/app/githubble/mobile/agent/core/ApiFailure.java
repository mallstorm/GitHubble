package app.githubble.mobile.agent.core;

/**
 * Class of a failed GitHub call with the handling the HOST requires from the model (TZ 17.3 F:
 * a refused operation is reported, never worked around).
 */
public enum ApiFailure {
    PERMISSION_DENIED("GitHub refused this operation for the current account or token. Do NOT retry, do NOT try another "
        + "endpoint or tool to get around it, do not suggest changing security settings yourself. Tell the user which "
        + "access or permission is missing and stop this action."),
    NOT_FOUND("The resource does not exist or is not visible to this account (GitHub answers 404 for private resources). "
        + "Check the name once; do not silently substitute a different repository or path."),
    RATE_LIMITED("GitHub rate limit reached. Do not retry now; report it and wait for the stated reset."),
    VALIDATION("GitHub rejected the arguments. Correct them once at most, then report the problem."),
    SERVER("GitHub server error. A read may be retried once; for a write verify the real state before repeating it."),
    OUTCOME_UNKNOWN("The request may have been applied but the answer was lost. Verify the state in GitHub before any repeat."),
    OTHER("");

    public final String instruction;

    ApiFailure(String instruction) { this.instruction = instruction; }

    public static ApiFailure classify(int status, boolean rateLimited, boolean outcomeUnknown) {
        if (outcomeUnknown) return OUTCOME_UNKNOWN;
        if (rateLimited || status == 429) return RATE_LIMITED;
        if (status == 401 || status == 403) return PERMISSION_DENIED;
        if (status == 404 || status == 410) return NOT_FOUND;
        if (status == 409 || status == 422) return VALIDATION;
        if (status >= 500 && status < 600) return SERVER;
        return OTHER;
    }
}
