package com.gtnhplanner.data.flowchart.balancer.alternatives;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.annotation.Nullable;

import com.gtnhplanner.data.flowchart.balancer.ChoiceKey;
import com.gtnhplanner.data.flowchart.balancer.External;
import com.gtnhplanner.data.flowchart.balancer.ModelData;
import com.gtnhplanner.data.flowchart.balancer.Note;
import com.gtnhplanner.data.flowchart.balancer.Numerics;
import com.gtnhplanner.data.flowchart.balancer.Parallel;
import com.gtnhplanner.data.flowchart.balancer.PortRef;
import com.gtnhplanner.data.flowchart.balancer.SolveContext;
import com.gtnhplanner.data.flowchart.balancer.SolveResult;
import com.gtnhplanner.data.flowchart.balancer.Solver;
import com.gtnhplanner.data.flowchart.balancer.SolverMessage;
import com.gtnhplanner.data.flowchart.balancer.StageOutcome;

/**
 * The choices/alternatives search for AUTO: every answer worth showing for the chart the solve
 * just answered, the solver's own first. Separate from the stages because it costs an LP per
 * candidate swap, worth paying only when somebody is looking. It REUSES the stage machinery -
 * {@link Solver#fixedQuantity}, {@link Solver#flowMinimal} and {@link Solver#canonicalize} - it
 * does not run as a stage itself.
 *
 * <p>
 * The bar for being listed is NOT DOMINATED rather than TIED: past the gate count every rule that
 * narrows the field is a preference (the source tilt, "least material moved", and so on), so
 * anything with the same gate count and no worse excess is shown, carrying the reason it is not
 * the default ({@link Rank}). The swap neighbourhood, the shortlist policies, the option cap and
 * its candid "not tried" note, and the sort orders are the persisted-across-versions behaviour
 * the corpus locks.
 */
public final class Enumerator {

    private Enumerator() {}

    /**
     * The answers around the committed point on {@code ctx}, using the context that already solved
     * it. Pass-2 floors are read from {@code ctx.floors}, which the runner leaves in place when a
     * floored answer was committed.
     */
    public static Alternatives enumerate(final SolveContext ctx) {
        return enumerate(ctx, null);
    }

