package com.gtnhplanner.data.flowchart.balancer;

import java.util.List;
import java.util.Set;

import org.ojalgo.optimisation.Expression;
import org.ojalgo.optimisation.Optimisation;
import org.ojalgo.optimisation.Variable;
import org.ojalgo.optimisation.linear.LinearSolver;

/**
 * The LP/MILP machinery shared by every stage. Model ASSEMBLY now lives in one fluent place,
 * {@link ModelBuilder} - a stage declares its variables, its row families and its objective as a
 * chain and calls {@code solve()} - and this class holds the higher-level solves the AUTO chain is
 * built from (fast path, stage-1 filter/certification, stage-2 quantity, stage-3 flow and
 * canonicalization) plus the readers that turn a solved model into a {@link StageOutcome}.
 *
 * <p>
 * Keeping the math in one place lets a new stage be written against known-good primitives;
 * {@link ModelBuilder#portRows(boolean)} already lets the OUTPUT / INPUT stage express its own
 * (extern-free) family without a bespoke builder.
 */
public final class Solver {

    /**
     * A tie-break on the import variables of the extent-flow rescue, not a real cost: the count
     * objective (1.0 per machine) dominates, so the model still uses the fewest machines and only
     * then pulls the least it must from outside. Small enough that no realistic shortfall can
     * outweigh a machine.
     */
    private static final double IMPORT_WEIGHT = 1e-8;

    private Solver() {}

    /**
     * The simplex set-ups an AUTO model is solved under, in turn: ojAlgo's revised simplex with Ruiz equilibration
     * (scaled, the fastest here), then its own pick. ojAlgo 57.3.1 is not sure-footed on these models: one set-up can
     * call a model infeasible that the other solves, or return a point that does not conserve.
     */
    private static final List<LinearSolver.Configuration> OPINIONS = List
        .of(new LinearSolver.Configuration().equilibration(5), new LinearSolver.Configuration());

    /**
     * The one place an AUTO model runs: under each simplex set-up in turn ({@link #OPINIONS}) until one returns a
     * point that passes the independent conservation check. A point that passes proves the model has one; that it has
     * none is believed only when every set-up says so. The profiler hears every attempt.
     */
    public static SolveResult run(final SolveContext ctx, final Handles h, final String label) {
        SolveResult last = null;
        for (final LinearSolver.Configuration opinion : OPINIONS) {
            ctx.checkStop();
            h.model().options.linear(opinion);
            final long start = System.currentTimeMillis();
            final Optimisation.Result result = h.model()
                .minimise();
            final long millis = System.currentTimeMillis() - start;
            if (ctx.profiler.enabled()) {
                ctx.profiler.modelSolved(
                    label,
                    millis,
                    result.getState()
                        .toString(),
                    h.model()
                        .countVariables(),
                    h.model()
                        .countExpressions());
            }
            last = result.getState()
                .isFeasible() ? fromHandles(ctx, h, false)
                    : rejected(ctx, result, millis >= h.model().options.time_abort);
            if (!last.isRejected()) return last;
        }
        return last;
    }

    /** The AUTO extent lower bound: the pass-2 floors when a replay forced them, else zero. */
    private static double[] autoLower(final SolveContext ctx) {
        final int n = ctx.model.machines.size();
        final double[] lower = new double[n];
        if (ctx.floors.length > 0) {
            System.arraycopy(ctx.floors, 0, lower, 0, n);
        }
        return lower;
    }

    /**
     * Stage 3 / zero-gate fast path: gates fixed to {@code open}, minimize total internal flow.
     * Empty support and a zero cap make this the fast path (0 gates, 0 externals is trivially optimal).
     */
    public static SolveResult flowMinimal(final SolveContext ctx, final Set<Integer> open, final double qtyCap) {
        final Handles h = ModelBuilder.over(ctx)
            .extents(autoLower(ctx))
            .pools()
            .flows()
            .externals()
            .conservation()
            .drawers()
            .handles();
        closeGatesOutside(ctx, h, open);
        if (!open.isEmpty()) holdQuantity(ctx, h, qtyCap);
        flowObjective(h);
        final String label = open.isEmpty() ? "fast path" : "stage 3 flow";
        return run(ctx, h, label);
    }

