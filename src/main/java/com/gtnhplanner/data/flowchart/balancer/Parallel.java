package com.gtnhplanner.data.flowchart.balancer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.function.Function;
import java.util.stream.IntStream;

/**
 * The solver's own worker threads, for models that do not depend on each other (the alternatives' swaps, the gate
 * filter's trials): solved side by side, and their answers always taken in the order the work was listed, so a solve
 * answers exactly as it would one model at a time. Every model is built and solved on its own (ojAlgo keeps nothing
 * between models but its presolver list, which nothing changes while solving), and the {@link SolveContext} is only
 * read while they run.
 */
public final class Parallel {

    private Parallel() {}

    /** All but a couple of the machine's threads: the game draws on one, and the solve service waits on another. */
    private static final int THREADS = Math.max(
        1,
        Math.min(
            16,
            Runtime.getRuntime()
                .availableProcessors() - 2));

    private static final ForkJoinPool POOL = new ForkJoinPool(THREADS, pool -> {
        final ForkJoinWorkerThread t = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
        t.setName("GTNH Planner solver worker " + t.getPoolIndex());
        t.setDaemon(true);
        return t;
    }, null, false);

    /** How many models may be solved at once: how big a batch of guesses is worth making. */
    public static int threads() {
        return THREADS;
    }

    /**
     * How many independent models a search hands over at once when it looks at its budget between batches: a fixed
     * number, so where a spent budget cuts the search never depends on how many threads this computer has.
     */
    public static int batch() {
        return 16;
    }

    /** {@code work} applied to every item, side by side when there are several; the answers in the items' order. */
    public static <T, R> List<R> map(final List<T> items, final Function<T, R> work) {
        return map(null, items, work);
    }

    /**
     * {@code work} applied to every item, side by side when there are several and nothing is listening (a profiler
     * hears every model, in order, so it gets them one at a time); the answers in the items' order.
     */
    public static <T, R> List<R> map(final SolveContext ctx, final List<T> items, final Function<T, R> work) {
        if (items.size() < 2 || THREADS < 2 || ctx != null && ctx.profiler.enabled()) {
            final List<R> out = new ArrayList<>(items.size());
            for (final T item : items) out.add(work.apply(item));
            return out;
        }
        final Object[] out = new Object[items.size()];
        POOL.submit(
            () -> IntStream.range(0, items.size())
                .parallel()
                .forEach(i -> out[i] = work.apply(items.get(i))))
            .join();
        @SuppressWarnings("unchecked")
        final List<R> results = (List<R>) Arrays.asList(out);
        return results;
    }
}
