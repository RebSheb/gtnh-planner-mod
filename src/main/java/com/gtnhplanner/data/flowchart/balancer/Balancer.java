package com.gtnhplanner.data.flowchart.balancer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.gtnhplanner.Config;
import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.balancer.alternatives.Alternative;
import com.gtnhplanner.data.flowchart.balancer.alternatives.Alternatives;
import com.gtnhplanner.data.flowchart.balancer.alternatives.Enumerator;
import com.gtnhplanner.data.flowchart.balancer.alternatives.Preference;
import com.gtnhplanner.data.flowchart.balancer.alternatives.Rank;
import com.gtnhplanner.data.properties.RecipeProperty;

/**
 * The single entry point of the balancer package. Every {@link BalanceMode} is a {@link Chain}:
 * NONE is an empty chain that leaves the point untested, OUTPUT / INPUT are each one
 * {@code ExtentMinStage} pick, and AUTO is the full lexicographic chain plus its pass-2 replay.
 * The chain is the whole difference between the modes; {@link #solve(SolveInput)} is the read-out
 * over that machinery.
 *
 * <p>
 * Everything runs on a {@link SolveInput}, the plain-data snapshot of a chart: the entry points
 * that take a {@link Graph} take the snapshot first, on the calling thread, and are kept for the
 * callers and tests written against the graph. Only {@link SolveInput#of} touches the graph, the
 * machine effects and the ingredient names, so every {@code SolveInput} entry here is safe on any
 * thread.
 *
 * <p>
 * Drawers ride on every mode: their rules are rows in every model, an at-least or exactly drawer
 * anchors AUTO the way a pin does, and a run that stalls with drawers in the model gets one more
 * try with the drawers that cannot be kept loosened to what the chart can do
 * ({@link DrawerRelaxation}), so the plan keeps its numbers and says which drawers fall short.
 */
public final class Balancer {

    private Balancer() {}

    // ── Runs (the chain and its settlement, for tests and the profiler) ──

    /**
     * Runs the mode's chain against the chart and returns the context plus its {@link Settlement}
     * (a point, or the reason there is none).
     */
    public static RunContext run(final BalanceMode mode, final Graph graph) {
        return run(mode, graph, Profiler.disabled());
    }

    /** {@link #run(SolveInput, Profiler)} on a snapshot of the graph in {@code mode}. */
    public static RunContext run(final BalanceMode mode, final Graph graph, final Profiler profiler) {
        return run(SolveInput.of(graph, mode, null), profiler);
    }

    /**
     * The run with an instrumentation hook attached. The profiler's {@code runStarted} event
     * fires with the model's size, each chain element reports through it, and {@code runFinished}
     * hands back a {@link SolutionView} - only ever built here, when a profiler is actually
     * attached, so the default path never constructs it. The input's stored choice is not applied.
     */
    public static RunContext run(final SolveInput input, final Profiler profiler) {
        final BalanceMode mode = input.mode();
        final Supplier<SolveContext> fresh = () -> ctxOf(input, profiler);
        final SolveContext ctx = fresh.get();
        profiler.runStarted(
            mode,
            ctx.model.machines.size(),
            ctx.model.connectedPorts.size(),
            ctx.model.gates.size(),
            ctx.model.edges.size());
        final long start = System.currentTimeMillis();
        // Unpinned chart = wiring, not a problem to solve: AUTO reports the NO_PIN idle answer.
        // The OUTPUT / INPUT modes solve regardless - their "fewest machines" LP needs no scale anchor.
        if (mode == BalanceMode.AUTO && !ctx.anchored) {
            profiler.runFinished(null, SolverMessage.NO_PIN.describe());
            return new RunContext(ctx, new Settlement.Stalled(SolverMessage.NO_PIN.toNote()));
        }
        final RunContext settled = settle(mode, ctx, fresh);
        final SolveContext done = settled.ctx();
        final SolutionView view = profiler.enabled() && done.point() != null
            ? SolutionView.of(done, System.currentTimeMillis() - start)
            : null;
        profiler.runFinished(
            view,
            settled.settlement() instanceof Settlement.Stalled(Note reason) ? reason.describe() : null);
        return settled;
    }