    /**
     * Every gate open, each port's externals weighted by its gate's {@code gateWeight} (per craft of the machine at the
     * port): one round of the gate search's reweighted-L1 start. Each port's weight is nudged by its fixed tie weight,
     * a ten-thousandth at most, so the optimum is one point and not whichever of a tie the simplex reaches first.
     */
    public static SolveResult gateWeighted(final SolveContext ctx, final double[] gateWeight) {
        final Handles h = ModelBuilder.over(ctx)
            .extents(autoLower(ctx))
            .pools()
            .flows()
            .externals()
            .conservation()
            .drawers()
            .handles();
        for (int p = 0; p < ctx.model.connectedPorts.size(); p++) {
            h.extVars()[p]
                .weight(gateWeight[ctx.model.portGate[p]] * ctx.externalWeight(p) * (1 + 1e-4 * (tieWeight(p) - 1)));
        }
        return run(ctx, h, "gate weighted");
    }

    /** LP over the whole gate structure, raw-quantity objective; seeds the deletion filter. */
    public static SolveResult externalsLp(final SolveContext ctx, final Set<Integer> open) {
        final Handles h = ModelBuilder.over(ctx)
            .extents(autoLower(ctx))
            .pools()
            .flows()
            .externals()
            .conservation()
            .drawers()
            .handles();
        for (int p = 0; p < ctx.model.connectedPorts.size(); p++) {
            // null open = the permissive probe (every gate open, every external free):
            // diagnosePins uses it to test whether the pins are the conflict, and it must not
            // clamp anything or it would report infeasible.
            if (open != null && !open.contains(ctx.model.portGate[p])) {
                h.extVars()[p].upper(0);
            } else {
                h.extVars()[p].weight(ctx.importTilt(ctx.model.portGate[p]));
            }
        }
        return run(ctx, h, "externals");
    }

    /** Least external quantity over a fixed support: no binaries, so a plain LP. */
    public static SolveResult fixedQuantity(final SolveContext ctx, final Set<Integer> open) {
        final Handles h = ModelBuilder.over(ctx)
            .extents(autoLower(ctx))
            .pools()
            .flows()
            .externals()
            .conservation()
            .drawers()
            .handles();
        closeGatesOutside(ctx, h, open);
        for (int p = 0; p < ctx.model.connectedPorts.size(); p++) {
            h.extVars()[p].weight(ctx.externalWeight(p));
        }
        return run(ctx, h, "stage 2 fixed quantity");
    }

    /** The one canonical point among a stage-3 optimum: redistributes within it, deterministically. */
    public static StageOutcome canonicalize(final SolveContext ctx, final Set<Integer> open, final StageOutcome s3) {
        final Handles h = ModelBuilder.over(ctx)
            .extents(autoLower(ctx))
            .pools()
            .flows()
            .externals()
            .conservation()
            .drawers()
            .handles();
        final Expression qty = open.isEmpty() ? null
            : h.model()
                .addExpression("qty_cap");
        final double qtyEps = ctx.heuristics.numerics().qtyEps;
        final int ports = ctx.model.connectedPorts.size();
        for (int p = 0; p < ports; p++) {
            if (qty != null && open.contains(ctx.model.portGate[p])) {
                qty.set(h.extVars()[p], ctx.externalWeight(p));
                h.extVars()[p].weight(tieWeight(p) * ctx.externalWeight(p));
            } else {
                h.extVars()[p].upper(0);
            }
        }
        if (qty != null) {
            qty.upper(s3.externalQuantity * (1 + qtyEps) + qtyEps);
        }
        final Expression flowCap = h.model()
            .addExpression("flow_cap");
        for (int e = 0; e < h.flowVars().length; e++) {
            flowCap.set(h.flowVars()[e], 1.0);
            h.flowVars()[e].weight(tieWeight(e));
        }
        flowCap.upper(s3.internalFlow * (1 + qtyEps) + qtyEps * Math.max(1.0, s3.internalFlow));
        final SolveResult canonical = run(ctx, h, "canonicalise");
        return canonical.isRejected() ? s3 : canonical.point(); // the uncanonical point is still a correct answer
    }

