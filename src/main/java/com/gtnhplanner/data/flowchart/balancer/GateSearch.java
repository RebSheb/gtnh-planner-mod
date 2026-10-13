package com.gtnhplanner.data.flowchart.balancer;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

/**
 * Stage 1 exactly: the least-weight sets of open gates (fewest gates, then fewest imports, by the packed
 * {@link SolveContext#gateWeight}) that let the plan balance, and every one of them that ties, found with LP
 * feasibility questions alone. No big-M and no branch-and-bound over flows, so nothing here depends on how a solver
 * found a point: every decision is a yes or no about a set of gates, the same however it is reached.
 *
 * <p>
 * "Balances with only the gates in S open" is monotone (opening more never hurts), which the search leans on:
 * <ol>
 * <li>a gate is <em>needed</em> when the plan cannot balance with every other gate open; every answer opens it, so
 * when the needed gates alone balance the plan they are the one least answer (most plans end here);</li>
 * <li>otherwise a few rounds of reweighted L1 gather the flow onto few gates, and a deletion filter closes more of
 * them one at a time, thinnest first in a canonical point, while the plan still balances: a minimal answer, and a
 * ceiling on the least weight;</li>
 * <li>a set that does not balance, grown by every gate (in index order) that keeps it from balancing, leaves a
 * <em>core</em>: the gates outside it, at least one of which every answer opens. The minimal answer less any one of
 * its gates seeds one core per gate;</li>
 * <li>the least-weight sets that open a gate of every core known (a small hitting-set search) are the only
 * candidates: when they all balance they are every least answer, and one that does not yields the next core.</li>
 * </ol>
 * Growing a core decides gate by gate in a fixed order but asks about whole runs of gates at once, halving a run only
 * when the answer is not the same for all of it (as QuickXplain does), which gives the one-at-a-time answer in far
 * fewer questions. Every point an LP returns proves every superset of the gates it carries, and every refusal
 * proves every subset of what it tried, so many questions are answered from earlier ones. Independent questions and
 * independent cores go to the solver's threads ({@link Parallel}); answers are facts, so the order they arrive in
 * changes nothing.
 *
 * <p>
 * The work is counted in questions ({@link Numerics#maxGateProofQuestions}) and hitting-set steps
 * ({@link Numerics#maxHittingSetNodes}), the same on every computer. Past either, least answers already found stand
 * (perhaps not all of the ties); without one, the deletion filter's minimal answer stands, not proven least.
 */
public final class GateSearch {

    /** A set of open gates and the least-quantity point over it (stage 2's LP), which proves it balances. */
    public record Support(Set<Integer> open, StageOutcome point) {}

    /**
     * What the search found.
     *
     * @param ties      every least-weight answer found, in index order of their gates.
     * @param needed    the gates every answer opens.
     * @param certified whether the answers are proven least (false only for the deletion filter's answer).
     */
    public record Result(List<Support> ties, Set<Integer> needed, boolean certified) {}

    private final SolveContext ctx;
    private final int gates;
    private final long[] weight;
    private final int maxQuestions;
    private final int maxTies;
    private final int maxNodes;
    /** Questions asked so far, answered from memory or not: the same count however the threads interleave. */
    private final AtomicInteger questions = new AtomicInteger();
    /** What a balancing point carries: every superset of one balances. Guarded by itself. */
    private final List<BitSet> balancing = new ArrayList<>();
    /** The point behind each of {@link #balancing}, in the same order. Guarded by {@link #balancing}. */
    private final List<StageOutcome> witnesses = new ArrayList<>();
    /** What was tried and does not balance: no subset of one does. Guarded by {@link #balancing}. */
    private final List<BitSet> stuck = new ArrayList<>();
    /** The least-quantity point of each set an LP ran on. Guarded by {@link #balancing}. */
    private final Map<BitSet, StageOutcome> points = new HashMap<>();
    private volatile Note rejection;

