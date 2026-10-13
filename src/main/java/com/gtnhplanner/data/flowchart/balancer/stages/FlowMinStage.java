package com.gtnhplanner.data.flowchart.balancer.stages;

import java.util.List;
import java.util.Set;

import com.gtnhplanner.data.flowchart.balancer.GateSearch;
import com.gtnhplanner.data.flowchart.balancer.Outcome;
import com.gtnhplanner.data.flowchart.balancer.Parallel;
import com.gtnhplanner.data.flowchart.balancer.SolveContext;
import com.gtnhplanner.data.flowchart.balancer.SolveResult;
import com.gtnhplanner.data.flowchart.balancer.Solver;
import com.gtnhplanner.data.flowchart.balancer.Stage;
import com.gtnhplanner.data.flowchart.balancer.StageOutcome;

/**
 * Internal flow: each candidate's gates fixed open, its own least quantity held, the least total internal flow, then
 * the one canonical point among that optimum. The least flow wins; a tie there is a tie on every objective the solver
 * has, so it breaks on the lower ChoiceKey, a property of the chart rather than of the search. The champion is
 * committed to the context.
 */
public final class FlowMinStage implements Stage<ExternalMinStage.ExternalPlan, StageOutcome> {

    @Override
    public String name() {
        return "internal flow";
    }

    @Override
    public Outcome<StageOutcome> run(final SolveContext ctx, final ExternalMinStage.ExternalPlan plan) {
        final List<StageOutcome> solved = Parallel.map(ctx, plan.candidates(), (final GateSearch.Support cand) -> {
            final SolveResult s3 = Solver.flowMinimal(ctx, cand.open(), cand.point().externalQuantity);
            return s3.isRejected() ? null : Solver.canonicalize(ctx, cand.open(), s3.point());
        });
        StageOutcome best = null;
        Set<Integer> bestOpen = null;
        for (int i = 0; i < solved.size(); i++) {
            final StageOutcome s3 = solved.get(i);
            if (s3 == null) continue;
            if (best == null) {
                best = s3;
                bestOpen = plan.candidates()
                    .get(i)
                    .open();
                continue;
            }
            final double tol = ctx.tieTolerance(best.internalFlow, best);
            final boolean better = s3.internalFlow < best.internalFlow - tol;
            final boolean tied = Math.abs(s3.internalFlow - best.internalFlow) <= tol;
            if (better || tied && ctx.keyOf(s3.support)
                .compareTo(ctx.keyOf(best.support)) < 0) {
                best = s3;
                bestOpen = plan.candidates()
                    .get(i)
                    .open();
            }
        }
        if (best == null) return new Outcome.Fail<>(ctx.rejection);
        ctx.commit(best);
        ctx.openGates = bestOpen;
        return new Outcome.Continue<>(best);
    }
}