    /** The context after a run plus how it ended: a point, or the reason it stalled. */
    public record RunContext(SolveContext ctx, Settlement settlement) {}

    /**
     * Runs the mode's chain on {@code ctx}; when it stalls with drawer bounds in the model, relaxes
     * the drawers that cannot be kept and runs once more on a {@code fresh} context under the
     * loosened bounds. The context returned is the one the settlement belongs to.
     */
    private static RunContext settle(final BalanceMode mode, final SolveContext ctx,
        final Supplier<SolveContext> fresh) {
        final Settlement first = mode.chain()
            .run(ctx);
        if (!(first instanceof Settlement.Stalled) || !ctx.hasDrawerBounds()) {
            return new RunContext(ctx, first);
        }
        final DrawerRelaxation.Outcome relaxed = DrawerRelaxation.run(ctx);
        if (relaxed == null) return new RunContext(ctx, first);
        final SolveContext retry = fresh.get();
        relaxed.applyTo(retry);
        return new RunContext(
            retry,
            mode.chain()
                .run(retry));
    }

    /** The shared context each entry point runs its mode's chain under; see {@link #run}. */
    private static SolveContext ctxOf(final SolveInput input, final Profiler profiler) {
        return ctxOf(input, profiler, NEVER);
    }

    private static SolveContext ctxOf(final SolveInput input, final Profiler profiler, final BooleanSupplier stop) {
        final BalanceMode mode = input.mode();
        return new SolveContext(
            input,
            mode.heuristics(),
            stop,
            input.extentPins(),
            mode.pins(),
            mode == BalanceMode.AUTO,
            profiler);
    }

    // ── Alternatives ──

    /**
     * Runs the mode's chain against the chart and then, when the mode has a choices surface
     * ({@link BalanceMode#supportsAlternatives()}), the alternatives search around the committed
     * point. A stored {@link ChoiceKey} that cannot be resolved or needs more gates than the
     * answer produces the same notes the corpus keys on, and the point stays the solver's own.
     */
    public static Alternatives alternatives(final BalanceMode mode, final Graph graph) {
        return alternatives(mode, graph, null, Map.of());
    }

    /**
     * As above, but with a stored {@link ChoiceKey} to honour (applied between the solve and the
     * enumeration) and per-machine extent pins.
     */
    public static Alternatives alternatives(final BalanceMode mode, final Graph graph, @Nullable final ChoiceKey choice,
        final Map<UUID, Double> extraExtentPins) {
        return alternatives(SolveInput.of(graph, mode, choice, extraExtentPins));
    }

    /** The alternatives search over a snapshot, honouring its stored choice. */
    public static Alternatives alternatives(final SolveInput input) {
        final BalanceMode mode = input.mode();
        final Supplier<SolveContext> fresh = () -> ctxOf(input, Profiler.disabled());
        final SolveContext first = fresh.get();
        if (mode == BalanceMode.AUTO && !first.anchored || first.model.machines.isEmpty()) {
            return new Alternatives(null, List.of(), true, List.of());
        }
        final RunContext settled = settle(mode, first, fresh);
        final SolveContext ctx = settled.ctx();
        if (settled.settlement() instanceof final Settlement.Stalled stalled || ctx.point() == null) {
            return new Alternatives(null, List.of(), true, List.of());
        }
        if (!mode.supportsAlternatives()) {
            // The simple modes have no choices to offer; still answer with an empty, complete list
            // so the panel renders nothing rather than a broken promise.
            return new Alternatives(ctx.keyOf(ctx.support()), List.of(), true, List.of());
        }
        if (input.choice() != null) {
            ctx.commit(Enumerator.applyChoice(ctx, input.choice()));
        }
        return Enumerator.enumerate(ctx);
    }

    // ── Solve and enumerate ──

    /**
     * {@link #solveWithAlternatives(SolveInput)} on a snapshot of the graph.
     *
     * @param choice          a stored {@link ChoiceKey} to honour between the solve and the
     *                        enumeration, or null for the solver's own answer.
     * @param extraExtentPins per-machine extent pins (crafts/s), e.g. from a target-rate pin.
     */
    public static Answer solveWithAlternatives(final BalanceMode mode, final Graph graph,
        @Nullable final ChoiceKey choice, final Map<UUID, Double> extraExtentPins) {
        return solveWithAlternatives(SolveInput.of(graph, mode, choice, extraExtentPins));
    }

