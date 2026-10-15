package com.gtnhplanner.logistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.logistics.BlockRoute.Cell;
import com.gtnhplanner.logistics.PipeBus.Tap;

/** A pipe run stays out of the machines, and a shared bus is fattest where the flows join. */
class BlockRouteTest {

    @Test
    void pathStepsAroundABlockedCell() {
        final Set<Cell> blocked = Set.of(new Cell(1, 0, 0));
        final List<Cell> path = BlockRoute.path(new Cell(0, 0, 0), new Cell(2, 0, 0), blocked);
        assertEquals(new Cell(0, 0, 0), path.get(0));
        assertEquals(new Cell(2, 0, 0), path.get(path.size() - 1));
        assertFalse(path.contains(new Cell(1, 0, 0)));
        for (int i = 1; i < path.size(); i++) {
            final Cell a = path.get(i - 1), b = path.get(i);
            assertEquals(1, Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()) + Math.abs(a.z() - b.z()));
        }
    }

    @Test
    void outsideWalksClearOfTheMachine() {
        final Set<Cell> self = new HashSet<>();
        self.add(new Cell(1, 0, 0));
        self.add(new Cell(2, 0, 0));
        assertEquals(new Cell(3, 0, 0), BlockRoute.outside(new Cell(0, 0, 0), 1, 0, 0, self));
    }

    @Test
    void outsideClearsAThickMachine() {
        final Set<Cell> self = new HashSet<>();
        for (int x = 1; x <= 12; x++) self.add(new Cell(x, 0, 0));
        assertEquals(new Cell(13, 0, 0), BlockRoute.outside(new Cell(0, 0, 0), 1, 0, 0, self));
    }

    @Test
    void outsideStopsAtANeighbour() {
        final Cell start = new Cell(0, 0, 0);
        assertEquals(new Cell(1, 0, 0), BlockRoute.outside(start, 1, 0, 0, Set.of(start)));
    }

    @Test
    void itemAndFluidTakeDifferentFaces() {
        final Cell machine = new Cell(0, 0, 0), toward = new Cell(10, 0, 0);
        final Set<Cell> self = Set.of(machine);
        final Set<Cell> blocked = new HashSet<>(self);
        final Cell fluid = BlockRoute.freeExit(machine, self, blocked, toward);
        assertEquals(new Cell(1, 0, 0), fluid);
        blocked.add(fluid);
        final Cell item = BlockRoute.freeExit(machine, self, blocked, toward);
        assertTrue(item != null && !item.equals(fluid));
        assertFalse(blocked.contains(item));
    }

    @Test
    void noRouteWhenAnEndIsInsideAMachine() {
        assertTrue(
            BlockRoute.path(new Cell(0, 0, 0), new Cell(2, 0, 0), Set.of(new Cell(0, 0, 0)))
                .isEmpty());
    }

    @Test
    void sharedBusCarriesTheSumOnTheTrunk() {
        final List<Tap> taps = List
            .of(new Tap(new Cell(0, 1, 0), 10), new Tap(new Cell(0, 1, 10), 15), new Tap(new Cell(10, 1, 5), -25));
        final Map<Cell, Double> flow = PipeBus.lay(taps, Set.of(), 3);
        assertEquals(25, flow.get(new Cell(5, 3, 5)), 1e-9);
        assertEquals(10, flow.get(new Cell(0, 1, 0)), 1e-9);
        assertEquals(15, flow.get(new Cell(0, 1, 10)), 1e-9);
        assertTrue(flow.get(new Cell(0, 3, 5)) >= 25);
    }

    @Test
    void oneTapIsNotABus() {
        assertTrue(
            PipeBus.lay(List.of(new Tap(new Cell(0, 0, 0), 5)), Set.of(), 2)
                .isEmpty());
    }

    @Test
    void pipeFacesJoinAlongTheRun() {
        final Cell a = new Cell(0, 0, 0), b = new Cell(1, 0, 0), dock = new Cell(-1, 0, 0);
        assertEquals(16 | 32, PipeConnect.mask(a, Set.of(a, b), Set.of(dock), Set.of(a)));
        assertEquals(16, PipeConnect.mask(b, Set.of(a, b), Set.of(dock), Set.of(a)));
    }

    @Test
    void onlyTheExitJoinsTheMachine() {
        final Cell machine = new Cell(0, 0, 0), exit = new Cell(1, 0, 0), side = new Cell(0, 0, 1);
        final Set<Cell> same = Set.of(exit, new Cell(1, 0, 1), side);
        assertTrue((PipeConnect.mask(exit, same, Set.of(machine), Set.of(exit)) & 16) != 0);
        assertEquals(0, PipeConnect.mask(side, same, Set.of(machine), Set.of(exit)) & 4);
    }

    @Test
    void alongStaysOnTheAllowedCells() {
        final Cell a = new Cell(0, 0, 0), b = new Cell(2, 0, 0), mid = new Cell(1, 0, 0);
        final List<Cell> path = BlockRoute.along(a, b, Set.of(a, mid, b));
        assertEquals(List.of(a, mid, b), path);
        assertTrue(
            BlockRoute.along(a, b, Set.of(a, b))
                .isEmpty());
    }

    @Test
    void distillationTowerFluidsSitOnTheirOwnLayer() {
        final List<String> fluids = List
            .of("fluid:creosote", "fluid:phenol", "fluid:toluene", "fluid:dimethylbenzene", "fluid:benzene");
        assertEquals(1, TowerLayers.layer(fluids, "fluid:creosote"));
        assertEquals(5, TowerLayers.layer(fluids, "fluid:benzene"));
        assertEquals(-1, TowerLayers.layer(fluids, "fluid:water"));
    }

    @Test
    void itemAndFluidDoNotShareABlockOrHatch() {
        final PipeClaims claims = new PipeClaims();
        final Cell hatch = new Cell(0, 0, 0);
        final Cell pipe = new Cell(1, 0, 0);
        claims.claim(List.of(pipe), hatch, null, "fluid:lightfuel");
        assertTrue(
            claims.blocked(Set.of(), "item:dust")
                .contains(pipe));
        assertFalse(
            claims.blocked(Set.of(), "fluid:lightfuel")
                .contains(pipe));
        assertFalse(claims.hatchFree(hatch));
        assertTrue(
            claims.blockedAll(Set.of())
                .contains(pipe));
    }

    @Test
    void differentFluidsDoNotShareABlockOrHatch() {
        final PipeClaims claims = new PipeClaims();
        final Cell hatch = new Cell(0, 0, 0);
        final Cell pipe = new Cell(1, 0, 0);
        claims.claim(List.of(pipe), hatch, null, "fluid:lightfuel");
        assertTrue(
            claims.blocked(Set.of(), "fluid:fuel")
                .contains(pipe));
        assertFalse(claims.hatchFree(hatch));
    }

    @Test
    void eachPairingTakesItsOwnHatch() {
        final PipeClaims claims = new PipeClaims();
        final Cell first = new Cell(0, 0, 0), second = new Cell(0, 1, 0);
        claims.claim(List.of(), first, null, "fluid:ethanol");
        assertFalse(claims.hatchFree(first));
        assertTrue(claims.hatchFree(second));
        claims.claim(List.of(), second, null, "fluid:ethanol");
        assertFalse(claims.hatchFree(second));
    }
}