    private GateSearch(final SolveContext ctx) {
        this.ctx = ctx;
        this.gates = ctx.model.gates.size();
        this.weight = new long[gates];
        for (int g = 0; g < gates; g++) weight[g] = Math.round(ctx.gateWeight(g));
        final Numerics n = ctx.heuristics.numerics();
        this.maxQuestions = (int) n.effort(n.maxGateProofQuestions);
        this.maxTies = n.maxTiedSupports;
        this.maxNodes = n.maxHittingSetNodes;
        this.rejection = ctx.rejection;
    }

    /** The least answers, or why the plan does not balance even with every gate open. */
    public static Outcome<Result> run(final SolveContext ctx) {
        final GateSearch search = new GateSearch(ctx);
        final Result result = search.search();
        return result == null ? new Outcome.Fail<>(search.rejection) : new Outcome.Continue<>(result);
    }

    @Nullable
    private Result search() {
        final BitSet all = new BitSet();
        all.set(0, gates);
        if (!balances(all)) return null;
        final BitSet needed = needed(all);
        if (balances(needed)) return result(List.of(needed), needed, true);

        final BitSet upper = minimal(all, needed);
        final List<Integer> chosen = new ArrayList<>();
        for (int g = upper.nextSetBit(0); g >= 0; g = upper.nextSetBit(g + 1)) if (!needed.get(g)) chosen.add(g);
        // The proof's questions are counted from here: the needed gates and the minimal answer are always found.
        final int proofStart = questions.get();
        final List<BitSet> cores = new ArrayList<>();
        for (int at = 0; at < chosen.size(); at += Parallel.batch()) {
            if (questions.get() - proofStart >= maxQuestions) return result(List.of(upper), needed, false);
            cores.addAll(Parallel.map(ctx, chosen.subList(at, Math.min(chosen.size(), at + Parallel.batch())), g -> {
                final BitSet without = (BitSet) upper.clone();
                without.clear(g);
                return core(without);
            }));
        }

        List<BitSet> least = List.of();
        final Set<BitSet> found = new HashSet<>();
        while (questions.get() - proofStart < maxQuestions) {
            final HittingSets search = new HittingSets(cores, needed, weightOf(upper));
            if (search.exhausted) break;
            least = search.sets;
            final List<BitSet> fresh = new ArrayList<>();
            for (final BitSet h : least) if (!found.contains(h)) fresh.add(h);
            final List<Boolean> answers = balances(fresh);
            BitSet refused = null;
            for (int i = 0; i < fresh.size(); i++) {
                if (answers.get(i)) found.add(fresh.get(i));
                else if (refused == null) refused = fresh.get(i);
            }
            if (refused == null) return result(least, needed, true);
            cores.add(core(refused));
        }
        // Out of budget. A least hitting set that balances is still a least answer (every answer opens a gate of every
        // core, so their weight is a floor): those stand, perhaps not all of the ties. Else the minimal answer does.
        final List<BitSet> proven = new ArrayList<>();
        for (final BitSet h : least) if (found.contains(h)) proven.add(h);
        return proven.isEmpty() ? result(List.of(upper), needed, false) : result(proven, needed, true);
    }

    @Nullable
    private Result result(final List<BitSet> ties, final BitSet needed, final boolean certified) {
        final List<BitSet> missing = new ArrayList<>();
        synchronized (balancing) {
            for (final BitSet s : ties) if (!points.containsKey(s)) missing.add(s);
        }
        final List<SolveResult> solved = Parallel.map(ctx, missing, s -> Solver.fixedQuantity(ctx, setOf(s)));
        final List<Support> supports = new ArrayList<>(ties.size());
        synchronized (balancing) {
            for (int i = 0; i < missing.size(); i++) {
                if (!solved.get(i)
                    .isRejected())
                    points.put(
                        missing.get(i),
                        solved.get(i)
                            .point());
            }
            // A set proven to balance by a point over fewer gates whose own LP then fails on numerics keeps that point.
            for (final BitSet s : ties) {
                final StageOutcome point = points.containsKey(s) ? points.get(s) : witnessFor(s);
                if (point != null) supports.add(new Support(setOf(s), point));
            }
        }
        return supports.isEmpty() ? null : new Result(supports, setOf(needed), certified);
    }