    /**
     * Solves and enumerates in one pass, so a reader does not pay for the whole pipeline twice -
     * once to draw the chart and again to ask what else it could have been. Unlike {@link #run},
     * the {@link SolutionView} is always built: it is what the board reads (the boundary, the drawer
     * read-out, the solver notes). Any thread.
     */
    public static Answer solveWithAlternatives(final SolveInput input) {
        return solveWithAlternatives(input, null, NEVER);
    }

    /** Never stops: a solve nobody can make stale. */
    private static final BooleanSupplier NEVER = () -> false;

    /**
     * A solve stopped because a newer edit made its answer worthless ({@link SolveContext#checkStop}): never an answer,
     * only a way out. Islands it finished stay in the cache.
     */
    public static final class Stopped extends RuntimeException {

        public Stopped() {
            super(null, null, false, false);
        }
    }

    /**
     * As above, each island of the plan ({@link Islands}) solved by itself, side by side, and those {@code cache}
     * already holds not solved again; the islands' answers are read together as one plan's (one {@link SolutionView},
     * whose notes look across islands). A plan none of whose islands solved answers as one plan does.
     */
    public static Answer solveWithAlternatives(final SolveInput input, @Nullable final Cache cache,
        final BooleanSupplier stop) {
        if (cache == null) return solveIslands(input, null, stop);
        boolean stopped = true;
        try {
            final Answer answer = solveIslands(input, cache, stop);
            stopped = false;
            return answer;
        } finally {
            cache.finish(stopped);
        }
    }

    private static Answer solveIslands(final SolveInput input, @Nullable final Cache cache,
        final BooleanSupplier stop) {
        final long start = System.currentTimeMillis();
        final List<SolveInput> islands = Islands.of(input);
        if (islands.size() == 1) return piece(input, cache, stop).answer();
        final List<Piece> pieces = Parallel.map(islands, island -> piece(island, cache, stop));
        boolean anySolved = false;
        for (final Piece p : pieces) anySolved |= p.answer() instanceof Answer.Solved;
        if (!anySolved) return piece(input, null, stop).answer();

        final SolveContext whole = ctxOf(input, Profiler.disabled());
        final ModelData model = whole.model;
        final double[] extents = new double[model.machines.size()];
        final double[] flows = new double[model.edges.size()];
        final double[] externals = new double[model.connectedPorts.size()];
        final Map<UUID, Integer> edgeIndex = new HashMap<>();
        for (int e = 0; e < model.edges.size(); e++) edgeIndex.put(
            model.edges.get(e)
                .id(),
            e);
        final Set<Integer> support = new HashSet<>();
        final List<Alternative> options = new ArrayList<>();
        final List<Note> choiceNotes = new ArrayList<>();
        final List<Spares.Leftover> spares = new ArrayList<>();
        boolean complete = true;
        for (final Piece p : pieces) {
            final SolveContext c = p.ctx();
            if (p.answer() instanceof final Answer.Failed failed) {
                // An island with nothing to size is idle, not broken; any other stands unsolved, and says why.
                if (failed.failure() != null && failed.failure()
                    .message() != SolverMessage.NO_PIN) {
                    whole.stageNotes.add(failed.failure());
                    whole.stageNotes.addAll(
                        failed.notes()
                            .subList(Math.min(c.constructionNotes, failed.notes().size()), failed.notes().size()));
                }
                continue;
            }
            final Answer.Solved solved = (Answer.Solved) p.answer();
            for (int m = 0; m < c.model.machines.size(); m++) {
                extents[model.machineIndex.get(c.model.machines.get(m).spec.id())] = c.extents()[m];
            }
            for (int e = 0; e < c.model.edges.size(); e++) {
                flows[edgeIndex.get(
                    c.model.edges.get(e)
                        .id())] = c.flows()[e];
            }
            for (int q = 0; q < c.model.connectedPorts.size(); q++) {
                externals[portOf(whole, c.refOf(q))] = c.externals()[q];
            }
            for (final int g : c.support()) {
                support.add(
                    model.portGate[portOf(
                        whole,
                        c.refOf(
                            c.model.gates.get(g)
                                .ports()
                                .get(0)))]);
            }
            whole.floorsUsed |= c.floorsUsed;
            whole.stageNotes.addAll(c.notes.subList(c.constructionNotes, c.notes.size()));
            whole.stageNotes.addAll(c.stageNotes);
            whole.unmetDrawers.addAll(c.unmetDrawers);
            whole.shortfalls.addAll(c.shortfalls);
            options.addAll(
                solved.alternatives()
                    .options());
            choiceNotes.addAll(
                solved.alternatives()
                    .notes());
            complete &= solved.alternatives()
                .complete();
            spares.addAll(
                solved.solution()
                    .spares());
        }
        whole.commit(StageOutcome.of(whole, extents, flows, externals, support, false));
        // Each option keeps its island's key: a stored choice applies to the islands its anchors sit on (Islands).
        final List<Alternative> planOptions = new ArrayList<>(options);
        final List<Rank> displayOrder = Preference.displayOrder();
        planOptions.sort(
            Comparator.comparing((final Alternative option) -> option.replaces(), PortRef.ORDER)
                .thenComparingInt(option -> displayOrder.indexOf(option.rank()))
                .thenComparing(Alternative::key));
        final SolutionView view = SolutionView.of(whole, System.currentTimeMillis() - start);
        // In the plan's order of decisions, as one plan's leftovers come.
        spares.sort(Comparator.comparing(Balancer::decisionOf, PortRef.ORDER));
        view.spares(List.copyOf(spares));
        return new Answer.Solved(
            view,
            new Alternatives(whole.keyOf(support), List.copyOf(planOptions), complete, List.copyOf(choiceNotes)));
    }

