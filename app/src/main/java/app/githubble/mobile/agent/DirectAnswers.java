package app.githubble.mobile.agent;

/** Metadata answers use actual GitHub facts without sending account data to an external model. */
public final class DirectAnswers {
    private DirectAnswers() {}
    public static boolean handles(String question) {
        if(question==null||question.length()>1500)return false;
        String q=question.toLowerCase(java.util.Locale.ROOT);
        boolean changes=q.matches("(?s).*(?:исправ|измен|созда|добав|собер|сборк|удал|repair|fix|edit|build|create|delete).*");
        // Cross-check of all projects against all accounts is answered from the GitHub API, never guessed by a model.
        if(!changes&&GitHubReadLoop.wantsAccountMatrix(question))return true;
        if(question.length()>300)return false;
        IntentRouter.Kind kind=IntentRouter.classify(question);
        if(kind==IntentRouter.Kind.PICK_REPO||kind==IntentRouter.Kind.CONTINUE)return false;
        if(q.matches("(?s).*(?:исправ|измен|проанализ|анализ|созда|добав|собер|сборк|repair|fix|edit|build|review|analy).*"))return false;
        return GitHubReadLoop.isDirectRepositoryQuestion(question);
    }
    public static String answer(GitHubAgentBridge github,String account,String question) throws Exception {
        return GitHubReadLoop.directAnswer(github,account,question);
    }
}
