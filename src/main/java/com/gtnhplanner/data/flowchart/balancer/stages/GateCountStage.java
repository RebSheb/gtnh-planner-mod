package com.gtnhplanner.data.flowchart.balancer.stages;

import java.util.List;

import com.gtnhplanner.data.flowchart.balancer.Entry;
import com.gtnhplanner.data.flowchart.balancer.GateSearch;
import com.gtnhplanner.data.flowchart.balancer.Outcome;
import com.gtnhplanner.data.flowchart.balancer.SolveContext;
import com.gtnhplanner.data.flowchart.balancer.SolverMessage;

/**
 * The AUTO chain's entry: gate count. Which gates open - the fewest, then the fewest imports - found exactly by
 * {@link GateSearch} from LP feasibility tests, with every other set that ties with it, for the next stages to choose
 * between on quantity and flow. When the search's counted budget runs out before it found a least set, a deletion
 * filter's minimal set stands and the plan says the count is not proven least.
 */
public final class GateCountStage implements Entry<GateCountStage.GatePlan> {

    @Override
    public String name() {
        return "gate count";
    }

    @Override
    public Outcome<GatePlan> run(final SolveContext ctx) {
        final Outcome<GateSearch.Result> searched = GateSearch.run(ctx);
        if (searched instanceof final Outcome.Fail<GateSearch.Result> fail) return new Outcome.Fail<>(fail.reason());
        final GateSearch.Result result = ((Outcome.Continue<GateSearch.Result>) searched).value();
        ctx.neededGates = result.needed();
        if (!result.certified()) {
            ctx.stageNotes.add(
                SolverMessage.GATE_COUNT_NOT_CERTIFIED.toNote(
                    result.ties()
                        .get(0)
                        .open()
                        .size()));
        }
        return new Outcome.Continue<>(new GatePlan(result.ties(), result.certified()));
    }

    /**
     * The gate count's hand-off: every least set of gates found (with the least-quantity point over each), in a fixed
     * order, and whether they are proven least.
     */
    public record GatePlan(List<GateSearch.Support> ties, boolean certified) {

        public GatePlan {
            ties = List.copyOf(ties);
        }
    }
}
