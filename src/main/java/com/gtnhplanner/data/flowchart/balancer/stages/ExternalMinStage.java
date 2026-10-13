package com.gtnhplanner.data.flowchart.balancer.stages;

import java.util.ArrayList;
import java.util.List;

import com.gtnhplanner.data.flowchart.balancer.GateSearch;
import com.gtnhplanner.data.flowchart.balancer.Outcome;
import com.gtnhplanner.data.flowchart.balancer.SolveContext;
import com.gtnhplanner.data.flowchart.balancer.Stage;
import com.gtnhplanner.data.flowchart.balancer.StageOutcome;

/**
 * External quantity: of the least gate sets, the ones that lean least on the outside. Each set's least quantity is its
 * own LP's (solved by the gate search, {@link GateSearch.Support#point()}); every set within the tie tolerance of the
 * least goes on to the internal-flow stage, in the gate count's order.
 */
public final class ExternalMinStage implements Stage<GateCountStage.GatePlan, ExternalMinStage.ExternalPlan> {

    @Override
    public String name() {
        return "external quantity";
    }

    @Override
    public Outcome<ExternalPlan> run(final SolveContext ctx, final GateCountStage.GatePlan plan) {
        StageOutcome least = null;
        for (final GateSearch.Support s : plan.ties()) {
            if (least == null || s.point().externalQuantity < least.externalQuantity) least = s.point();
        }
        if (least == null) return new Outcome.Fail<>(ctx.rejection);
        final double tol = ctx.tieTolerance(least.externalQuantity, least);
        final List<GateSearch.Support> candidates = new ArrayList<>();
        for (final GateSearch.Support s : plan.ties()) {
            if (s.point().externalQuantity <= least.externalQuantity + tol) candidates.add(s);
        }
        return new Outcome.Continue<>(new ExternalPlan(candidates));
    }

    /** The external-quantity stage's hand-off: the least gate sets tied at the least quantity, in order. */
    public record ExternalPlan(List<GateSearch.Support> candidates) {

        public ExternalPlan {
            candidates = List.copyOf(candidates);
        }
    }
}
