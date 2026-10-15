package com.gtnhplanner.data.flowchart.balancer.alternatives;

import javax.annotation.Nullable;

import com.gtnhplanner.data.flowchart.balancer.Note;
import com.gtnhplanner.data.flowchart.balancer.SolverMessage;

/**
 * Why an answer is not the default. Everything past the gate count is a preference rather than
 * a fact - the sink-over-source tilt is a constant somebody chose, and "least material moved" is
 * a taste - so the reason travels with the option and gets shown, instead of quietly deciding on
 * the user's behalf. The strings and their ordering are part of the persisted choices format.
 */
public enum Rank {

    /** What the solver returned; nothing to say, so it carries no note key. */
    DEFAULT(null),
    /** Indistinguishable on every objective; only node ordering separated the two. */
    EQUALLY_VALID(SolverMessage.REASON_EQUALLY_VALID),
    /** Lost on buying: it buys more, or buys a part further down the line where the default buys what makes it. */
    BUYS_MORE(SolverMessage.REASON_BUYS_MORE),
    /** Lost on selling: it sells more, or sells spare earlier in the line where the default works it further. */
    SELLS_MORE(SolverMessage.REASON_SELLS_MORE),
    /** Lost on flow: it trades as the default does, but more material moves inside the plan. */
    MOVES_MORE(SolverMessage.REASON_MOVES_MORE),
    /**
     * Trades as the default does and moves LESS material than it. Reachable because the default's tie enumeration is
     * capped and a one-gate swap can step outside what it reached.
     */
    MOVES_LESS(SolverMessage.REASON_MOVES_LESS);

    /** The reason's note key; null only for {@link #DEFAULT}, which has nothing to say. */
    private final @Nullable SolverMessage msg;

    Rank(final @Nullable SolverMessage msg) {
        this.msg = msg;
    }

    public @Nullable Note toNote() {
        return msg == null ? null : msg.toNote();
    }
}