    /**
     * As {@link #enumerate(SolveContext)}, also keeping each answer's whole point in {@code points}, by its key (the
     * committed point under the default's key), so a split between answers can be made by mixing them
     * ({@link com.gtnhplanner.data.flowchart.balancer.Spares}).
     *
     * <p>
     * Every leftover the answer shows (an open gate) that not every answer has ({@link SolveContext#neededGates}) is a
     * decision: each closed gate is tried in its place, the canonical point {@link #evaluateSupport} finds, side by
     * side
     * ({@link Parallel}). A swap that balances with the same gate count is an option for that decision, ranked by why
     * it
     * is not the default; each decision lists its best {@link Numerics#maxAltOptions}. The swaps are counted
     * ({@link Numerics#maxAltSwaps}), never timed, so the list is the same on every computer.
     */
    public static Alternatives enumerate(final SolveContext ctx, @Nullable final Map<ChoiceKey, StageOutcome> points) {
        final Set<Integer> incumbent = ctx.support();
        final ChoiceKey chosen = ctx.keyOf(incumbent);
        if (points != null) points.put(chosen, ctx.point());
        final List<Alternative> options = new ArrayList<>();
        // One "this is what it does now" row per open gate, so every question in the list has its
        // current answer sitting at the top of its own group rather than one row standing for all
        // of them at once.
        for (final int gate : sorted(incumbent)) {
            options.add(asAlternative(ctx, ctx.externals(), incumbent, gate, gate, Rank.DEFAULT));
        }
        final List<Note> notes = new ArrayList<>();

        // Nothing else could open, so nothing else could differ.
        if (incumbent.isEmpty() || ctx.model.gates.size() == incumbent.size()) {
            return new Alternatives(chosen, List.copyOf(options), true, List.copyOf(notes));
        }

        final double flowStar = sum(ctx.flows());
        final double qtyStar = ctx.normalizedQuantity(ctx.externals());
        // The incumbent measured once, in the same order an alternative will be measured against it.
        final double[] incumbentCost = new double[Preference.ORDER.size()];
        for (int i = 0; i < incumbentCost.length; i++) {
            incumbentCost[i] = Preference.ORDER.get(i)
                .measure(ctx, incumbent, ctx.externals(), ctx.flows());
        }
        final double tol = ctx.heuristics.numerics().tieRel
            * Math.max(Math.max(flowStar, qtyStar), ctx.solutionScale(ctx.extents(), ctx.flows(), ctx.externals()));
        final Numerics n = ctx.heuristics.numerics();

        // What the answer opens, gates carrying too little to show included: a swap keeps them open.
        final Set<Integer> open = ctx.openGates.isEmpty() ? incumbent : ctx.openGates;
        final List<int[]> swaps = new ArrayList<>();
        for (final int out : sorted(incumbent)) {
            if (ctx.neededGates.contains(out)) continue;
            for (int in = 0; in < ctx.model.gates.size(); in++) {
                if (!open.contains(in)) swaps.add(new int[] { out, in });
            }
        }
        final int cap = (int) Math.min(swaps.size(), n.effort(n.maxAltSwaps));
        final List<int[]> tried = swaps.subList(0, cap);
        final List<StageOutcome> answers = Parallel.map(ctx, tried, swap -> {
            final Set<Integer> trial = new HashSet<>(open);
            trial.remove(swap[0]);
            trial.add(swap[1]);
            return evaluateSupport(ctx, trial);
        });

        final Map<Integer, List<Alternative>> byDecision = new TreeMap<>();
        final Set<ChoiceKey> seen = new HashSet<>();
        seen.add(chosen);
        for (int i = 0; i < tried.size(); i++) {
            final StageOutcome alt = answers.get(i);
            final int out = tried.get(i)[0], in = tried.get(i)[1];
            if (alt == null || alt.support.size() != incumbent.size()
                || !alt.support.contains(in)
                || alt.support.contains(out)) continue;
            final ChoiceKey key = ctx.keyOf(alt.support);
            if (!seen.add(key)) continue;
            if (points != null) points.put(key, alt);
            byDecision.computeIfAbsent(out, k -> new ArrayList<>())
                .add(
                    asAlternative(
                        ctx,
                        alt.externals,
                        alt.support,
                        out,
                        in,
                        Preference.rankOf(ctx, alt, incumbentCost, tol)));
        }
        // Default first, then the ones that cost nothing to prefer, then by how much they give up.
        // Ties inside a rank go to the lower key, so the same chart always lists in the same order.
        final List<Rank> displayOrder = Preference.displayOrder();
        final Comparator<Alternative> best = Comparator
            .comparingInt((final Alternative option) -> displayOrder.indexOf(option.rank()))
            .thenComparing(Alternative::key);
        boolean complete = cap == swaps.size();
        int shown = 0, found = 0;
        for (final List<Alternative> decision : byDecision.values()) {
            decision.sort(best);
            found += decision.size();
            if (decision.size() > n.maxAltOptions) {
                decision.subList(n.maxAltOptions, decision.size())
                    .clear();
                complete = false;
            }
            shown += decision.size();
            options.addAll(decision);
        }
        options.sort(
            Comparator.comparing((final Alternative option) -> option.replaces(), PortRef.ORDER)
                .thenComparing(best));
        if (shown < found) notes.add(new Note(SolverMessage.SHOWING_CLOSEST, shown, found));
        if (cap < swaps.size()) notes.add(new Note(SolverMessage.STOPPED_EARLY, cap, swaps.size() - cap));
        return new Alternatives(chosen, List.copyOf(options), complete, List.copyOf(notes));
    }

    /**
     * Applies a stored choice, or explains in a note why it could not be. Returns the alternative
     * to commit, or the incumbent point unchanged when the choice no longer fits or already IS the
     * answer (the notes say so) - never null.
     */
    public static StageOutcome applyChoice(final SolveContext ctx, final ChoiceKey choice) {
        final Set<Integer> target = resolve(ctx, choice);
        if (target == null) {
            ctx.notes.add(new Note(SolverMessage.CHOICE_NO_LONGER_FITS));
            return ctx.point();
        }
        if (target.equals(ctx.support())) return ctx.point();

        final StageOutcome alt = evaluateSupport(ctx, target);
        if (alt == null || alt.support.size() != ctx.support()
            .size()) {
            // Gate count is the one bar a choice may not fall below: it is a real optimum, where
            // everything after it is a preference the user is entitled to disagree with.
            ctx.notes.add(new Note(SolverMessage.CHOICE_NEEDS_MORE_GATES));
            return ctx.point();
        }
        // Deliberately no note when the pick leans on the outside more than the default would have:
        // that is what choosing a listed alternative MEANS, and the row offering it already says so.
        return alt;
    }

