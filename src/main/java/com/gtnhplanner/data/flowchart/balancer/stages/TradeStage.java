package com.gtnhplanner.data.flowchart.balancer.stages;

import java.util.Set;

import javax.annotation.Nullable;

import com.gtnhplanner.data.flowchart.balancer.Entry;
import com.gtnhplanner.data.flowchart.balancer.Outcome;
import com.gtnhplanner.data.flowchart.balancer.SolveContext;
import com.gtnhplanner.data.flowchart.balancer.SolveResult;
import com.gtnhplanner.data.flowchart.balancer.Solver;

/**
 * The AUTO chain's entry: where the plan trades with the market. When the pins cannot be met by the plan's own machines
 * alone, something is bought or sold at a wired port. The plan buys as far upstream as it can and sells as far
 * downstream as it can, always: level by level ({@link Solver#levels}), buying first, it trades as little as it can at
 * each, holding every level before. So it buys raw goods and runs its own machines rather than buying a part they could
 * make, never buys more in order to sell more, and sells spare only where nothing further on can take it. Each level is
 * one LP whose least value is a property of the plan, so the answer is too.
 */
public final class TradeStage implements Entry<Solver.Trades> {

    @Override
    public String name() {
        return "trades";
    }

    @Override
    public Outcome<Solver.Trades> run(final SolveContext ctx) {
        return settle(ctx, null);
    }

    /** The plan's trading settled level by level, trading only at {@code open}'s gates (every gate when null). */
    public static Outcome<Solver.Trades> settle(final SolveContext ctx, @Nullable final Set<Integer> open) {
        Solver.Trades held = Solver.Trades.within(open);
        for (final Solver.Level level : Solver.levels(ctx)) {
            final SolveResult least = Solver.leastAt(ctx, held, level);
            if (least.isRejected()) return new Outcome.Fail<>(least.rejection());
            held = held
                .holding(level, Solver.heldAt(ctx, level.measure(ctx, least.point().externals), least.point().extents));
        }
        return new Outcome.Continue<>(held);
    }
}