    /** Every gate the plan cannot balance without, every other gate open: one question each, side by side. */
    private BitSet needed(final BitSet all) {
        final List<Integer> asked = new ArrayList<>(gates);
        final List<BitSet> sets = new ArrayList<>(gates);
        for (int g = 0; g < gates; g++) {
            final BitSet without = (BitSet) all.clone();
            without.clear(g);
            asked.add(g);
            sets.add(without);
        }
        final BitSet needed = new BitSet();
        for (int at = 0; at < sets.size(); at += Parallel.batch()) {
            final int end = Math.min(sets.size(), at + Parallel.batch());
            final List<Boolean> answers = balances(sets.subList(at, end));
            for (int i = at; i < end; i++) if (!answers.get(i - at)) needed.set(asked.get(i));
        }
        return needed;
    }

    /**
     * A minimal answer, and the ceiling on the least weight: the deletion filter the solver always used, made
     * canonical.
     * Over the plan's canonical point for the gates still open, every gate it carries nothing through closes at once
     * (the point proves it), then the first gate that can close in the order imports first, thinnest flow, index; then
     * again from the new canonical point, until none can. A gate that cannot close never will once fewer are open.
     */
    private BitSet minimal(final BitSet all, final BitSet needed) {
        final BitSet open = sparse(all);
        final BitSet kept = (BitSet) needed.clone();
        while (true) {
            final StageOutcome point = canonicalPoint(open);
            final double[] gateFlow = new double[gates];
            for (int p = 0; p < point.externals.length; p++) gateFlow[ctx.model.portGate[p]] += point.externals[p];
            final BitSet carried = bitsOf(ctx.carryingGates(point.externals, Set.of()));
            for (int g = open.nextSetBit(0); g >= 0; g = open.nextSetBit(g + 1)) if (!carried.get(g)) open.clear(g);
            // Flows compared at a fixed relative precision, so solver noise never reorders two equal ones.
            final double grain = 1e-9 * SolveContext.scaleOf(gateFlow);
            final List<Integer> order = new ArrayList<>();
            for (int g = open.nextSetBit(0); g >= 0; g = open.nextSetBit(g + 1)) if (!kept.get(g)) order.add(g);
            order.sort(
                Comparator.<Integer>comparingDouble(g -> -ctx.importTilt(g))
                    .thenComparingLong(g -> Math.round(gateFlow[g] / grain))
                    .thenComparingInt(g -> g));
            int closing = -1;
            for (int at = 0; at < order.size() && closing < 0; at += Parallel.batch()) {
                final List<Integer> batch = order.subList(at, Math.min(order.size(), at + Parallel.batch()));
                final List<BitSet> sets = new ArrayList<>(batch.size());
                for (final int g : batch) {
                    final BitSet without = (BitSet) open.clone();
                    without.clear(g);
                    sets.add(without);
                }
                final List<Boolean> answers = balances(sets);
                for (int i = 0; i < batch.size(); i++) {
                    if (answers.get(i)) {
                        closing = batch.get(i);
                        break;
                    }
                    kept.set(batch.get(i));
                }
            }
            if (closing < 0) return open;
            open.clear(closing);
        }
    }

    /**
     * Where the deletion filter starts: the gates a few rounds of reweighted L1 leave carrying flow (Candès, Wakin and
     * Boyd 2008). Every gate open, each round weighs a gate's externals by one over what it carried in the last (plus
     * a thousandth of the most any gate carried), so gates carrying little grow dear and the flow gathers onto few.
     * Its last point proves the set balances.
     */
    private BitSet sparse(final BitSet all) {
        final double[] weightOf = new double[gates];
        java.util.Arrays.fill(weightOf, 1.0);
        BitSet carried = all;
        for (int round = 0; round < 4; round++) {
            final SolveResult r = Solver.gateWeighted(ctx, weightOf);
            if (r.isRejected()) break;
            remember(r.point());
            carried = bitsOf(ctx.carryingGates(r.point().externals, Set.of()));
            final double[] gateFlow = new double[gates];
            for (int p = 0; p < r.point().externals.length; p++) {
                gateFlow[ctx.model.portGate[p]] += r.point().externals[p] * ctx.externalWeight(p);
            }
            final double eps = 1e-3 * SolveContext.scaleOf(gateFlow);
            for (int g = 0; g < gates; g++) weightOf[g] = 1 / (gateFlow[g] + eps);
        }
        return (BitSet) carried.clone();
    }

