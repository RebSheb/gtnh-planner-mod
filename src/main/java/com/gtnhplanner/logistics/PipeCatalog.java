package com.gtnhplanner.logistics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * GregTech item and fluid pipes, as {@code LoaderMetaPipeEntities} registers them in GT5-Unofficial 5.09.54.205
 * (the version this mod compiles against) and as their tooltips rate them.
 *
 * <p>
 * A fluid pipe's tooltip prints {@code capacity × 20} litres a second. A quadruple or nonuple is several channels in
 * one block, each with that figure, so the rate here is the tooltip's number times the channel count. The pipe ticks
 * every five ticks and can push about twice the tooltip; sizing to the tooltip is the slower of the two.
 *
 * <p>
 * An item pipe's tooltip is how many stacks it moves, and how often: {@code slots × 20 / tickTime} stacks a second.
 * That rating does not depend on how long the pipe is. Restrictive pipes are the same rating with a routing penalty,
 * so they are listed and never chosen.
 */
public final class PipeCatalog {

    public enum Kind {
        ITEM,
        FLUID
    }

    public enum Size {

        TINY("Tiny"),
        SMALL("Small"),
        NORMAL(""),
        LARGE("Large"),
        HUGE("Huge"),
        QUADRUPLE("Quadruple"),
        NONUPLE("Nonuple");

        final String word;

        Size(final String word) {
            this.word = word;
        }
    }

    /** What one stack counts as when a port does not say. GregTech item pipes move whole stacks. */
    public static final int STACK = 64;

    /**
     * One pipe the player would actually run: its meta on {@code gregtech:gt.blockmachines}, and the rate its tooltip
     * claims. {@code tier} is the material's place in GregTech's registration, earlier being the one to prefer.
     */
    public record Spec(Kind kind, String material, Size size, boolean restrictive, int meta, int channels, int slots,
        int tickTime, int litresPerSecond, int heatKelvin, boolean gasProof, int tier) {

        /** Stacks a second, from the item-pipe tooltip. Zero for a fluid pipe. */
        public double stacksPerSecond() {
            return tickTime <= 0 ? 0 : slots * 20.0 / tickTime;
        }

        /**
         * Items a second at {@code stackSize}, or litres a second. A stack of 1 (a machine that will not stack) needs
         * a fatter pipe than a stack of 64 moving the same count of items.
         */
        public double perSecond(final int stackSize) {
            if (kind == Kind.FLUID) return litresPerSecond;
            return stacksPerSecond() * Math.max(1, stackSize);
        }

        /** "Small Copper pipe", "Tin pipe". The wire already says whether it is items or fluid. */
        public String name() {
            final String sizeWord = size.word.isEmpty() ? "" : size.word + " ";
            final String restrict = restrictive ? "Restrictive " : "";
            return restrict + sizeWord + material + " pipe";
        }
    }

    private static final List<Spec> ALL = Collections.unmodifiableList(build());

    private PipeCatalog() {}

    public static List<Spec> all() {
        return ALL;
    }

    private static List<Spec> build() {
        final List<Spec> out = new ArrayList<>();
        int tier = 0;
        tier = fluids(out, tier);
        items(out, tier);
        return out;
    }

    /** Tooltip litres a second: the constructor's capacity, times 20, times the channels in the block. */
    private static void fluid(final List<Spec> out, final int tier, final String material, final Size size,
        final int meta, final int capacity, final int heat, final int channels, final boolean gasProof) {
        out.add(
            new Spec(
                Kind.FLUID,
                material,
                size,
                false,
                meta,
                channels,
                0,
                0,
                capacity * 20 * channels,
                heat,
                gasProof,
                tier));
    }

