package com.gtnhplanner.data.flowchart;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Fractional order keys, exactly the website's {@code src/lib/tasks/order-key.ts} (the two share
 * {@code order-key-vectors.json}): a key is a base-62 fraction (digits 0-9, A-Z, a-z, which sort in that order as
 * plain strings), so a thing moved between two others takes one new key between theirs and nothing else changes. Any
 * string is accepted as a neighbour, another client's keys included (a character that is not a digit is placed by its
 * character code), and nothing here throws. Sorting is by key, then id.
 */
public final class OrderKeys {

    private static final String DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int BASE = DIGITS.length();

    private OrderKeys() {}

    /** The first key of an empty list. */
    public static final String FIRST = between(null, null);

    /** Whether a key is usable as one: any non-empty text sorts, and a key can be put next to it. */
    public static boolean valid(@Nullable final String key) {
        return key != null && !key.isEmpty();
    }

    private static int digit(final String s, final int at) {
        if (at >= s.length()) return 0;
        final char c = s.charAt(at);
        final int found = DIGITS.indexOf(c);
        if (found >= 0) return found;
        return c < '0' ? 0 : BASE - 1;
    }

    /** A fraction strictly between {@code low} and {@code high} (null: the top end). */
    private static String midpoint(final String low, @Nullable final String high) {
        final StringBuilder prefix = new StringBuilder();
        for (int at = 0;; at++) {
            final int lowDigit = digit(low, at);
            final int highDigit = high == null || at >= high.length() ? BASE : digit(high, at);
            if (lowDigit == highDigit) {
                prefix.append(DIGITS.charAt(lowDigit));
                continue;
            }
            final int middle = (lowDigit + highDigit) / 2;
            if (middle > lowDigit) return prefix.append(DIGITS.charAt(middle))
                .toString();
            // Adjacent digits: keep the low one and go above the rest of `low`.
            return prefix.append(DIGITS.charAt(lowDigit))
                .append(midpoint(at + 1 <= low.length() ? low.substring(at + 1) : "", null))
                .toString();
        }
    }

    /**
     * A key after {@code a} and before {@code b}; either may be null (the start or the end of the list). Keys that are
     * out of order or equal give a key after {@code a}.
     */
    public static String between(@Nullable final String a, @Nullable final String b) {
        final String low = a == null ? "" : a;
        if (b != null && b.compareTo(low) > 0) return midpoint(low, b);
        return midpoint(low, null);
    }

    /** {@code n} keys in order, for a list whose keys are lost or clash. */
    public static List<String> sequence(final int n) {
        final List<String> out = new ArrayList<>(n);
        String last = null;
        for (int i = 0; i < n; i++) {
            last = between(last, null);
            out.add(last);
        }
        return out;
    }

    /** Sorted by order key, then id, so every client shows the same order. */
    public static int compare(final String orderA, final String idA, final String orderB, final String idB) {
        final int c = orderA.compareTo(orderB);
        return c != 0 ? c : idA.compareTo(idB);
    }

    /** The same ordering for a list of things. */
    public static <T> Comparator<T> byOrder(final java.util.function.Function<T, String> order,
        final java.util.function.Function<T, String> id) {
        return (x, y) -> compare(order.apply(x), id.apply(x), order.apply(y), id.apply(y));
    }
}
