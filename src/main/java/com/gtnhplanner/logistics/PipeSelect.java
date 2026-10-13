package com.gtnhplanner.logistics;

import javax.annotation.Nullable;

import com.gtnhplanner.logistics.PipeCatalog.Kind;
import com.gtnhplanner.logistics.PipeCatalog.Spec;
import com.gtnhplanner.ui.theme.Fmt;

/**
 * The pipe for a solved flow. The smallest tooltip rate that covers it, restrictive pipes left out, and covers,
 * pumps and robot arms ignored. Several of the largest pipe when one is not enough, up to {@link #MAX_PARALLELS}.
 * A longer run is not a slower one here: item pipes are rated as their tooltip rates them, which does not name a
 * length.
 */
public final class PipeSelect {

    /** Past this many side by side, the label says the run still comes up short. */
    public static final int MAX_PARALLELS = 64;

    /**
     * @param spec      the pipe, null when nothing in the catalog can carry this flow
     * @param count     how many of them, side by side
     * @param shortfall true when {@link #MAX_PARALLELS} of the largest pipe still cannot
     */
    public record Choice(@Nullable Spec spec, int count, boolean shortfall) {

        /** The tag's line, in the board's rate unit: "Small Copper pipe · 120 L/s". */
        public String line(final Fmt.RateUnit unit, final int stackSize) {
            if (spec == null) return "No pipe fits";
            final String name = (count > 1 ? count + "× " : "") + spec.name();
            final String rated = Fmt.rate(spec.perSecond(stackSize) * count, unit, spec.kind() == Kind.FLUID);
            return name + " · " + rated + (shortfall ? ", short" : "");
        }
    }

    private PipeSelect() {}

    /**
     * @param perSecond         items a second, or litres a second
     * @param stackSize         how many of the item make a stack; ignored for a fluid
     * @param temperatureKelvin the fluid's temperature, or 0 when it is not known (no heat check)
     * @param gas               a gas skips pipes that are not gas proof (wooden pipes)
     */
    @Nullable
    public static Choice choose(final Kind kind, final double perSecond, final int stackSize,
        final int temperatureKelvin, final boolean gas) {
        if (!(perSecond > 0) || Double.isNaN(perSecond)) return null;
        Spec best = null;
        Spec largest = null;
        for (final Spec spec : PipeCatalog.all()) {
            if (spec.kind() != kind || spec.restrictive()) continue;
            if (kind == Kind.FLUID) {
                if (temperatureKelvin > 0 && temperatureKelvin > spec.heatKelvin()) continue;
                if (gas && !spec.gasProof()) continue;
            }
            if (largest == null || spec.perSecond(stackSize) > largest.perSecond(stackSize)) largest = spec;
            if (spec.perSecond(stackSize) + 1e-9 < perSecond) continue;
            if (best == null || smaller(spec, best, stackSize)) best = spec;
        }
        if (best != null) return new Choice(best, 1, false);
        if (largest == null || largest.perSecond(stackSize) <= 0) return new Choice(null, 0, true);
        final int count = (int) Math.ceil(perSecond / largest.perSecond(stackSize) - 1e-12);
        if (count > MAX_PARALLELS) return new Choice(largest, MAX_PARALLELS, true);
        return new Choice(largest, count, false);
    }

    /** A smaller rate, then one channel rather than several, then the earlier material, then the smaller size. */
    private static boolean smaller(final Spec candidate, final Spec current, final int stackSize) {
        final int byRate = Double.compare(candidate.perSecond(stackSize), current.perSecond(stackSize));
        if (byRate != 0) return byRate < 0;
        if (candidate.channels() != current.channels()) return candidate.channels() < current.channels();
        if (candidate.tier() != current.tier()) return candidate.tier() < current.tier();
        return candidate.size()
            .ordinal()
            < current.size()
                .ordinal();
    }
}