    /** Whether a stored {@link ChoiceKey} still names a support on this chart, or null when it does not. */
    public static @Nullable Set<Integer> resolve(final SolveContext ctx, final ChoiceKey choice) {
        final Set<Integer> support = new HashSet<>();
        for (final PortRef anchor : choice.gateAnchors()) {
            final Integer machine = ctx.model.machineIndex.get(anchor.nodeId());
            if (machine == null) return null;
            final long key = ((long) machine << 32) | ((long) anchor.portIndex() << 1) | (anchor.input() ? 1 : 0);
            final Integer port = ctx.model.portLookup.get(key);
            // Not a ternary: mixing int and Integer in one makes javac unbox both arms, so a
            // null from the ingredient fallback becomes an NPE instead of "no match".
            Integer gate = null;
            if (port != null) {
                gate = ctx.model.portGate[port];
            } else {
                gate = resolveByIngredient(ctx, anchor);
            }
            if (gate == null) return null;
            // Two anchors landing on one gate means the key no longer describes the support it was
            // written for: an edge has merged two ingredient components since it was stored.
            if (!support.add(gate)) return null;
        }
        return support;
    }

    /** The gate matching a stored anchor by ingredient, only when the match is unique. */
    private static Integer resolveByIngredient(final SolveContext ctx, final PortRef anchor) {
        final ModelData model = ctx.model;
        final ModelData.Machine machine = model.machines.get(model.machineIndex.get(anchor.nodeId()));
        final var ports = machine.spec()
            .ports(anchor.input());
        if (anchor.portIndex() < 0 || anchor.portIndex() >= ports.size()) return null;
        final int want = ports.get(anchor.portIndex())
            .resource();
        Integer found = null;
        for (int p = 0; p < model.connectedPorts.size(); p++) {
            final ModelData.ConnectedPort cp = model.connectedPorts.get(p);
            if (cp.input() != anchor.input()) continue;
            final int candidate = model.machines.get(cp.machine())
                .port(cp.portIndex(), cp.input())
                .resource();
            if (want != candidate) continue;
            if (found != null && found != model.portGate[p]) return null; // ambiguous, so no answer
            found = model.portGate[p];
        }
        return found;
    }

    /**
     * The canonical point with exactly {@code open}'s gates allowed: least quantity, then least flow, then the
     * canonical tie-break. Null when they do not balance. A property of the set, however the LPs reach it.
     */
    public static StageOutcome evaluateSupport(final SolveContext ctx, final Set<Integer> open) {
        final SolveResult s2Result = Solver.fixedQuantity(ctx, open);
        if (s2Result.isRejected()) return null;
        final SolveResult s3Result = Solver.flowMinimal(ctx, open, s2Result.point().externalQuantity);
        if (s3Result.isRejected()) return null;
        return Solver.canonicalize(ctx, open, s3Result.point());
    }

    /** One answer packaged for the UI, carrying only the flows at the gate it opens. */
    private static Alternative asAlternative(final SolveContext ctx, final double[] externals,
        final Set<Integer> support, final int replaced, final int opened, final Rank rank) {
        final double dust = ctx.heuristics.numerics().dust * SolveContext.scaleOf(externals);
        final List<External> flows = new ArrayList<>();
        for (final int p : ctx.model.gates.get(opened)
            .ports()) {
            if (externals[p] <= dust) continue;
            flows.add(new External(ctx.refOf(p), externals[p]));
        }
        return new Alternative(
            ctx.keyOf(support),
            ctx.anchorOf(replaced),
            ctx.anchorOf(opened),
            List.copyOf(flows),
            rank);
    }

    private static double sum(final double[] values) {
        double total = 0;
        for (final double v : values) {
            total += v;
        }
        return total;
    }

    /** Gate indices in a stable order, so the swap search is reproducible. */
    private static List<Integer> sorted(final Set<Integer> gates) {
        final List<Integer> out = new ArrayList<>(gates);
        out.sort(Comparator.naturalOrder());
        return out;
    }
}
