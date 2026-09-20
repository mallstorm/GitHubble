package app.githubble.mobile.build;

/** Shared, deterministic Actions status filters for the RC4.2 build-center UI. */
public final class BuildRunFilter {
    public static final String ALL="all";
    public static final String RUNNING="running";
    public static final String SUCCESS="success";
    public static final String OTHER="other";
    private BuildRunFilter() {}

    public static boolean accepts(String filter,String status,String conclusion) {
        if(ALL.equals(filter)||filter==null)return true;
        boolean done="completed".equals(status);
        if(RUNNING.equals(filter))return !done;
        if(SUCCESS.equals(filter))return done&&"success".equals(conclusion);
        if(OTHER.equals(filter))return done&&!"success".equals(conclusion);
        return true;
    }
}
