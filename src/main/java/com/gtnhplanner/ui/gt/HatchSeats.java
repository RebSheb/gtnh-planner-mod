package com.gtnhplanner.ui.gt;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureElementChain;
import com.gtnewhorizon.structurelib.structure.IStructureWalker;
import com.gtnewhorizon.structurelib.structure.StructureDefinition;
import com.gtnhplanner.logistics.HatchKind;

import blockrenderer6343.client.world.TrackedDummyWorld;
import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/**
 * Where a built ghost will take a pipe hatch, from the structure's own definition: every casing that piece will accept,
 * not the hatch the preview happened to place. Loaded only when a route asks, so a failure cannot drop the ghost.
 */
final class HatchSeats {

    private static final Map<Object, List<StructureGhosts.Hatch>> CACHE = new IdentityHashMap<>();
    @Nullable
    private static ItemStack[] samples;

    private HatchSeats() {}

    static List<StructureGhosts.Hatch> of(final StructureGhosts.Ghost ghost) {
        final StructureGhostBuilder.Built built = (StructureGhostBuilder.Built) ghost.built();
        final List<StructureGhosts.Hatch> known = CACHE.get(built);
        if (known != null) return known;
        final List<StructureGhosts.Hatch> found = scan(built, ghost);
        CACHE.put(built, found);
        return found;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static List<StructureGhosts.Hatch> scan(final StructureGhostBuilder.Built built,
        final StructureGhosts.Ghost ghost) {
        final IMetaTileEntity machine = controllerOf(built.world());
        if (!(machine instanceof final IConstructable constructable)) return List.of();
        final IStructureDefinition<?> definition = constructable.getStructureDefinition();
        if (!(definition instanceof final StructureDefinition<?> structure)) return List.of();
        final IMetaTileEntity fresh = freshOf(machine);
        if (fresh == null) return List.of();
        final ItemStack trigger = built.trigger() != null ? built.trigger() : trigger();
        final ItemStack[] samples = samples();
        final Set<Long> occupied = new HashSet<>();
        for (final int[] block : built.blocks()) occupied.add(pack(block[0], block[1], block[2]));
        final int minX = ghost.minX(), minY = ghost.minY(), minZ = ghost.minZ();
        final int maxX = ghost.maxX(), maxY = ghost.maxY(), maxZ = ghost.maxZ();
        final int maxA = Math
            .min(20, Math.max(Math.abs(minX), Math.abs(maxX)) + Math.max(Math.abs(minZ), Math.abs(maxZ)) + 3);
        final int maxB = Math.min(20, Math.max(Math.abs(minY), Math.abs(maxY)) + 3);
        final Map<Long, StructureGhosts.Hatch> found = new TreeMap<>();
        for (final String piece : structure.getStructures()
            .keySet()) {
            for (int a = 0; a <= maxA; a++) {
                for (int b = 0; b <= maxB; b++) {
                    for (int c = 0; c <= maxA; c++) {
                        final List<int[]> hits = new ArrayList<>();
                        final boolean[] ok = { true };
                        try {
                            structure.iterate(
                                piece,
                                built.world(),
                                ExtendedFacing.SOUTH_NORMAL_NONE,
                                MultiblockRenderer.PLACE_X,
                                MultiblockRenderer.PLACE_Y,
                                MultiblockRenderer.PLACE_Z,
                                a,
                                b,
                                c,
                                new IStructureWalker() {

                                    @Override
                                    public boolean visit(final IStructureElement element, final World w, final int x,
                                        final int y, final int z, final int aa, final int bb, final int cc) {
                                        if (!ok[0]) return false;
                                        final int dx = x - MultiblockRenderer.PLACE_X,
                                            dy = y - MultiblockRenderer.PLACE_Y, dz = z - MultiblockRenderer.PLACE_Z;
                                        if (dx == 0 && dy == 0 && dz == 0) {
                                            ok[0] = false;
                                            return false;
                                        }
                                        if (dx < minX || dx > maxX
                                            || dy < minY
                                            || dy > maxY
                                            || dz < minZ
                                            || dz > maxZ) {
                                            ok[0] = false;
                                            return false;
                                        }
                                        try {
                                            if (!element.couldBeValid(machine, w, x, y, z, trigger)) {
                                                ok[0] = false;
                                                return false;
                                            }
                                        } catch (final RuntimeException | LinkageError e) {
                                            ok[0] = false;
                                            return false;
                                        }
                                        hits.add(new int[] { dx, dy, dz, takes(element, fresh, trigger, samples) });
                                        return true;
                                    }
                                });
                        } catch (final RuntimeException | LinkageError ignored) {
                            ok[0] = false;
                        }
                        if (!ok[0] || hits.isEmpty()) continue;
                        for (final int[] hit : hits) {
                            if (hit[3] == 0) continue;
                            final int[] face = openFace(hit[0], hit[1], hit[2], occupied);
                            for (int kind = 0; kind < 4; kind++) {
                                if ((hit[3] & (1 << kind)) == 0) continue;
                                final long key = pack(hit[0], hit[1], hit[2]) << 2 | kind;
                                found.putIfAbsent(
                                    key,
                                    new StructureGhosts.Hatch(
                                        hit[0],
                                        hit[1],
                                        hit[2],
                                        face[0],
                                        face[1],
                                        face[2],
                                        kind < 2,
                                        (kind & 1) == 1));
                            }
                        }
                    }
                }
            }
        }
        return new ArrayList<>(found.values());
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static int takes(final IStructureElement element, final IMetaTileEntity fresh, final ItemStack trigger,
        final ItemStack[] samples) {
        if (element instanceof final IStructureElementChain chain) {
            int bits = 0;
            for (final IStructureElement fallback : chain.fallbacks())
                bits |= takes(fallback, fresh, trigger, samples);
            return bits;
        }
        int named = fromDescription(element, fresh);
        if (named == 0) named = fromBuilder(hatchBuilder(element));
        if (named != 0) return named;
        return fromFilter(element, fresh, trigger, samples);
    }

    /** Display names kept on the hatch-adder builder, when the element itself has none. */
    private static int fromBuilder(final Object builder) {
        if (builder == null) return 0;
        int bits = 0;
        bits |= invokeList(builder, "getDescriptionNames");
        bits |= invokeString(builder, "getHatchItemTypeName");
        bits |= invokeSupplier(builder, "getHatchItemType");
        return bits;
    }

    private static int invokeList(final Object builder, final String method) {
        try {
            final Object value = builder.getClass()
                .getMethod(method)
                .invoke(builder);
            final Object list = value instanceof java.util.function.Supplier<?> supplier ? supplier.get() : value;
            if (!(list instanceof final List<?> names)) return 0;
            int bits = 0;
            for (final Object name : names) if (name != null) bits |= HatchKind.kindsIn(name.toString());
            return bits;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            return 0;
        }
    }

    private static int invokeString(final Object builder, final String method) {
        try {
            final Object value = builder.getClass()
                .getMethod(method)
                .invoke(builder);
            return value == null ? 0 : HatchKind.kindsIn(value.toString());
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            return 0;
        }
    }

    private static int invokeSupplier(final Object builder, final String method) {
        try {
            final Object value = builder.getClass()
                .getMethod(method)
                .invoke(builder);
            if (!(value instanceof final java.util.function.Supplier<?> supplier)) return 0;
            final Object text = supplier.get();
            return text == null ? 0 : HatchKind.kindsIn(text.toString());
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            return 0;
        }
    }

    /**
     * Hatch kinds named by the structure element itself. Used instead of
     * {@code HatchElementBuilder.getHatchItemFilter},
     * which some GregTech builds do not have.
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static int fromDescription(final IStructureElement element, final IMetaTileEntity fresh) {
        try {
            final List names = element.getDescription(fresh);
            if (names == null) return 0;
            int bits = 0;
            for (final Object name : names) if (name != null) bits |= HatchKind.kindsIn(name.toString());
            return bits;
        } catch (final RuntimeException | LinkageError e) {
            return 0;
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static int fromFilter(final IStructureElement element, final IMetaTileEntity fresh, final ItemStack trigger,
        final ItemStack[] samples) {
        final Object builder = hatchBuilder(element);
        if (builder == null) return 0;
        final BiFunction filter;
        try {
            filter = (BiFunction) builder.getClass()
                .getMethod("getHatchItemFilter")
                .invoke(builder);
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            return 0;
        }
        if (filter == null) return 0;
        int bits = 0;
        for (int kind = 0; kind < 4; kind++) {
            if (samples[kind] == null) continue;
            try {
                final Predicate test = (Predicate) filter.apply(fresh, trigger);
                if (test != null && test.test(samples[kind])) bits |= 1 << kind;
            } catch (final RuntimeException | LinkageError ignored) {}
        }
        return bits;
    }

    @Nullable
    private static Object hatchBuilder(final IStructureElement<?> element) {
        final Class<?> type = element.getClass();
        if (!type.getName()
            .contains("HatchElementBuilder")) return null;
        try {
            final Field owner = type.getDeclaredField("this$0");
            owner.setAccessible(true);
            return owner.get(element);
        } catch (final ReflectiveOperationException e) {
            return null;
        }
    }

    @Nullable
    private static IMetaTileEntity controllerOf(final TrackedDummyWorld world) {
        final net.minecraft.tileentity.TileEntity tile = world
            .getTileEntity(MultiblockRenderer.PLACE_X, MultiblockRenderer.PLACE_Y, MultiblockRenderer.PLACE_Z);
        if (!(tile instanceof final IGregTechTileEntity gt)) return null;
        return gt.getMetaTileEntity();
    }

    @Nullable
    private static IMetaTileEntity freshOf(final IMetaTileEntity machine) {
        try {
            return machine.newMetaEntity(machine.getBaseMetaTileEntity());
        } catch (final RuntimeException e) {
            return null;
        }
    }

    private static ItemStack trigger() {
        final ItemStack trigger = new ItemStack(StructureLibAPI.getDefaultHologramItem());
        trigger.stackSize = 1;
        return trigger;
    }

    private static ItemStack[] samples() {
        if (samples != null) return samples;
        final ItemStack[] out = new ItemStack[4];
        for (final IMetaTileEntity machine : GregTechAPI.METATILEENTITIES) {
            if (machine == null) continue;
            final String name = machine.getClass()
                .getSimpleName();
            for (int kind = 0; kind < 4; kind++) {
                if (out[kind] != null) continue;
                try {
                    if (HatchKind.matches(name, kind < 2, (kind & 1) == 1)) out[kind] = machine.getStackForm(1);
                } catch (final RuntimeException ignored) {}
            }
        }
        samples = out;
        return out;
    }

    private static int[] openFace(final int dx, final int dy, final int dz, final Set<Long> occupied) {
        final int[][] steps = { { 1, 0, 0 }, { -1, 0, 0 }, { 0, 0, 1 }, { 0, 0, -1 }, { 0, 1, 0 }, { 0, -1, 0 } };
        for (final int[] step : steps) {
            if (!occupied.contains(pack(dx + step[0], dy + step[1], dz + step[2]))) return step;
        }
        return steps[0];
    }

    private static long pack(final int x, final int y, final int z) {
        return (x + 512L & 0x3FF) << 20 | (y + 512L & 0x3FF) << 10 | (z + 512L & 0x3FF);
    }
}
