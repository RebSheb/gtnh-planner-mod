package com.gtnhplanner.data.flowchart.balancer;

/**
 * The strategy bundle of a balancer type: how a measure is weighted (externals, source tilt), plus
 * the numeric knobs of the solve.
 * Swapping the object swaps the heuristic - a new balancer type is a new combination, not an edit
 * to a big shared class.
 */
public final class Heuristics {

    /** How much one port's external counts in the "least excess" objective. */
    @FunctionalInterface
    public interface ExternalWeight {

        /** Weight for a port whose per-craft quantity is {@code qtyPerCraft}. */
        double weight(double qtyPerCraft);
    }

    /** The added cost of a SOURCE gate over a SINK gate, as a multiplier on a quantity. */
    @FunctionalInterface
    public interface ImportTilt {

        /** 1.0 for a pure sink, &#62; 1.0 for a source. */
        double tilt(boolean input);
    }

    private final Numerics numerics = new Numerics();
    private final ExternalWeight externalWeight;
    private final ImportTilt tilt;

    private Heuristics(final ExternalWeight externalWeight, final ImportTilt tilt) {
        this.externalWeight = externalWeight;
        this.tilt = tilt;
    }

    /** AUTO's semantics: trades measured in crafts, sink over source. */
    public static Heuristics auto() {
        return new Heuristics(
            qty -> qty > 0 ? 1.0 / qty : 1.0,
            input -> input ? 1.0 + 1.0 / Numerics.GATE_WEIGHT_FLOOR : 1.0);
    }

    /** The non-gated modes: no excess weighting of note, no tilt. */
    public static Heuristics counts() {
        return new Heuristics(qty -> 1.0, input -> 1.0);
    }

    public Numerics numerics() {
        return numerics;
    }

    public double externalWeight(final double qtyPerCraft) {
        return externalWeight.weight(qtyPerCraft);
    }

    public double importTilt(final boolean input) {
        return tilt.tilt(input);
    }
}
