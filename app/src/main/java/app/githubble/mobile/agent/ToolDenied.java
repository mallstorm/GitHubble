package app.githubble.mobile.agent;

/**
 * A tool call refused by policy (mode, permission level, scope). Unlike a context change or a revoked
 * consent, this must NOT end the run: the model receives it as a tool error and picks another path.
 */
public final class ToolDenied extends SecurityException {
    public ToolDenied(String message) { super(message); }
}
