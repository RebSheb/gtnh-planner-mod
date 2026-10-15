package com.gtnhplanner.data.flowchart.balancer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

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

    /** The AUTO extent lower bound: zero (every machine is free to run at any rate the balance gives it). */
    private static double[] autoLower(final SolveContext ctx) {
        return new double[ctx.model.machines.size()];
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
     * One step of AUTO's trading: the ports where the plan would buy (or sell) goods {@code steps} machine steps along
     * ({@link ModelData#portSteps}), what is traded there measured in crafts of the machines at them.
     */
    public record Level(boolean buying, int steps, int[] ports) {

        /** The same level over only {@code subset} of its ports. */
        public Level of(final List<Integer> subset) {
            return new Level(
                buying,
                steps,
                subset.stream()
                    .mapToInt(Integer::intValue)
                    .toArray());
        }

        /** What the plan trades at this level at a point, in crafts. */
        public double measure(final SolveContext ctx, final double[] externals) {
            double total = 0;
            for (final int p : ports) total += externals[p] * ctx.externalWeight(p);
            return total;
        }
    }

    /**
     * The plan's trading levels in the order AUTO settles them: buying first, the most processed goods first (the most
     * steps already in them), so the plan buys raw goods and runs its own machines and never buys more in order to sell
     * more; then selling, the goods with the most steps still ahead of them first, so spare goes on to every machine
     * that can take it and is sold only where nothing further on can.
     */
    public static List<Level> levels(final SolveContext ctx) {
        // Keyed so that ascending order is the order above: buying (0) before selling (1), then steps descending.
        final java.util.TreeMap<Long, List<Integer>> byLevel = new java.util.TreeMap<>();
        for (int p = 0; p < ctx.model.connectedPorts.size(); p++) {
            final boolean buying = ctx.model.connectedPorts.get(p)
                .input();
            byLevel.computeIfAbsent(((buying ? 0L : 1L) << 32) - ctx.model.portSteps[p], k -> new ArrayList<>())
                .add(p);
        }
        final List<Level> out = new ArrayList<>(byLevel.size());
        for (final List<Integer> ports : byLevel.values()) {
            final int first = ports.get(0);
            out.add(
                new Level(
                    ctx.model.connectedPorts.get(first)
                        .input(),
                    ctx.model.portSteps[first],
                    ports.stream()
                        .mapToInt(Integer::intValue)
                        .toArray()));
        }
        return out;
    }

    /**
     * What AUTO's trading is held to: per level settled so far, at most what it was solved to (at most zero closes its
     * ports outright, so no later step can trade a sliver there to save a sliver of its own). {@code open}, when not
     * null, is the only gates the plan may trade at: a support the choices search tries.
     */
    public record Trades(@Nullable Set<Integer> open, List<Level> levels, List<Double> caps) {

        public Trades {
            levels = List.copyOf(levels);
            caps = List.copyOf(caps);
        }

        /** Only {@code open}'s gates (every gate when null), nothing held yet. */
        public static Trades within(@Nullable final Set<Integer> open) {
            return new Trades(open, List.of(), List.of());
        }

        public Trades holding(final Level level, final double cap) {
            final List<Level> l = new ArrayList<>(levels);
            final List<Double> c = new ArrayList<>(caps);
            l.add(level);
            c.add(cap);
            return new Trades(open, l, c);
        }
    }

    /**
     * Where a level is held once solved: exactly where it was reached, so no later level can buy or sell a sliver there
     * to save something of its own. Measured against how fast the plan's machines run ({@code extents}), a billionth
     * is rounding, not a trade: reaching only that trades nothing, and closes the level outright.
     */
    public static double heldAt(final SolveContext ctx, final double reached, final double[] extents) {
        final double rounding = 1e-9 * Math.max(1, SolveContext.scaleOf(extents));
        return reached <= rounding ? 0 : reached;
    }

    /** The least the plan can trade at {@code level}, with {@code held}'s levels held. */
    public static SolveResult leastAt(final SolveContext ctx, final Trades held, final Level level) {
        final Handles h = tradeModel(ctx, held);
        for (final int p : level.ports()) h.extVars()[p].weight(ctx.externalWeight(p));
        return run(ctx, h, (level.buying() ? "least buying at " : "least selling at ") + level.steps() + " steps");
    }

    /** The least internal flow with the plan's trading held. */
    public static SolveResult leastFlowHolding(final SolveContext ctx, final Trades held) {
        final Handles h = tradeModel(ctx, held);
        flowObjective(h);
        return run(ctx, h, "stage 3 flow");
    }

    /**
     * The one canonical point among the least-flow optimum with the plan's trading held: what crosses the plan's edge
     * weighed far above what flows inside, each port and wire by its fixed tie weight.
     */
    public static StageOutcome canonicalHolding(final SolveContext ctx, final Trades held, final StageOutcome s3) {
        final Handles h = tradeModel(ctx, held);
        for (int p = 0; p < h.extVars().length; p++) {
            h.extVars()[p].weight(EDGE_FIRST * tieWeight(p) * ctx.externalWeight(p));
        }
        final double qtyEps = ctx.heuristics.numerics().qtyEps;
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

    private static Handles tradeModel(final SolveContext ctx, final Trades held) {
        final Handles h = ModelBuilder.over(ctx)
            .extents(autoLower(ctx))
            .pools()
            .flows()
            .externals()
            .conservation()
            .drawers()
            .handles();
        if (held.open() != null) closeGatesOutside(ctx, h, held.open());
        for (int i = 0; i < held.levels()
            .size(); i++) {
            final Level level = held.levels()
                .get(i);
            final double cap = held.caps()
                .get(i);
            if (cap <= 0) {
                for (final int p : level.ports()) h.extVars()[p].upper(0);
                continue;
            }
            final Expression hold = h.model()
                .addExpression("hold_level_" + i);
            for (final int p : level.ports()) hold.set(h.extVars()[p], ctx.externalWeight(p));
            hold.upper(cap);
        }
        return h;
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

    /** How much more the canonical tie-break weighs what crosses the plan's edge than what flows inside it. */
    private static final double EDGE_FIRST = 1e6;

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
                // Far above any flow's weight, so the quantity's rounding room is never spent moving material across
                // the plan's edge to save flow inside it: that would be a leftover a millionth of a unit wide.
                h.extVars()[p].weight(EDGE_FIRST * tieWeight(p) * ctx.externalWeight(p));
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
