package com.gtnhplanner.data.flowchart.balancer.stages;

import java.util.Set;

import com.gtnhplanner.data.flowchart.balancer.Outcome;
import com.gtnhplanner.data.flowchart.balancer.SolveContext;
import com.gtnhplanner.data.flowchart.balancer.SolveResult;
import com.gtnhplanner.data.flowchart.balancer.Solver;
import com.gtnhplanner.data.flowchart.balancer.Stage;
import com.gtnhplanner.data.flowchart.balancer.StageOutcome;

/**
 * Internal flow: with the plan's trading held where the trade stage left it, the least total internal flow, then the
 * one canonical point
 * among that optimum ({@link Solver#canonicalHolding}). The point is committed to the context.
 */
public final class FlowMinStage implements Stage<Solver.Trades, StageOutcome> {

    @Override
    public String name() {
        return "internal flow";
    }

    @Override
    public Outcome<StageOutcome> run(final SolveContext ctx, final Solver.Trades plan) {
        final SolveResult s3 = Solver.leastFlowHolding(ctx, plan);
        if (s3.isRejected()) return new Outcome.Fail<>(s3.rejection());
        final StageOutcome best = Solver.canonicalHolding(ctx, plan, s3.point());
        ctx.commit(best);
        ctx.openGates = ctx.carryingGates(best.externals, Set.of());
        return new Outcome.Continue<>(best);
    }
}