    /**
     * Position {@code i}'s weight in the canonical tie-break, in [1, 2): a fixed scramble of the index (SplitMix64), so
     * no two different sets of edges or ports weigh the same. Evenly spaced weights tie whenever two index sums match,
     * and then the solver's path picks the point.
     */
    public static double tieWeight(final int i) {
        long z = (i + 1) * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return 1.0 + (z >>> 11) * 0x1.0p-53;
    }

    public static void closeGatesOutside(final SolveContext ctx, final Handles h, final Set<Integer> open) {
        for (int p = 0; p < ctx.model.connectedPorts.size(); p++) {
            if (!open.contains(ctx.model.portGate[p])) h.extVars()[p].upper(0);
        }
    }

    /**
     * Records what the solver actually said and yields the rejection every caller reads as
     * "nothing here". The state does not separate a model with no solution from a search that gave
     * up looking - ojAlgo returns INFEASIBLE for both - so a model that ran into its safety limit
     * ({@link Numerics#modelSafetyMillis}) says so in the reason too.
     */
    private static SolveResult rejected(final SolveContext ctx, final Optimisation.Result result,
        final boolean outOfTime) {
        final Note note = outOfTime ? new Note(SolverMessage.SOLVER_BUDGET, result.getState())
            : new Note(SolverMessage.SOLVER_UNSATISFIABLE, result.getState());
        ctx.profiler.pointRejected("solve", note.describe());
        return SolveResult.rejected(note);
    }

    private static void holdQuantity(final SolveContext ctx, final Handles h, final double optimum) {
        final Expression cap = h.model()
            .addExpression("hold_least_excess");
        for (int p = 0; p < ctx.model.connectedPorts.size(); p++) {
            cap.set(h.extVars()[p], ctx.externalWeight(p));
        }
        final double slack = ctx.heuristics.numerics().qtyEps;
        cap.upper(optimum * (1 + slack) + slack);
    }

    private static void flowObjective(final Handles h) {
        for (final Variable v : h.flowVars()) {
            v.weight(1.0);
        }
    }

    public static SolveResult fromHandles(final SolveContext ctx, final Handles h, final boolean provenOptimal) {
        final double[] extents = values(h.extentVars());
        final double[] flows = values(h.flowVars());
        final double[] externals = values(h.extVars());
        final String residual = ctx.validate(extents, flows, externals);
        if (residual != null) {
            final Note note = new Note(SolverMessage.SOLVER_NOT_CONSERVING, residual);
            ctx.profiler.pointRejected("solve", note.describe());
            return SolveResult.rejected(note);
        }
        return SolveResult.solved(StageOutcome.of(ctx, extents, flows, externals, provenOptimal));
    }

    private static double[] values(final Variable[] vars) {
        final double[] out = new double[vars.length];
        for (int i = 0; i < vars.length; i++) {
            final Number v = vars[i].getValue();
            out[i] = v == null ? 0 : v.doubleValue();
        }
        return out;
    }

