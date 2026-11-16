package app.githubble.mobile.agent;

/** States belong to the set and to its individual file entries for partial approval. */
public final class ChangeSet {
    private ChangeSet() {}
    public enum State { PROPOSED, APPROVED, LOCAL_APPLIED, PUBLISHED, REJECTED, CONFLICT, ROLLED_BACK }
}