    /** The place the solver itself put a leftover: the decision it is listed under. */
    private static PortRef decisionOf(final Spares.Leftover leftover) {
        for (final Spares.Bearer b : leftover.bearers()) if (b.solverPick()) return b.anchor();
        return leftover.bearers()
            .get(0)
            .anchor();
    }

    /** The plan's connected port for {@code ref}. */
    private static int portOf(final SolveContext ctx, final PortRef ref) {
        return ctx.model.portLookup
            .get(ModelData.portKey(ctx.model.machineIndex.get(ref.nodeId()), ref.portIndex(), ref.input()));
    }

    /** An island's answer, from {@code cache} when it holds the island, else solved and kept there. */
    private static Piece piece(final SolveInput island, @Nullable final Cache cache, final BooleanSupplier stop) {
        if (cache == null) return solvePiece(island, stop);
        final Piece kept = cache.get(island);
        if (kept != null) return kept;
        final Piece solved = solvePiece(island, stop);
        cache.put(island, solved);
        return solved;
    }

    /** One island solved, with the context that solved it (whose point is the answer's, when there is one). */
    record Piece(SolveContext ctx, Answer answer) {}

    /**
     * Islands already solved, by their snapshot and the solver's effort: an edit re-solves only the island it touched,
     * and undo finds the answer it had. It keeps the islands of the last two solves, however many. A
     * {@link SolveService} keeps one for its plan.
     */
    public static final class Cache {

        /** The islands of the last solve. */
        private Map<Object, Piece> last = new HashMap<>();
        /** The islands of the solve before it, so undo finds its answer. */
        private Map<Object, Piece> before = new HashMap<>();
        /** The islands of the solve under way. */
        private Map<Object, Piece> current = new HashMap<>();

        @Nullable
        synchronized Piece get(final SolveInput island) {
            final Object key = keyOf(island);
            Piece piece = last.get(key);
            if (piece == null) piece = before.get(key);
            if (piece != null) current.put(key, piece);
            return piece;
        }

        synchronized void put(final SolveInput island, final Piece piece) {
            current.put(keyOf(island), piece);
        }

        /**
         * A solve is over: its islands become the last, the last the ones before. A stopped solve keeps what it did
         * finish beside the last, so its next try starts there.
         */
        synchronized void finish(final boolean stopped) {
            if (stopped) {
                last.putAll(current);
            } else {
                before = last;
                last = current;
            }
            current = new HashMap<>();
        }

        private static Object keyOf(final SolveInput island) {
            return List.of(island, Config.solverEffort());
        }
    }

