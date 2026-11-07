package app.githubble.mobile.agent;

/** Execution authority is separate from source disclosure and GitHub OAuth scopes. */
public final class AgentPermissionPolicy {
    public static final int READ_ONLY=0, REVIEW=1, LOCAL_AUTO=2, GITHUB_AUTO=3;
    private AgentPermissionPolicy(){}
    public static int normalize(int level){return level>=0&&level<=3?level:REVIEW;}
    public static boolean mayWrite(int level,boolean planning){
        return !planning&&normalize(level)!=READ_ONLY;
    }
    public static boolean needsReview(int level,boolean remote,boolean sensitive){
        int value=normalize(level);
        return sensitive||(remote?value<GITHUB_AUTO:value<LOCAL_AUTO);
    }
    public static final String[] LABELS={"Только чтение","Подтверждать изменения",
        "Автоматически локально","Автоматически локально и GitHub"};
}
