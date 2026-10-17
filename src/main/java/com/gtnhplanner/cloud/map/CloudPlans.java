package com.gtnhplanner.cloud.map;

import java.util.List;

import javax.annotation.Nullable;

import com.google.gson.JsonObject;
import com.gtnhplanner.cloud.sync.LiveOp;
import com.gtnhplanner.cloud.sync.LiveOps;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.importer.ImportReport;

/**
 * A board as a shared plan and back (docs/design/cloud-live.md, "Game mapping"). The shared plan is the website's
 * {@code FactoryProject}, as the library's posting export writes it, each card, drawer, wire and note carrying the
 * game's own data for it ({@code gtnhplanner: {v: 1, game, sum}}) and the plan the game's data for the whole of it
 * (how it balances, setting pins, spare shares, groups and shared machines, its icon's key).
 *
 * <ul>
 * <li>Ids: the game's UUIDs are the plan's ids; a thing the website made keeps its id ({@link PlanIds}). A drawer's
 * link
 * is a wire {@code <drawer>:<card>:<port>}; a card's recipe is {@code gtnhplanner:<card>}.</li>
 * <li>Places: the posting export's scale, exact both ways ({@code PlanExport.position}, {@code gameX}).</li>
 * <li>Reading: a thing whose {@code sum} still matches its website fields is the game's own again; anything else the
 * importer's way, under its own id, keeping what the website has no place for.</li>
 * <li>Writing ({@link #write}), against the plan read and its {@link Basis}: what nobody changed is written as the plan
 * had it, so reading a plan and writing it straight back changes nothing.</li>
 * <li>Never in the plan: the camera, the pin list (each player's own), the solve's answers, and what the website works
 * out on load ({@link Derived}).</li>
 * </ul>
 */
public final class CloudPlans {

    /**
     * A plan read: the board, what each of its things was read as, what did not come over as it was, and the plan it
     * was read from (after the board was written, the plan it now stands for: {@link #after}).
     */
    public record Read(Graph graph, Basis basis, ImportReport report, JsonObject plan) {

        /** The same board once written as {@code w} and that write applied: {@code plan} is what it now stands for. */
        public Read after(final Written w, final JsonObject plan) {
            return new Read(graph, w.basis(), report, plan);
        }
    }

    /** A board written: the plan, and the basis to write the board against next time. */
    public record Written(JsonObject plan, Basis basis) {}

    private CloudPlans() {}

    /** A shared plan as a board. */
    public static Read read(final JsonObject plan, final CloudGame game) {
        return new PlanRead(plan, game, null).run();
    }

    /**
     * A shared plan as a board, following {@code previous}: a card whose JSON (and its recipe's) is the very object the
     * previous plan held is the previous board's card again, and every thing unchanged that way keeps what it was read
     * as, so only what a batch touched is read afresh (plans from {@link LiveOps#apply} share what a batch did not
     * touch). The previous board must not be used afterwards: its cards are the new board's.
     */
    public static Read read(final JsonObject plan, final CloudGame game, @Nullable final Read previous) {
        return new PlanRead(plan, game, previous).run();
    }

    /**
     * A board as a shared plan, against the plan it was read from and what that was read as (both null for a plan
     * new to the cloud, written whole).
     */
    public static Written write(final Graph graph, final CloudGame game, @Nullable final JsonObject base,
        @Nullable final Basis basis) {
        return new PlanWrite(graph, game, base, basis).run();
    }

    /** The edit that turns one plan into the other, what is worked out on load left out of both ({@link Derived}). */
    public static List<LiveOp> diff(final JsonObject before, final JsonObject after) {
        return LiveOps.diff(Derived.plan(before), Derived.plan(after));
    }
}