    /** Keeps a point that balances, as proof for every set of gates holding the ones it carries. */
    private void remember(final StageOutcome point) {
        synchronized (balancing) {
            balancing.add(bitsOf(ctx.carryingGates(point.externals, Set.of())));
            witnesses.add(point);
        }
    }

    /** The one canonical point with only {@code open}'s gates open: least quantity, then least flow, then canonical. */
    private StageOutcome canonicalPoint(final BitSet open) {
        final Set<Integer> gatesOpen = setOf(open);
        StageOutcome least;
        synchronized (balancing) {
            least = points.get(open);
        }
        if (least == null) {
            final SolveResult r = Solver.fixedQuantity(ctx, gatesOpen);
            if (!r.isRejected()) least = r.point();
            else synchronized (balancing) {
                least = witnessFor(open);
            }
        }
        final SolveResult s3 = Solver.flowMinimal(ctx, gatesOpen, least.externalQuantity);
        return s3.isRejected() ? least : Solver.canonicalize(ctx, gatesOpen, s3.point());
    }

    /**
     * The core a set that does not balance leaves: grown by every gate, in index order, that keeps it from balancing,
     * the gates still outside it are a set every answer opens one of.
     */
    private BitSet core(final BitSet stuckSet) {
        final BitSet grown = (BitSet) stuckSet.clone();
        final List<Integer> rest = new ArrayList<>();
        for (int g = grown.nextClearBit(0); g < gates; g = grown.nextClearBit(g + 1)) rest.add(g);
        decide(rest, 0, rest.size(), run -> {
            final BitSet with = (BitSet) grown.clone();
            for (final int g : run) with.set(g);
            if (balances(with)) return false;
            grown.or(with);
            return true;
        });
        final BitSet core = new BitSet();
        core.set(0, gates);
        core.andNot(grown);
        return core;
    }

    /** What a run of items asks: whether it holds for all of them together, applying it when it does. */
    private interface RunTest {

        boolean applyAll(List<Integer> run);
    }

    /**
     * Decides {@code items[from, to)} one at a time in order, as if each were asked alone against the state its
     * predecessors left: a run that holds for all its items together holds for each in turn (monotone), so it is
     * applied whole; one that does not is halved, down to single items.
     */
    private static void decide(final List<Integer> items, final int from, final int to, final RunTest test) {
        if (from >= to) return;
        if (test.applyAll(items.subList(from, to)) || to - from == 1) return;
        final int mid = (from + to) >>> 1;
        decide(items, from, mid, test);
        decide(items, mid, to, test);
    }

    // ── The oracle ──

    private boolean balances(final BitSet open) {
        return balances(List.of(open)).get(0);
    }

    /** Whether each set balances: from what earlier answers prove, else one LP each, side by side. */
    private List<Boolean> balances(final List<BitSet> sets) {
        questions.addAndGet(sets.size());
        final Boolean[] out = new Boolean[sets.size()];
        final List<BitSet> ask = new ArrayList<>();
        final List<Integer> at = new ArrayList<>();
        for (int i = 0; i < sets.size(); i++) {
            out[i] = known(sets.get(i));
            if (out[i] == null) {
                ask.add(sets.get(i));
                at.add(i);
            }
        }
        final List<SolveResult> results = Parallel.map(ctx, ask, s -> Solver.fixedQuantity(ctx, setOf(s)));
        synchronized (balancing) {
            for (int i = 0; i < ask.size(); i++) {
                final SolveResult r = results.get(i);
                final BitSet s = (BitSet) ask.get(i)
                    .clone();
                if (r.isRejected()) {
                    stuck.add(s);
                    rejection = r.rejection();
                    out[at.get(i)] = false;
                } else {
                    balancing.add(bitsOf(ctx.carryingGates(r.point().externals, Set.of())));
                    witnesses.add(r.point());
                    points.put(s, r.point());
                    out[at.get(i)] = true;
                }
            }
        }
        return List.of(out);
    }

