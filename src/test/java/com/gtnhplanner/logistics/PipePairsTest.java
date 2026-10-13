package com.gtnhplanner.logistics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.logistics.PipePairs.Pair;

class PipePairsTest {

    @Test
    void oneEachKeepsTheWholeRate() {
        assertEquals(List.of(new Pair(0, 0, 100)), PipePairs.pair(1, 1, 100));
    }

    @Test
    void twoToTwoGoIndividually() {
        final List<Pair> pairs = PipePairs.pair(2, 2, 200);
        assertEquals(List.of(new Pair(0, 0, 100), new Pair(1, 1, 100)), pairs);
    }

    @Test
    void extraSourcesShareTheLastTarget() {
        assertEquals(List.of(new Pair(0, 0, 50), new Pair(1, 0, 50)), PipePairs.pair(2, 1, 100));
    }

    @Test
    void extraTargetsEachGetARunFromTheLastSource() {
        assertEquals(List.of(new Pair(0, 0, 50), new Pair(0, 1, 50)), PipePairs.pair(1, 2, 100));
    }

    @Test
    void threeToTwoPutsTheLeftoverOnTheLastTarget() {
        assertEquals(List.of(new Pair(0, 0, 30), new Pair(1, 1, 30), new Pair(2, 1, 30)), PipePairs.pair(3, 2, 90));
    }

    @Test
    void missingASideIsNoPair() {
        assertEquals(List.of(), PipePairs.pair(0, 2, 10));
        assertEquals(List.of(), PipePairs.pair(2, 0, 10));
    }
}
