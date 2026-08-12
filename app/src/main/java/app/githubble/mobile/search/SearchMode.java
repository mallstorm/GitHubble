package app.githubble.mobile.search;

/** Search surface exposed by the unified GitHubble search screen. */
public enum SearchMode {
    REPOSITORIES("Репозитории"),
    ISSUES_PRS("Issues / PR"),
    CODE("Код"),
    PEOPLE("Люди");

    public final String title;
    SearchMode(String title) { this.title = title; }
}
