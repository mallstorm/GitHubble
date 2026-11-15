package app.githubble.mobile.mirror;

/** Security and input validation for account-to-account mirror operations. */
public final class MirrorPolicy {
    private MirrorPolicy() {}
    public static final String WORKFLOW_PATH=".github/workflows/githubble-mirror.yml";

    public static String requireRepository(String full) {
        if(full==null || !full.matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}")
                || full.contains("..") || full.endsWith(".") || full.endsWith("/."))
            throw new IllegalArgumentException("Неверное имя репозитория: владелец/название");
        return full;
    }
    public static String requireName(String name) {
        if(name==null || !name.matches("[A-Za-z0-9_.-]{1,100}") || name.contains("..")
                || name.equals(".") || name.endsWith("."))
            throw new IllegalArgumentException("Название назначения: 1–100 символов A-Z, 0-9, _, -, .");
        return name;
    }
    public static String requireDifferent(String source,String target) {
        requireRepository(source);requireRepository(target);
        if(source.equalsIgnoreCase(target))
            throw new IllegalArgumentException("Нельзя зеркалировать репозиторий в самого себя");
        return target;
    }
}
