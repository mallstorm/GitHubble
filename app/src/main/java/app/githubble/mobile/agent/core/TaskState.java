package app.githubble.mobile.agent.core;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Explicit task state machine (TZ 4.3). Transitions are validated, never implied. */
public enum TaskState {
    CREATED, ANALYZING, PLANNING, APPROVAL, EXECUTING, TESTING, BUILDING, VERIFYING,
    DELIVERING, COMPLETED,
    PAUSED, WAITING_PERMISSION, WAITING_EXTERNAL, RECOVERING, FAILED, CANCELLED, ROLLED_BACK;

    private static final Map<TaskState, Set<TaskState>> NEXT = new EnumMap<>(TaskState.class);

    private static void allow(TaskState from, TaskState... to) {
        Set<TaskState> set = NEXT.get(from);
        if (set == null) { set = EnumSet.noneOf(TaskState.class); NEXT.put(from, set); }
        for (TaskState t : to) set.add(t);
    }

    static {
        // Forward chain.
        allow(CREATED, ANALYZING);
        allow(ANALYZING, PLANNING);
        allow(PLANNING, APPROVAL);
        allow(APPROVAL, EXECUTING, PLANNING);          // rejected or edited plan goes back
        allow(EXECUTING, TESTING, PLANNING);           // PLANNING: requirements changed
        allow(TESTING, BUILDING, EXECUTING);           // EXECUTING: fix a failing test
        allow(BUILDING, VERIFYING, EXECUTING);         // EXECUTING: fix a failed build
        allow(VERIFYING, DELIVERING, EXECUTING, BUILDING);
        allow(DELIVERING, COMPLETED, VERIFYING);
        // A task that never reached CI may end where it stopped: a pure answer (ANALYZING) or
        // local changes without a build request (EXECUTING, TESTING). BUILDING/VERIFYING cannot
        // complete directly: an unverified build is paused, never "done".
        allow(ANALYZING, COMPLETED);
        allow(EXECUTING, COMPLETED);
        allow(TESTING, COMPLETED);
        // Suspension states are entered from any active state (below) and resume to the
        // state that was interrupted, enforced by TaskJournal.
        for (TaskState s : active()) {
            allow(s, PAUSED, WAITING_PERMISSION, WAITING_EXTERNAL, RECOVERING, FAILED, CANCELLED);
        }
        for (TaskState s : EnumSet.of(EXECUTING, TESTING, BUILDING, VERIFYING, DELIVERING)) {
            allow(s, ROLLED_BACK);
        }
        for (TaskState s : suspended()) allow(s, FAILED, CANCELLED);
        for (TaskState s : EnumSet.of(PAUSED, WAITING_PERMISSION, WAITING_EXTERNAL)) allow(s, RECOVERING);
        allow(FAILED, RECOVERING, ROLLED_BACK, CANCELLED);
        // COMPLETED, CANCELLED and ROLLED_BACK are terminal.
    }

    /** States in which the agent is making progress. */
    public static Set<TaskState> active() {
        return EnumSet.of(CREATED, ANALYZING, PLANNING, APPROVAL, EXECUTING, TESTING, BUILDING,
            VERIFYING, DELIVERING);
    }

    public static Set<TaskState> suspended() {
        return EnumSet.of(PAUSED, WAITING_PERMISSION, WAITING_EXTERNAL, RECOVERING);
    }

    public boolean isActive() { return active().contains(this); }
    public boolean isSuspended() { return suspended().contains(this); }
    public boolean isTerminal() { return this == COMPLETED || this == CANCELLED || this == ROLLED_BACK; }

    public Set<TaskState> allowedNext() {
        Set<TaskState> set = NEXT.get(this);
        return set == null ? Collections.<TaskState>emptySet() : Collections.unmodifiableSet(set);
    }

    public boolean canMoveTo(TaskState target) { return allowedNext().contains(target); }
}
