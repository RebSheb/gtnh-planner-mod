package com.gtnhplanner.logistics;

/**
 * Whether a GregTech tile's class is the hatch a pipe of this kind leaves or enters. Energy, muffler, maintenance and
 * battery hatches are left out.
 */
public final class HatchKind {

    private HatchKind() {}

    public static boolean matches(final String name, final boolean fluid, final boolean output) {
        if (name.contains("Energy") || name.contains("Dynamo")
            || name.contains("Muffler")
            || name.contains("Maintenance")
            || name.contains("Data")
            || name.contains("Battery")) return false;
        final boolean bus = name.contains("Bus");
        final boolean in = name.contains("Input");
        final boolean out = name.contains("Output");
        if (in == out) return false;
        if (output != out) return false;
        return fluid ? !bus && name.contains("Hatch") : bus;
    }

    /**
     * Hatch kinds named in a tooltip or lang key, as bits: fluid in, fluid out, item in, item out. Energy and the like
     * name neither input nor output, so they stay 0.
     */
    public static int kindsIn(final String text) {
        int bits = 0;
        for (final String part : text.toLowerCase()
            .split("\\bor\\b|,")) bits |= one(part);
        return bits;
    }

    private static int one(final String name) {
        final boolean hatch = name.contains("hatch");
        final boolean bus = name.contains("bus");
        final boolean in = name.contains("input");
        final boolean out = name.contains("output");
        int bits = 0;
        if (in && hatch && !bus) bits |= 1;
        if (out && hatch && !bus) bits |= 2;
        if (in && bus) bits |= 4;
        if (out && bus) bits |= 8;
        return bits;
    }
}
