package app.githubble.mobile.agent.core;

import java.util.Set;

/**
 * Finds the Actions run created by one of our own dispatches. Matching uses run ids seen
 * BEFORE the dispatch (never device time, which may disagree with GitHub's clock).
 */
public final class DispatchRunMatcher {
    public enum Outcome { MATCHED, AMBIGUOUS, NOT_FOUND }

    public static final class Match {
        public final Outcome outcome;
        public final long runId; // 0 when NOT_FOUND
        Match(Outcome o, long id) { outcome = o; runId = id; }
    }

    private DispatchRunMatcher() {}

    public static Match pick(long[] ids, String[] events, String[] branches, Set<Long> before, String branch) {
        long best = 0;
        int fresh = 0;
        for (int i = 0; i < ids.length; i++) {
            if (before.contains(ids[i])) continue;
            if (!"workflow_dispatch".equals(events[i])) continue;
            if (branch != null && !branch.isEmpty() && !branch.equals(branches[i])) continue;
            fresh++;
            if (ids[i] > best) best = ids[i];
        }
        if (fresh == 0) return new Match(Outcome.NOT_FOUND, 0);
        return new Match(fresh == 1 ? Outcome.MATCHED : Outcome.AMBIGUOUS, best);
    }
}
