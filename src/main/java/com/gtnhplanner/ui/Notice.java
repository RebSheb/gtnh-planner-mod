package com.gtnhplanner.ui;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.gtnhplanner.data.flowchart.balancer.Severity;

/**
 * One line along the top of the board: something about the plan worth fixing or knowing, and the cards and drawers
 * it is about, which "Show me" frames. A notice may carry a key of its own instead ({@code actionLabel}, which runs
 * {@code action}): "Open" on a plan someone shared.
 */
public record Notice(Severity severity, String text, List<UUID> focus, @Nullable String actionLabel,
    @Nullable Runnable action) {

    public Notice(final Severity severity, final String text, final List<UUID> focus) {
        this(severity, text, focus, null, null);
    }

    /** Whether the row has a key at its end. */
    public boolean hasKey() {
        return action != null || !focus.isEmpty();
    }

    /** The key's words. */
    public String keyLabel() {
        return action != null && actionLabel != null ? actionLabel : "Show me";
    }
}
