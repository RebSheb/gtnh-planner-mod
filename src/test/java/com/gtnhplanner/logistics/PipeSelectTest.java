package com.gtnhplanner.logistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.logistics.PipeCatalog.Kind;
import com.gtnhplanner.logistics.PipeCatalog.Size;
import com.gtnhplanner.logistics.PipeCatalog.Spec;
import com.gtnhplanner.logistics.PipeSelect.Choice;
import com.gtnhplanner.ui.theme.Fmt.RateUnit;

/**
 * The catalog's ratings are the ones GregTech prints on the pipe, and the choice is the smallest of those that
 * covers the flow.
 */
class PipeSelectTest {

    private static final double EPS = 1e-9;

    @Test
    void itemPipesMatchTheTooltip() {
        // Tiny tin is one stack every eight seconds; large tin is one a second; huge tin is two.
        assertEquals(0.125, find(Kind.ITEM, "Tin", Size.TINY).stacksPerSecond(), EPS);
        assertEquals(1, find(Kind.ITEM, "Tin", Size.LARGE).stacksPerSecond(), EPS);
        assertEquals(2, find(Kind.ITEM, "Tin", Size.HUGE).stacksPerSecond(), EPS);
        assertEquals(5589, find(Kind.ITEM, "Tin", Size.TINY).meta());
        assertTrue(restrictive(Kind.ITEM, "Tin", Size.TINY).restrictive());
        assertEquals(
            find(Kind.ITEM, "Tin", Size.TINY).stacksPerSecond(),
            restrictive(Kind.ITEM, "Tin", Size.TINY).stacksPerSecond(),
            EPS);
    }

    @Test
    void fluidPipesMatchTheTooltip() {
        // Copper's builder capacity is 20 L a tick on the medium pipe, and the tooltip multiplies by 20.
        assertEquals(60, find(Kind.FLUID, "Copper", Size.TINY).litresPerSecond());
        assertEquals(120, find(Kind.FLUID, "Copper", Size.SMALL).litresPerSecond());
        assertEquals(400, find(Kind.FLUID, "Copper", Size.NORMAL).litresPerSecond());
        assertEquals(1600, find(Kind.FLUID, "Copper", Size.HUGE).litresPerSecond());
        // A quadruple is four medium channels.
        assertEquals(1600, find(Kind.FLUID, "Copper", Size.QUADRUPLE).litresPerSecond());
        assertEquals(4, find(Kind.FLUID, "Copper", Size.QUADRUPLE).channels());
        assertFalse(find(Kind.FLUID, "Wooden", Size.SMALL).gasProof());
        assertEquals(200, find(Kind.FLUID, "Wooden", Size.SMALL).litresPerSecond());
    }

    @Test
    void choosesTheSmallestPipeThatCoversTheRate() {
        final Choice slow = PipeSelect.choose(Kind.FLUID, 50, 1, 300, false);
        assertNotNull(slow);
        assertEquals(
            "Copper",
            slow.spec()
                .material());
        assertEquals(
            Size.TINY,
            slow.spec()
                .size());
        assertEquals("Tiny Copper pipe · 60 L/s", slow.line(RateUnit.SECOND, 1));

        final Choice hundred = PipeSelect.choose(Kind.FLUID, 100, 1, 300, false);
        assertNotNull(hundred);
        assertEquals(
            Size.SMALL,
            hundred.spec()
                .size());
        assertEquals(
            "Copper",
            hundred.spec()
                .material());

        // Huge copper and quadruple copper are both 1600 L/s. One channel, not four.
        final Choice wide = PipeSelect.choose(Kind.FLUID, 1500, 1, 300, false);
        assertNotNull(wide);
        assertEquals(
            Size.HUGE,
            wide.spec()
                .size());
        assertEquals(
            1,
            wide.spec()
                .channels());
    }

    @Test
    void itemChoiceUsesTheStackSize() {
        // One item a second of something that stacks to 64 fits in a tiny tin (8 items a second).
        final Choice stacked = PipeSelect.choose(Kind.ITEM, 1, 64, 0, false);
        assertNotNull(stacked);
        assertEquals(
            "Tin",
            stacked.spec()
                .material());
        assertEquals(
            Size.TINY,
            stacked.spec()
                .size());
        assertFalse(
            stacked.spec()
                .restrictive());
        assertEquals("Tiny Tin pipe · 8/s", stacked.line(RateUnit.SECOND, 64));

        // The same one item a second, of something that does not stack, needs a large tin (1 stack a second).
        final Choice single = PipeSelect.choose(Kind.ITEM, 1, 1, 0, false);
        assertNotNull(single);
        assertEquals(
            Size.LARGE,
            single.spec()
                .size());
        assertEquals(
            "Tin",
            single.spec()
                .material());
    }

    @Test
    void hotFluidSkipsAPipeThatWouldMelt() {
        // Lava is 1300 K. Copper stops at 1000; tiny bronze is 400 L/s and survives.
        final Choice lava = PipeSelect.choose(Kind.FLUID, 100, 1, 1300, false);
        assertNotNull(lava);
        assertEquals(
            "Bronze",
            lava.spec()
                .material());
        assertEquals(
            Size.TINY,
            lava.spec()
                .size());
        assertTrue(
            lava.spec()
                .heatKelvin() >= 1300);
    }

    @Test
    void gasSkipsWoodenPipes() {
        final Choice gas = PipeSelect.choose(Kind.FLUID, 50, 1, 300, true);
        assertNotNull(gas);
        assertEquals(
            "Copper",
            gas.spec()
                .material());
        assertTrue(
            gas.spec()
                .gasProof());
    }

    @Test
    void parallelsWhenNothingSingleIsEnough() {
        final Spec biggest = find(Kind.ITEM, "Quantium", Size.HUGE);
        final double one = biggest.perSecond(64);
        final Choice two = PipeSelect.choose(Kind.ITEM, one * 2, 64, 0, false);
        assertNotNull(two);
        assertEquals(
            "Quantium",
            two.spec()
                .material());
        assertEquals(
            Size.HUGE,
            two.spec()
                .size());
        assertEquals(2, two.count());
        assertFalse(two.shortfall());
        assertTrue(
            two.line(RateUnit.SECOND, 64)
                .startsWith("2× "));

        final Choice tooMuch = PipeSelect.choose(Kind.ITEM, one * (PipeSelect.MAX_PARALLELS + 1), 64, 0, false);
        assertNotNull(tooMuch);
        assertEquals(PipeSelect.MAX_PARALLELS, tooMuch.count());
        assertTrue(tooMuch.shortfall());
        assertTrue(
            tooMuch.line(RateUnit.SECOND, 64)
                .endsWith(", short"));
    }

    @Test
    void noFlowHasNoPipe() {
        assertNull(PipeSelect.choose(Kind.FLUID, 0, 1, 300, false));
        assertNull(PipeSelect.choose(Kind.ITEM, -1, 64, 0, false));
    }

    private static Spec find(final Kind kind, final String material, final Size size) {
        return PipeCatalog.all()
            .stream()
            .filter(
                s -> s.kind() == kind && s.material()
                    .equals(material) && s.size() == size && !s.restrictive())
            .findFirst()
            .orElseThrow();
    }

    private static Spec restrictive(final Kind kind, final String material, final Size size) {
        return PipeCatalog.all()
            .stream()
            .filter(
                s -> s.kind() == kind && s.material()
                    .equals(material) && s.size() == size && s.restrictive())
            .findFirst()
            .orElseThrow();
    }
}