    private static Piece solvePiece(final SolveInput input, final BooleanSupplier stop) {
        final BalanceMode mode = input.mode();
        final Supplier<SolveContext> fresh = () -> ctxOf(input, Profiler.disabled(), stop);
        final SolveContext first = fresh.get();
        final long start = System.currentTimeMillis();
        if (first.model.machines.isEmpty()) {
            return new Piece(first, new Answer.Failed(SolverMessage.EMPTY_GRAPH.toNote()));
        }
        if (mode == BalanceMode.AUTO && !first.anchored) {
            // Idle, not broken. Rules that only cap (at most) or drawers asking for nothing size
            // nothing, which is worth saying when the player has set some.
            final List<Note> notes = new ArrayList<>(first.notes);
            if (first.hasDrawerBounds()) notes.add(SolverMessage.DRAWER_ONLY_UPPER_BOUNDS.toNote());
            return new Piece(first, new Answer.Failed(SolverMessage.NO_PIN.toNote(), notes, DrawerReadout.unsolved(first)));
        }
        final RunContext settled = settle(mode, first, fresh);
        final SolveContext ctx = settled.ctx();
        if (settled.settlement() instanceof final Settlement.Stalled stalled) {
            return new Piece(ctx, new Answer.Failed(stalled.reason(), ctx.notes, DrawerReadout.unsolved(ctx)));
        }
        if (ctx.point() == null) {
            return new Piece(
                ctx,
                new Answer.Failed(
                    SolverMessage.BALANCE_FAILED.toNote(ctx.rejection),
                    ctx.notes,
                    DrawerReadout.unsolved(ctx)));
        }
        if (input.choice() != null && mode.supportsAlternatives()) {
            ctx.commit(Enumerator.applyChoice(ctx, input.choice()));
        }
        // The independent conservation check is AUTO's contract - the extent-flow family's positive
        // "at least" residuals (OUTPUT / INPUT) legitimately fail it, so those modes never route
        // through this entry.
        if (mode == BalanceMode.AUTO) {
            final String residualError = ctx.validate(ctx.extents(), ctx.flows(), ctx.externals());
            if (residualError != null) {
                return new Piece(
                    ctx,
                    new Answer.Failed(
                        SolverMessage.VALIDATION_FAILED.toNote(residualError),
                        ctx.notes,
                        DrawerReadout.unsolved(ctx)));
            }
        }
        SolutionView view = SolutionView.of(ctx, System.currentTimeMillis() - start);
        if (!mode.supportsAlternatives()) {
            return new Piece(
                ctx,
                new Answer.Solved(view, new Alternatives(ctx.keyOf(ctx.support()), List.of(), true, List.of())));
        }
        final int notesBefore = ctx.notes.size(), stageNotesBefore = ctx.stageNotes.size();
        final Map<ChoiceKey, StageOutcome> points = new HashMap<>();
        final Alternatives alternatives = Enumerator.enumerate(ctx, points);
        final StageOutcome own = ctx.point();
        final List<Spares.Leftover> spares = Spares.apply(ctx, alternatives, points, input.spares());
        if (ctx.point() != own) {
            // A stored split moved the point: the view is read again over the mix, saying what the solve said.
            trim(ctx.notes, notesBefore);
            trim(ctx.stageNotes, stageNotesBefore);
            view = SolutionView.of(ctx, System.currentTimeMillis() - start);
        }
        view.spares(spares);
        return new Piece(ctx, new Answer.Solved(view, alternatives));
    }

    private static void trim(final List<Note> notes, final int size) {
        if (notes.size() > size) notes.subList(size, notes.size())
            .clear();
    }

    /** A solved chart plus the other answers it could have had, or the failure that prevented both. */
    public sealed interface Answer permits Answer.Solved,Answer.Failed {

        /**
         * The solve committed a usable point and the enumeration that goes with it. The drawer
         * read-out is on the view: {@code solution().drawers}.
         */
        record Solved(SolutionView solution, Alternatives alternatives) implements Answer {}

        /**
         * The solve committed no point; {@code failure} is why. {@code notes} are what the run said
         * besides (drawers not connected, drawers that cannot be met), and {@code drawers} what it
         * learned about the drawers without a point: which are unmet, never their rates.
         */
        record Failed(Note failure, List<Note> notes, DrawerReadout drawers) implements Answer {

