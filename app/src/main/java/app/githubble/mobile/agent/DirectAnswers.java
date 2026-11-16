package app.githubble.mobile.agent;

/** Metadata answers use actual GitHub facts without sending account data to an external model. */
public final class DirectAnswers {
    private DirectAnswers() {}
    public static boolean handles(String question) {
        if(question==null||question.length()>300)return false;
        String q=question.toLowerCase(java.util.Locale.ROOT);
        if(q.matches("(?s).*(?:исправ|измен|проанализ|анализ|созда|добав|собер|сборк|repair|fix|edit|build|review|analy).*"))return false;
        return GitHubReadLoop.isDirectRepositoryQuestion(question);
    }
    public static String answer(GitHubAgentBridge github,String account,String question) throws Exception {
        return GitHubReadLoop.directAnswer(github,account,question);
    }
}
