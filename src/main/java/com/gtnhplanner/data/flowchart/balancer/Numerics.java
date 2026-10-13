package com.gtnhplanner.data.flowchart.balancer;

import com.gtnhplanner.Config;

/**
 * The numeric knobs of a solve: feast/sensitivity tolerances, model time limits and node budgets,
 * the big-M growth policy and the effort scaler. These are carried inside {@link Heuristics} so a
 * new balancer type can trade fidelity against time differently without touching any stage code.
 */
public final class Numerics {

    /** Duration ticks per second, used for every extent-to-count conversion. */
    public static final int TICKS_PER_SECOND = 20;

    /**
     * How long one model may run before it is stopped as broken: a safety net only, far past what any working model
     * takes. Nothing else in a solve looks at the clock; its work is counted.
     */
    public final long modelSafetyMillis = 30_000;
    /**
     * How many questions the gate search ({@link GateSearch}) may ask proving its minimal answer least, scaled by
     * effort. Counted, so where it stops is the same on every computer; past it, the least answers found stand, or the
     * minimal one does, not proven least.
     */
    public final int maxGateProofQuestions = 1024;
    /** How many steps the gate search's hitting-set search may take over its cores before it gives up. */
    public final int maxHittingSetNodes = 200_000;
    /** Relative tolerance for "two solves found the SAME optimum". */
    public final double tieRel = 1e-6;
    /** Relative slack on the stage-2 quantity cap in stage 3. */
    public final double qtyEps = 1e-7;
    /**
     * Ceiling on how many least gate sets ({@link GateSearch}) are compared on quantity and flow, NOT scaled by effort:
     * cutting the ties short lets the same chart answer differently.
     */
    public final int maxTiedSupports = 64;

    /** Cap on gate swaps tried in the alternatives search, scaled by effort. */
    public final int maxAltSwaps = 1024;
    /** How many other places the alternatives list offers for one leftover. */
    public final int maxAltOptions = 8;

    /** Flows below this count as zero when deriving gate support (relative). */
    public final double zero = 1e-6;
    /** Solver dust: what a variable holds when the solver meant zero (relative). */
    public final double dust = 1e-11;
    /** Residual tolerance for the independent conservation check, scaled per row. */
    public final double validateTol = 1e-6;
    /** Fallback pass-2 floor (crafts/s) when no machine ran at all. */
    public final double useEps = 1e-4;
    /** A machine "runs" if its crafts/s exceeds this. */
    public final double useEpsDetect = 1e-7;

    /**
     * Packing floor for the lexicographic gate weights; see {@link Heuristics#packedGateWeights}.
     * Static because the packing formula is scale-free by construction.
     */
    public static final double GATE_WEIGHT_FLOOR = 1024.0;

    public final double gateWeightFloor = GATE_WEIGHT_FLOOR;

    /**
     * How much of a tuned effort number {@link Config#solverEffortPercent} buys. Applied where each
     * number is used rather than folded into the constants, so nothing depends on whether this class
     * initialized before the config loaded. Only numbers that trade time for a better answer are
     * scaled.
     */
    public long effort(final long tuned) {
        return Math.max(1, tuned * Config.solverEffort() / 100);
    }
}