            public Failed {
                notes = List.copyOf(notes);
            }

            public Failed(final Note failure) {
                this(failure, List.of(), DrawerReadout.NONE);
            }
        }
    }

    // ── The read-out ──

    /**
     * The graph's balance in {@code mode}, honouring the stored excess choice: a snapshot taken
     * here, on the calling thread, then {@link #solve(SolveInput)}. Client thread only (the
     * snapshot computes machine effects and the excess choice lives on the {@code Plan}).
     */
    @Nonnull
    public static BalanceResult balance(final Graph graph, final BalanceMode mode) {
        return solve(SolveInput.of(graph, mode, graph.getExcessChoice()));
    }

    /**
     * The read-out every board number comes from: per-node counts and rates, the solved view and
     * its alternatives, the drawer read-out and the notes. Every solving mode runs the SAME engine -
     * build a context, run the mode's own {@link Chain}, and derive the result from the committed
     * point's {@link SolutionView} - so a new balancer is a new {@link BalanceMode} constant and
     * nothing else. Only NONE is a separate branch, because it never solves: the configured counts
     * as-is. Touches nothing but the snapshot, so it is safe on any thread.
     */
    @Nonnull
    public static BalanceResult solve(final SolveInput input) {
        return solve(input, null, NEVER);
    }

    /**
     * {@link #solve(SolveInput)}, with the islands {@code cache} holds not solved again, stopped ({@link Stopped}) as
     * soon as {@code stop} says a newer edit made the answer worthless.
     */
    @Nonnull
    public static BalanceResult solve(final SolveInput input, @Nullable final Cache cache, final BooleanSupplier stop) {
        final BalanceMode mode = input.mode();
        if (mode == BalanceMode.NONE) {
            return buildResultFractional(input, configuredCounts(input), List.of(), null, null, DrawerReadout.NONE);
        }
        // One pass produces both the chart and the answers it could have had: the panel shows the
        // alternatives unconditionally now, and re-deriving them would mean solving twice per edit.
        final Answer answer = solveWithAlternatives(input, cache, stop);
        if (answer instanceof final Answer.Failed failed) {
            final Note reason = failed.failure();
            if (reason != null && reason.message() == SolverMessage.NO_PIN) {
                // Expected state, not an error: an unpinned chart is just wiring, so it gets no
                // quantities at all rather than numbers derived from an anchor nobody set.
                GtnhPlanner.LOG.debug("{} balance idle: {}", mode, reason.describe());
            } else {
                GtnhPlanner.LOG.warn(
                    "{} balance failed ({}); showing the chart without solve-derived quantities",
                    mode,
                    reason == null ? "no point" : reason.describe());
            }
            // A stalled AUTO shows the chart without quantities and the reason; the simple modes
            // keep the configured counts - their solve is a refinement that may be refused.
            final List<Note> failureNotes = new ArrayList<>();
            if (reason != null) failureNotes.add(reason);
            failureNotes.addAll(failed.notes());
            return mode == BalanceMode.AUTO
                ? buildResultFractional(input, Map.of(), failureNotes, null, null, failed.drawers())
                : buildResultFractional(input, configuredCounts(input), failureNotes, null, null, failed.drawers());
        }
        final Answer.Solved solved = (Answer.Solved) answer;
        final SolutionView view = solved.solution();
        GtnhPlanner.LOG.debug(
            "{} balance: {} machines, {} open gates, {}ms, notes {}",
            mode,
            view.machineCounts.size(),
            view.openGates,
            view.wallMillis,
            view.notes);
        return buildResultFractional(input, view.machineCounts, view.notes, view, solved.alternatives(), view.drawers);
    }

    /**
     * The configured machine counts: NONE's answer, and the simple modes' answer when their solve
     * is refused. A chart with no solve-derived quantities reads its own configuration.
     */
    @Nonnull
    private static Map<UUID, Double> configuredCounts(final SolveInput input) {
        final Map<UUID, Double> counts = new HashMap<>();
        for (final SolveInput.Machine machine : input.machines()) {
            counts.put(machine.id(), (double) machine.machineCount());
        }
        return counts;
    }