    /** The seven sizes {@code FluidPipeBuilder} emits. {@code multi} 0 means the ids follow the five single sizes. */
    private static void fluidFamily(final List<Spec> out, final int tier, final String material, final int start,
        final int multi, final int base, final int heat) {
        final int multiStart = multi == 0 ? start + 5 : multi;
        fluid(out, tier, material, Size.TINY, start, base / 6, heat, 1, true);
        fluid(out, tier, material, Size.SMALL, start + 1, base / 3, heat, 1, true);
        fluid(out, tier, material, Size.NORMAL, start + 2, base, heat, 1, true);
        fluid(out, tier, material, Size.LARGE, start + 3, base * 2, heat, 1, true);
        fluid(out, tier, material, Size.HUGE, start + 4, base * 4, heat, 1, true);
        fluid(out, tier, material, Size.QUADRUPLE, multiStart, base, heat, 4, true);
        fluid(out, tier, material, Size.NONUPLE, multiStart + 1, base / 3, heat, 9, true);
    }

    private static int fluids(final List<Spec> out, int tier) {
        // Wood is registered on its own: not gas proof, and only three sizes.
        fluid(out, tier, "Wooden", Size.SMALL, 5101, 10, 350, 1, false);
        fluid(out, tier, "Wooden", Size.NORMAL, 5102, 30, 350, 1, false);
        fluid(out, tier, "Wooden", Size.LARGE, 5103, 60, 350, 1, false);
        tier++;
        fluidFamily(out, tier++, "Copper", 5110, 0, 20, 1000);
        fluidFamily(out, tier++, "Bronze", 5120, 0, 120, 2000);
        fluidFamily(out, tier++, "Steel", 5130, 0, 240, 2500);
        fluidFamily(out, tier++, "Stainless Steel", 5140, 0, 360, 3000);
        fluidFamily(out, tier++, "Titanium", 5150, 0, 480, 5000);
        fluidFamily(out, tier++, "Tungstensteel", 5160, 5270, 600, 7500);
        fluidFamily(out, tier++, "PBI", 5280, 5290, 600, 1000);
        // High pressure, on redstone's ids, gas proof.
        fluid(out, tier, "High Pressure", Size.SMALL, 5165, 4800, 1500, 1, true);
        fluid(out, tier, "High Pressure", Size.NORMAL, 5166, 7200, 1500, 1, true);
        fluid(out, tier, "High Pressure", Size.LARGE, 5167, 9600, 1500, 1, true);
        tier++;
        fluidFamily(out, tier++, "Plastic", 5170, 0, 360, 350);
        fluidFamily(out, tier++, "Niobium-Titanium", 5180, 0, 900, 2900);
        fluidFamily(out, tier++, "Enderium", 5190, 0, 1800, 15000);
        fluidFamily(out, tier++, "Naquadah", 5200, 0, 9000, 19000);
        fluidFamily(out, tier++, "Neutronium", 5210, 0, 16800, 1_000_000);
        fluidFamily(out, tier++, "Nether Star", 5220, 0, 19200, 1_000_000);
        fluidFamily(out, tier++, "Mysterious Crystal", 5230, 0, 24000, 1_000_000);
        fluidFamily(out, tier++, "Awakened Draconium", 5240, 0, 45000, 10_000_000);
        fluidFamily(out, tier++, "Infinity", 5250, 0, 60000, 10_000_000);
        fluidFamily(out, tier++, "Cast Iron", 5260, 0, 180, 2250);
        fluidFamily(out, tier++, "PTFE", 5680, 0, 480, 600);
        fluidFamily(out, tier++, "Spacetime", 5300, 0, 250000, Integer.MAX_VALUE);
        fluidFamily(out, tier++, "Transcendent Metal", 5310, 0, 220000, Integer.MAX_VALUE);
        fluidFamily(out, tier++, "Radox Polymer", 5760, 0, 5000, 1500);
        return tier;
    }

    private static void item(final List<Spec> out, final int tier, final String material, final Size size,
        final int meta, final int slots, final int tickTime, final boolean restrictive) {
        out.add(new Spec(Kind.ITEM, material, size, restrictive, meta, 1, slots, tickTime, 0, 0, true, tier));
    }