    /**
     * Reads an extent-flow point (built with {@link ModelBuilder#portRows(boolean)}) with the
     * validation that family actually satisfies - the conservation-with-externals residual that
     * {@link #fromHandles} checks is the AUTO family's, and would falsely reject the OUTPUT /
     * INPUT 'at least' rows. Every claimed (exact) port's residual must be within tolerance of
     * zero, each 'at least' port's residual must stay non-negative (relative to {@code max(1, qty)}),
     * and no variable may sit below zero. Externals carry the imports the at-least rows pulled
     * from outside (zero on the claimed rows and on models built without {@link ModelBuilder#externals()});
     * {@link SolutionView} reports them as boundary "imports" chips. A model built with
     * {@link ModelBuilder#extentCounts()} reads integer machine counts and converts them to rate
     * extents (count * TPS/durTicks) so the point stays in the rate world.
     */
    public static SolveResult readExtentFlowModel(final SolveContext ctx, final Handles h, final boolean inputPriority,
        final boolean provenOptimal, final boolean counts) {
        final double[] countsOut = values(h.extentVars());
        final double[] extents = new double[countsOut.length];
        for (int i = 0; i < countsOut.length; i++) {
            extents[i] = counts ? countsOut[i] * (double) Numerics.TICKS_PER_SECOND / ctx.model.machines.get(i).durTicks
                : countsOut[i];
        }
        final double[] flows = values(h.flowVars());
        // The no-import model declares no externals at all (an empty array), which reads as a zero
        // import on every port rather than an index miss.
        final double[] externals = h.extVars().length == 0 ? new double[ctx.model.connectedPorts.size()]
            : values(h.extVars());
        final double floor = -ctx.heuristics.numerics().dust;
        for (final double extent : extents) {
            if (extent < floor) {
                final Note note = new Note(SolverMessage.SOLVER_NEGATIVE_EXTENT, extent);
                ctx.profiler.pointRejected("extent-min", note.describe());
                return SolveResult.rejected(note);
            }
        }
        for (final double flow : flows) {
            if (flow < floor) {
                final Note note = new Note(SolverMessage.SOLVER_NEGATIVE_FLOW, flow);
                ctx.profiler.pointRejected("extent-min", note.describe());
                return SolveResult.rejected(note);
            }
        }
        final double tol = ctx.heuristics.numerics().validateTol;
        for (int p = 0; p < ctx.model.connectedPorts.size(); p++) {
            final ModelData.ConnectedPort port = ctx.model.connectedPorts.get(p);
            if (port.qtyPerCraft() <= 0 || port.edges()
                .isEmpty()) continue;
            double flowsSum = 0;
            for (final int e : port.edges()) {
                flowsSum += flows[e];
            }
            final double residual = flowsSum + externals[p] - extents[port.machine()] * port.qtyPerCraft();
            final double scale = Math.max(1.0, port.qtyPerCraft());
            final boolean claimed = inputPriority == port.input();
            if (claimed) {
                if (Math.abs(residual) / scale > tol) {
                    final Note note = new Note(SolverMessage.SOLVER_INEXACT_RESIDUAL, residual);
                    ctx.profiler.pointRejected("extent-min", note.describe());
                    return SolveResult.rejected(note);
                }
            } else if (residual / scale < -tol) {
                final Note note = new Note(SolverMessage.SOLVER_UNDER_SUPPLY, residual);
                ctx.profiler.pointRejected("extent-min", note.describe());
                return SolveResult.rejected(note);
            }
        }
        return SolveResult.solved(StageOutcome.of(ctx, extents, flows, externals, provenOptimal));
    }

    /**
     * The OUTPUT / INPUT solve (the {@code ExtentMinStage} build), two-phase so imports never
     * displace normal operation. Phase 1 is the no-import balance - fewest whole machines
     * meeting the priority claims through the drawn edges only, with {@code min Σ count} and no
     * externals - and commits its point unchanged whenever the chart can balance on its own edges.
     * Only when that model is infeasible does phase 2 rescue the chart with external imports, and
     * even then counts stay primary: the SAME {@code min Σ count} objective, the at-least rows now
     * allowed to pull the shortfall from outside. A chart the edges can carry therefore never
     * imports, and a chart they cannot carries only the residual the fewest machines leave, never
     * a whole import the user could have avoided by building one more machine. An import is always
     * a genuine shortfall: a pin or a machine floor the drawn edges cannot cover.
     */
    public static SolveResult extentFlow(final SolveContext ctx, final boolean inputPriority) {
        final ModelBuilder natural = ModelBuilder.over(ctx)
            .extentCounts()
            .pools()
            .extentsWeighted(i -> 1.0)
            .flows()
            .portRows(inputPriority)
            .drawers();
        final Optimisation.Result naturalResult = natural.solve("extent-min");
        if (naturalResult.getState()
            .isFeasible()) {
            final Handles h = natural.handles();
            return readExtentFlowModel(
                ctx,
                h,
                inputPriority,
                naturalResult.getState()
                    .isOptimal(),
                true);
        }
        final ModelBuilder rescue = ModelBuilder.over(ctx)
            .extentCounts()
            .pools()
            .extentsWeighted(i -> 1.0)
            .flows()
            .externals()
            .importsWeighted(IMPORT_WEIGHT)
            .portRows(inputPriority)
            .drawers();
        final Optimisation.Result rescueResult = rescue.solve("extent-min rescue");
        if (!rescueResult.getState()
            .isFeasible()) {
            return SolveResult.rejected(ctx.rejection);
        }
        final Handles h = rescue.handles();
        return readExtentFlowModel(
            ctx,
            h,
            inputPriority,
            rescueResult.getState()
                .isOptimal(),
            true);
    }
}