    /**
     * Builds the balance result from (possibly fractional) machine counts. Every displayed
     * number derives from the exact fractional count; rounding up for placement is left to the
     * reader. A machine missing from {@code machineCounts} reads zero - the way a chart with no
     * solve-derived quantities is presented.
     */
    @Nonnull
    private static BalanceResult buildResultFractional(final SolveInput input, final Map<UUID, Double> machineCounts,
        final List<Note> notes, @Nullable final SolutionView auto, @Nullable final Alternatives alternatives,
        final DrawerReadout drawers) {
        final Map<UUID, NodeBalance> nodeBalances = new HashMap<>();
        final Map<RecipeProperty<?>, Long> propertyTotals = new HashMap<>();
        double totalOps = 0;
        double totalDuration = 0;

        for (final SolveInput.Machine machine : input.machines()) {
            final double count = machineCounts.getOrDefault(machine.id(), 0.0);
            totalOps += count;

            final double eutPerOp = machine.energyPerTick();
            final double durPerOp = machine.durationTicks();
            final int throughputFactor = machine.throughputFactor();

            final long totalEnergy = Math.round(eutPerOp * durPerOp * count);
            if (durPerOp > totalDuration) totalDuration = durPerOp;

            final Map<Integer, Float> effOuts = new HashMap<>(
                machine.outputs()
                    .size());
            for (int i = 0; i < machine.outputs()
                .size(); i++) {
                final SolveInput.PortIn out = machine.outputs()
                    .get(i);
                final double stackSize = out.amount();
                if (stackSize <= 0) continue;
                final float total = (float) (count * stackSize * out.chance() * out.multiplier() * throughputFactor);
                if (total <= 0) continue;
                effOuts.put(i, total);
            }

            final Map<Integer, Float> effIns = new HashMap<>(
                machine.inputs()
                    .size());
            for (int i = 0; i < machine.inputs()
                .size(); i++) {
                final SolveInput.PortIn in = machine.inputs()
                    .get(i);
                final double stackSize = in.amount();
                if (stackSize <= 0) continue;
                final float total = (float) (count * stackSize * in.chance() * in.multiplier() * throughputFactor);
                if (total <= 0) continue;
                effIns.put(i, total);
            }

            nodeBalances.put(machine.id(), new NodeBalance(count, durPerOp, totalEnergy, durPerOp, effOuts, effIns));

            for (final Map.Entry<RecipeProperty<?>, Number> entry : machine.properties()
                .entrySet()) {
                propertyTotals.merge(
                    entry.getKey(),
                    Math.round(
                        entry.getValue()
                            .longValue() * count),
                    Long::sum);
            }
        }

        if (auto == null) {
            return new BalanceResult.Fallback(
                nodeBalances,
                propertyTotals,
                totalOps,
                (int) Math.ceil(totalDuration),
                notes,
                drawers);
        }
        return new BalanceResult.Solved(
            nodeBalances,
            propertyTotals,
            totalOps,
            (int) Math.ceil(totalDuration),
            notes,
            auto,
            alternatives,
            drawers);
    }

    /**
     * One machine's share of a solved balance, for the node widget and the machine-count panel.
     *
     * @param operations       the machine count (fractional in AUTO).
     * @param effectiveOutputs per output index, units per cycle ({@code durationPerOp} ticks) across
     *                         all {@code operations} machines; see {@link #outputPerSecond}.
     * @param effectiveInputs  the same for inputs.
     */
    public record NodeBalance(double operations, double totalDurationTicks, long totalEnergy, double durationPerOp,
        Map<Integer, Float> effectiveOutputs, Map<Integer, Float> effectiveInputs) {

        /** Units per second through output {@code index} (0 when it carries nothing). */
        public double outputPerSecond(final int index) {
            return perSecond(effectiveOutputs.get(index));
        }

        /** Units per second through input {@code index} (0 when it carries nothing). */
        public double inputPerSecond(final int index) {
            return perSecond(effectiveInputs.get(index));
        }

        private double perSecond(@Nullable final Float perCycle) {
            if (perCycle == null) return 0;
            return perCycle * (double) Numerics.TICKS_PER_SECOND / (durationPerOp > 0 ? durationPerOp : 1);
        }
    }

}