    /**
     * {@code ItemPipeBuilder}: slots and tick time scale off the huge pipe's slot count, and the huge pipe itself
     * ticks every 20. Ids are tiny, small, normal, large, huge, then the same again for the restrictive pipes.
     * Materials without a tiny and a small start at normal.
     */
    private static void itemFamily(final List<Spec> out, final int tier, final String material, final int[] ids,
        final int hugeSlots) {
        int i = 0;
        final boolean small = ids.length >= 10;
        if (small) {
            item(
                out,
                tier,
                material,
                Size.TINY,
                ids[i++],
                Math.max(hugeSlots / 16, 1),
                Math.max(16 / hugeSlots, 1) * 20,
                false);
            item(
                out,
                tier,
                material,
                Size.SMALL,
                ids[i++],
                Math.max(hugeSlots / 8, 1),
                Math.max(8 / hugeSlots, 1) * 20,
                false);
        }
        item(
            out,
            tier,
            material,
            Size.NORMAL,
            ids[i++],
            Math.max(hugeSlots / 4, 1),
            Math.max(4 / hugeSlots, 1) * 20,
            false);
        item(
            out,
            tier,
            material,
            Size.LARGE,
            ids[i++],
            Math.max(hugeSlots / 2, 1),
            Math.max(2 / hugeSlots, 1) * 20,
            false);
        item(out, tier, material, Size.HUGE, ids[i++], hugeSlots, 20, false);
        if (small) {
            item(
                out,
                tier,
                material,
                Size.TINY,
                ids[i++],
                Math.max(hugeSlots / 16, 1),
                Math.max(16 / hugeSlots, 1) * 20,
                true);
            item(
                out,
                tier,
                material,
                Size.SMALL,
                ids[i++],
                Math.max(hugeSlots / 8, 1),
                Math.max(8 / hugeSlots, 1) * 20,
                true);
        }
        item(
            out,
            tier,
            material,
            Size.NORMAL,
            ids[i++],
            Math.max(hugeSlots / 4, 1),
            Math.max(4 / hugeSlots, 1) * 20,
            true);
        item(
            out,
            tier,
            material,
            Size.LARGE,
            ids[i++],
            Math.max(hugeSlots / 2, 1),
            Math.max(2 / hugeSlots, 1) * 20,
            true);
        item(out, tier, material, Size.HUGE, ids[i], hugeSlots, 20, true);
    }

    private static int[] range(final int start, final int count) {
        final int[] ids = new int[count];
        for (int i = 0; i < count; i++) ids[i] = start + i;
        return ids;
    }

    private static void items(final List<Spec> out, int tier) {
        itemFamily(out, tier++, "Tin", range(5589, 10), 2);
        itemFamily(out, tier++, "Brass", new int[] { 5600, 5601, 5602, 5603, 5604, 5640, 5641, 5605, 5606, 5607 }, 4);
        itemFamily(
            out,
            tier++,
            "Electrum",
            new int[] { 5610, 5611, 5612, 5613, 5614, 5642, 5643, 5615, 5616, 5617 },
            8);
        itemFamily(
            out,
            tier++,
            "Platinum",
            new int[] { 5620, 5621, 5622, 5623, 5624, 5644, 5645, 5625, 5626, 5627 },
            16);
        itemFamily(out, tier++, "Osmium", new int[] { 5630, 5631, 5632, 5633, 5634, 5646, 5647, 5635, 5636, 5637 }, 32);
        itemFamily(out, tier++, "Electrum Flux", range(5650, 10), 64);
        itemFamily(out, tier++, "Black Plutonium", range(5660, 10), 128);
        itemFamily(out, tier++, "Bedrockium", range(5670, 10), 256);
        itemFamily(out, tier++, "PVC", range(5690, 6), 16);
        itemFamily(out, tier++, "Nickel", range(5700, 6), 4);
        itemFamily(out, tier++, "Cobalt", range(5710, 6), 8);
        itemFamily(out, tier++, "Aluminium", range(5720, 6), 8);
        itemFamily(out, tier, "Quantium", range(5730, 10), 512);
    }
}