    /** What earlier answers already prove about a set, or null. */
    @Nullable
    private Boolean known(final BitSet open) {
        synchronized (balancing) {
            for (final BitSet carried : balancing) if (subset(carried, open)) return true;
            for (final BitSet refused : stuck) if (subset(open, refused)) return false;
        }
        return null;
    }

    /** A point that proves {@code open} balances, or null; the caller holds {@link #balancing}. */
    @Nullable
    private StageOutcome witnessFor(final BitSet open) {
        for (int i = 0; i < balancing.size(); i++) if (subset(balancing.get(i), open)) return witnesses.get(i);
        return null;
    }

    private long weightOf(final BitSet set) {
        long w = 0;
        for (int g = set.nextSetBit(0); g >= 0; g = set.nextSetBit(g + 1)) w += weight[g];
        return w;
    }

    private static boolean subset(final BitSet a, final BitSet b) {
        final BitSet extra = (BitSet) a.clone();
        extra.andNot(b);
        return extra.isEmpty();
    }

    private static Set<Integer> setOf(final BitSet bits) {
        final Set<Integer> out = new LinkedHashSet<>();
        for (int g = bits.nextSetBit(0); g >= 0; g = bits.nextSetBit(g + 1)) out.add(g);
        return out;
    }

    private static BitSet bitsOf(final Set<Integer> set) {
        final BitSet out = new BitSet();
        for (final int g : set) out.set(g);
        return out;
    }

    /**
     * The least-weight sets, no heavier than {@code ceiling}, that hold the forced gates and a gate of every core: a
     * depth-first search that branches on the first core a set misses, each gate of it in index order with the gates
     * before it barred (so every set is reached once), pruned by weight. At most {@link Numerics#maxTiedSupports}
     * sets, in index order.
     */
    private final class HittingSets {

        final List<BitSet> sets = new ArrayList<>();
        boolean exhausted;
        private final List<BitSet> cores;
        private long best;
        private int nodes;

        HittingSets(final List<BitSet> cores, final BitSet forced, final long ceiling) {
            this.cores = cores;
            this.best = ceiling;
            search((BitSet) forced.clone(), new BitSet(), weightOf(forced));
            sets.sort(HittingSets::compare);
        }

        private void search(final BitSet chosen, final BitSet barred, final long w) {
            if (exhausted) return;
            if (++nodes > maxNodes) {
                exhausted = true;
                return;
            }
            BitSet missed = null;
            long floor = 0;
            for (final BitSet core : cores) {
                if (core.intersects(chosen)) continue;
                long cheapest = Long.MAX_VALUE;
                for (int g = core.nextSetBit(0); g >= 0; g = core.nextSetBit(g + 1)) {
                    if (!barred.get(g)) cheapest = Math.min(cheapest, weight[g]);
                }
                if (cheapest == Long.MAX_VALUE) return; // every gate of this core is barred here
                if (missed == null) missed = core;
                floor = Math.max(floor, cheapest);
            }
            if (missed == null) {
                if (w < best) {
                    best = w;
                    sets.clear();
                }
                if (w == best && sets.size() < maxTies) sets.add((BitSet) chosen.clone());
                return;
            }
            if (w + floor > best) return;
            final BitSet barredHere = (BitSet) barred.clone();
            for (int g = missed.nextSetBit(0); g >= 0; g = missed.nextSetBit(g + 1)) {
                if (barredHere.get(g)) continue;
                if (w + weight[g] <= best) {
                    chosen.set(g);
                    search(chosen, barredHere, w + weight[g]);
                    chosen.clear(g);
                }
                barredHere.set(g);
            }
        }

        private static int compare(final BitSet a, final BitSet b) {
            int x = a.nextSetBit(0), y = b.nextSetBit(0);
            while (x >= 0 && y >= 0) {
                if (x != y) return Integer.compare(x, y);
                x = a.nextSetBit(x + 1);
                y = b.nextSetBit(y + 1);
            }
            return Integer.compare(x, y);
        }
    }
}
