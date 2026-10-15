package com.gtnhplanner.data.flowchart.balancer.alternatives;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.gtnhplanner.data.flowchart.balancer.SolveContext;
import com.gtnhplanner.data.flowchart.balancer.Solver;
import com.gtnhplanner.data.flowchart.balancer.SolverMessage;
import com.gtnhplanner.data.flowchart.balancer.StageOutcome;

/**
 * One rule for choosing between answers that every rule before it found equally good, in the order AUTO applies them:
 * buying as far upstream as it can, selling as far downstream as it can, then the least internal flow. The choices
 * panel
 * says why an option is not the default by the first of them that separates the two.
 *
 * <p>
 * {@link #ORDER} is the single authority for the sequence: the choices enumerator names the first entry that separates
 * a rejected answer, and the panel sorts by {@link #displayOrder()} which derives from the same sequence, so a
 * hand-kept
 * list cannot drift from it.
 *
 * @param measure    what the rule costs at a point, compared entry by entry, the first entry that differs deciding.
 * @param whenWorse  the reason given for an option that loses on this rule.
 * @param whenBetter the reason given for an option that wins on it (one a capped search could not rule out).
 */
public record Preference(SolverMessage name, Measure measure, @Nullable Rank whenWorse, @Nullable Rank whenBetter) {

    /** A rule's cost at a point. */
    @FunctionalInterface
    public interface Measure {

        double[] of(SolveContext ctx, double[] externals, double[] flows);
    }

    /** How the alternatives list says why an option is not the default, in this order. */
    public static final List<Preference> ORDER = List.of(
        new Preference(SolverMessage.PREF_LEAST_BUYING, levels(true), Rank.BUYS_MORE, null),
        new Preference(SolverMessage.PREF_LEAST_SELLING, levels(false), Rank.SELLS_MORE, null),
        new Preference(SolverMessage.PREF_LEAST_FLOW, (ctx, externals, flows) -> {
            double total = 0;
            for (final double f : flows) total += f;
            return new double[] { total };
        }, Rank.MOVES_MORE, Rank.MOVES_LESS));

    /**
     * What is bought (or sold) at each of AUTO's levels of it ({@link Solver#levels}), in the order it settles them.
     */
    private static Measure levels(final boolean buying) {
        return (ctx, externals, flows) -> {
            final List<Solver.Level> levels = Solver.levels(ctx);
            final List<Double> out = new ArrayList<>();
            for (final Solver.Level level : levels)
                if (level.buying() == buying) out.add(level.measure(ctx, externals));
            final double[] values = new double[out.size()];
            for (int i = 0; i < values.length; i++) values[i] = out.get(i);
            return values;
        };
    }

    /**
     * Display order, derived from {@link #ORDER} rather than written beside it: the default, then
     * what beats it, then what ties it, then what it gave up - mildest concession first, so the
     * LATEST preference given up sorts best. A hand-kept list drifts from the preference sequence
     * and silently sorts an unlisted rank to the front.
     */
    public static List<Rank> displayOrder() {
        final List<Rank> order = new ArrayList<>();
        order.add(Rank.DEFAULT);
        for (int i = ORDER.size() - 1; i >= 0; i--) {
            final Rank better = ORDER.get(i).whenBetter;
            if (better != null) order.add(better);
        }
        order.add(Rank.EQUALLY_VALID);
        for (int i = ORDER.size() - 1; i >= 0; i--) {
            final Rank worse = ORDER.get(i).whenWorse;
            if (worse != null) order.add(worse);
        }
        return List.copyOf(order);
    }

    /** What this rule costs at a solved point. */
    public double[] measure(final SolveContext ctx, final StageOutcome solve) {
        return measure.of(ctx, solve.externals, solve.flows);
    }

    /**
     * Why {@code alt} is not the default: the earliest rule that separates the two, which is the one that actually
     * decided it. mk1's alternative both imports and moves less material, and reporting the flow would hide the thing
     * that really chose - buying, which is settled before flow is ever looked at.
     *
     * @param incumbent each rule's cost at the default, in {@link #ORDER}.
     */
    public static Rank rankOf(final SolveContext ctx, final StageOutcome alt, final double[][] incumbent,
        final double tolerance) {
        final int n = Math.min(ORDER.size(), incumbent.length);
        for (int i = 0; i < n; i++) {
            final Preference preference = ORDER.get(i);
            final double[] mine = preference.measure(ctx, alt);
            for (int k = 0; k < Math.min(mine.length, incumbent[i].length); k++) {
                if (mine[k] > incumbent[i][k] + tolerance) {
                    if (preference.whenWorse != null) return preference.whenWorse;
                    break;
                }
                if (mine[k] < incumbent[i][k] - tolerance) {
                    if (preference.whenBetter != null) return preference.whenBetter;
                    break;
                }
            }
        }
        return Rank.EQUALLY_VALID;
    }
}
