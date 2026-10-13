package com.gtnhplanner.ui.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.gtnhplanner.logistics.BlockRoute;
import com.gtnhplanner.logistics.BlockRoute.Cell;
import com.gtnhplanner.logistics.PipeBus;
import com.gtnhplanner.logistics.PipeCatalog;
import com.gtnhplanner.logistics.PipeClaims;
import com.gtnhplanner.logistics.PipeConnect;
import com.gtnhplanner.logistics.PipeSelect;
import com.gtnhplanner.ui.gt.StructureGhosts;
import com.gtnhplanner.ui.gt.StructureGhosts.Hatch;

/**
 * Where pipes go between placed machines: one run for each connection, or, with the shared bus on, one trunk for
 * everything moving the same item or fluid. A multiblock is joined at a casing its structure will take a hatch of the
 * right kind on, the one whose exit is nearest the other end; the block in front of that hatch is the first pipe. Item
 * and two resources never share a block or a hatch, and a single-block machine gives each one its own side.
 */
final class PipeBlocks {

    /** A placed machine the route can leave or enter. */
    record Machine(int x, int y, int z, int facing, int size, @Nullable ItemStack stack, StructureGhosts.Needs needs,
        double hx, double hy, double hz) {}

    /**
     * One solved connection, {@code from} and {@code to} indexes into the machine list. {@code layer} is how far above
     * the controller a distillation tower's hatch for this fluid sits, or -1 when any hatch of the kind will do.
     */
    record Link(int from, int to, String resource, double perSecond, boolean fluid, int stackSize, int temperature,
        boolean gas, int layer) {}

    /** A pipe block to draw, and a hatch to mark. {@code connections} is {@link PipeConnect}'s face mask. */
    record Mark(int x, int y, int z, @Nullable ItemStack stack, int connections, float thickness) {

        Mark(final int x, final int y, final int z, @Nullable final ItemStack stack) {
            this(x, y, z, stack, 0, 0);
        }
    }

    /** A laid run between two machines, so the overlay wire can follow those blocks. */
    record Route(int fromX, int fromY, int fromZ, int toX, int toY, int toZ, String resource, List<Cell> cells) {}

    record Drawn(List<Mark> pipes, List<Mark> hatches, List<Route> routes) {}

    private static final Drawn NONE = new Drawn(List.of(), List.of(), List.of());
    private static final int MAX_BLOCKS = 2000;

    @Nullable
    private static Drawn cached;
    private static String cachedKey = "";
    private static final Map<Integer, ItemStack> STACKS = new HashMap<>();

    private PipeBlocks() {}

    static Drawn draw(final List<Machine> machines, final List<Link> links, final boolean shared) {
        final String key = key(machines, links, shared);
        if (key.equals(cachedKey) && cached != null) return cached;
        cachedKey = key;
        cached = build(machines, links, shared);
        return cached;
    }

    private static String key(final List<Machine> machines, final List<Link> links, final boolean shared) {
        final StringBuilder text = new StringBuilder();
        text.append(shared);
        for (final Machine machine : machines) {
            final StructureGhosts.Ghost ghost = StructureGhosts.peek(machine.stack, machine.needs, machine.size);
            text.append('|')
                .append(machine.x())
                .append(',')
                .append(machine.y())
                .append(',')
                .append(machine.z())
                .append(',')
                .append(machine.facing)
                .append(',')
                .append(machine.size)
                .append('g')
                .append(ghost == null ? -1 : ghost.blocks());
        }
        for (final Link link : links) text.append('|')
            .append(link.resource)
            .append('@')
            .append(link.perSecond)
            .append(link.fluid ? 'f' : 'i');
        return text.toString();
    }

    private static Drawn build(final List<Machine> machines, final List<Link> links, final boolean shared) {
        if (machines.isEmpty() || links.isEmpty()) return NONE;
        final Set<Cell> occupied = new HashSet<>();
        for (final Machine machine : machines) occupied.addAll(blocksOf(machine));
        final Map<Cell, Load> loads = new LinkedHashMap<>();
        final List<Mark> hatches = new ArrayList<>();
        final Set<Cell> marked = new HashSet<>();
        final PipeClaims claims = new PipeClaims();
        final Map<String, Set<Cell>> docks = new HashMap<>();
        final Map<String, Set<Cell>> ports = new HashMap<>();
        final List<Route> routes = new ArrayList<>();
        if (!shared) {
            for (final Link link : links)
                layDirect(machines, link, occupied, claims, loads, hatches, marked, docks, ports, routes);
        } else {
            final Map<String, List<Link>> groups = new LinkedHashMap<>();
            for (final Link link : links) groups.computeIfAbsent(link.resource, key -> new ArrayList<>())
                .add(link);
            for (final List<Link> group : groups.values()) {
                if (group.size() < 2)
                    layDirect(machines, group.get(0), occupied, claims, loads, hatches, marked, docks, ports, routes);
                else layBus(machines, group, occupied, claims, loads, hatches, marked, docks, ports, routes);
            }
        }
        final Map<String, Set<Cell>> same = new HashMap<>();
        for (final Map.Entry<Cell, Load> entry : loads.entrySet()) {
            if (occupied.contains(entry.getKey())) continue;
            same.computeIfAbsent(entry.getValue().resource, key -> new HashSet<>())
                .add(entry.getKey());
        }
        final List<Mark> pipes = new ArrayList<>();
        for (final Map.Entry<Cell, Load> entry : loads.entrySet()) {
            if (pipes.size() >= MAX_BLOCKS) break;
            final Cell cell = entry.getKey();
            if (occupied.contains(cell)) continue;
            final ItemStack stack = stack(entry.getValue());
            if (stack == null) continue;
            final String resource = entry.getValue().resource;
            final int connections = PipeConnect.mask(
                cell,
                same.getOrDefault(resource, Set.of()),
                docks.getOrDefault(resource, Set.of()),
                ports.getOrDefault(resource, Set.of()));
            pipes.add(new Mark(cell.x(), cell.y(), cell.z(), stack, connections, thickness(stack)));
        }
        return new Drawn(pipes, hatches, routes);
    }

    /** One connection's own run, each block carrying that connection's rate. */
    private static void layDirect(final List<Machine> machines, final Link link, final Set<Cell> occupied,
        final PipeClaims claims, final Map<Cell, Load> loads, final List<Mark> hatches, final Set<Cell> marked,
        final Map<String, Set<Cell>> docks, final Map<String, Set<Cell>> ports, final List<Route> routes) {
        final Set<Cell> blocked = claims.blocked(occupied, link.resource);
        final Join from = join(
            machines.get(link.from),
            true,
            link,
            machines.get(link.to),
            blocked,
            claims,
            hatches,
            marked);
        final Join to = join(
            machines.get(link.to),
            false,
            link,
            machines.get(link.from),
            blocked,
            claims,
            hatches,
            marked);
        if (from == null || to == null) return;
        dock(docks, ports, link, machines.get(link.from), from);
        dock(docks, ports, link, machines.get(link.to), to);
        final List<Cell> path = BlockRoute.path(from.exit, to.exit, blocked);
        claims.claim(path, from.hatch, to.hatch, link.resource);
        for (final Cell cell : path) loads.merge(cell, new Load(link, link.perSecond), Load::add);
        if (path.size() >= 2) routes.add(route(link, machines, path));
    }

    /** Every connection of one resource on one trunk, each block carrying what passes through it. */
    private static void layBus(final List<Machine> machines, final List<Link> group, final Set<Cell> occupied,
        final PipeClaims claims, final Map<Cell, Load> loads, final List<Mark> hatches, final Set<Cell> marked,
        final Map<String, Set<Cell>> docks, final Map<String, Set<Cell>> ports, final List<Route> routes) {
        int stack = Integer.MAX_VALUE, temperature = 0;
        boolean gas = false;
        for (final Link link : group) {
            stack = Math.min(stack, link.stackSize);
            temperature = Math.max(temperature, link.temperature);
            gas |= link.gas;
        }
        final Link sample = new Link(
            group.get(0).from,
            group.get(0).to,
            group.get(0).resource,
            0,
            group.get(0).fluid,
            stack,
            temperature,
            gas,
            group.get(0).layer);
        final Set<Cell> blocked = claims.blocked(occupied, group.get(0).resource);
        final List<PipeBus.Tap> taps = new ArrayList<>();
        int ceiling = 0;
        for (final Cell cell : blocked) ceiling = Math.max(ceiling, cell.y());
        Cell fromHatch = null, toHatch = null;
        final List<Join> froms = new ArrayList<>(), tos = new ArrayList<>();
        final List<Link> ended = new ArrayList<>();
        for (final Link link : group) {
            final Join from = join(
                machines.get(link.from),
                true,
                link,
                machines.get(link.to),
                blocked,
                claims,
                hatches,
                marked);
            final Join to = join(
                machines.get(link.to),
                false,
                link,
                machines.get(link.from),
                blocked,
                claims,
                hatches,
                marked);
            if (from != null) {
                taps.add(new PipeBus.Tap(from.exit, link.perSecond));
                dock(docks, ports, link, machines.get(link.from), from);
                if (from.hatch != null) fromHatch = from.hatch;
            }
            if (to != null) {
                taps.add(new PipeBus.Tap(to.exit, -link.perSecond));
                dock(docks, ports, link, machines.get(link.to), to);
                if (to.hatch != null) toHatch = to.hatch;
            }
            if (from != null && to != null) {
                ended.add(link);
                froms.add(from);
                tos.add(to);
            }
        }
        final Map<Cell, Double> laid = PipeBus.lay(taps, blocked, ceiling + 1);
        claims.claim(laid.keySet(), fromHatch, toHatch, group.get(0).resource);
        for (final Map.Entry<Cell, Double> entry : laid.entrySet())
            loads.merge(entry.getKey(), new Load(sample, entry.getValue()), Load::add);
        for (int i = 0; i < ended.size(); i++) {
            final List<Cell> path = BlockRoute.along(froms.get(i).exit, tos.get(i).exit, laid.keySet());
            if (path.size() >= 2) routes.add(route(ended.get(i), machines, path));
        }
    }

    private static void dock(final Map<String, Set<Cell>> docks, final Map<String, Set<Cell>> ports, final Link link,
        final Machine machine, final Join join) {
        docks.computeIfAbsent(link.resource, key -> new HashSet<>())
            .add(join.hatch != null ? join.hatch : new Cell(machine.x(), machine.y(), machine.z()));
        ports.computeIfAbsent(link.resource, key -> new HashSet<>())
            .add(join.exit);
    }

    private static Route route(final Link link, final List<Machine> machines, final List<Cell> path) {
        final Machine from = machines.get(link.from), to = machines.get(link.to);
        return new Route(from.x(), from.y(), from.z(), to.x(), to.y(), to.z(), link.resource, path);
    }

    /**
     * Where a machine joins a pipe: the cell in front of a free hatch of the right kind (a distillation tower's fluid
     * on its own layer), or a free face on a single-block. Two resources never share that face; the first pipe is never
     * inside a neighbour.
     */
    @Nullable
    private static Join join(final Machine machine, final boolean output, final Link link, final Machine other,
        final Set<Cell> occupied, final PipeClaims claims, final List<Mark> hatches, final Set<Cell> marked) {
        final Set<Cell> self = blocksOf(machine);
        final Cell toward = new Cell(
            (int) Math.floor(other.hx),
            (int) Math.floor(other.hy),
            (int) Math.floor(other.hz));
        final StructureGhosts.Ghost ghost = StructureGhosts.peek(machine.stack, machine.needs, machine.size);
        Join best = null;
        double bestDistance = Double.MAX_VALUE;
        if (ghost != null) for (final Hatch hatch : StructureGhosts.hatches(ghost, link.fluid, output)) {
            final int[] at = world(machine, hatch.dx(), hatch.dy(), hatch.dz());
            final Cell hatchCell = new Cell(at[0], at[1], at[2]);
            if (!claims.hatchFree(hatchCell, link.resource)) continue;
            if (output && link.layer >= 0 && hatch.dy() != link.layer) continue;
            final int[] step = step(machine.facing, hatch.fx(), hatch.fy(), hatch.fz());
            final Cell exit = BlockRoute.outside(hatchCell, step[0], step[1], step[2], self);
            if (occupied.contains(exit) || self.contains(exit)) continue;
            final double distance = square(
                exit.x() + 0.5 - other.hx,
                exit.y() + 0.5 - other.hy,
                exit.z() + 0.5 - other.hz);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = new Join(exit, hatchCell);
            }
        }
        if (best == null) {
            final Cell exit = BlockRoute
                .freeExit(new Cell(machine.x(), machine.y(), machine.z()), self, occupied, toward);
            if (exit == null) return null;
            best = new Join(exit, null);
        }
        if (best.hatch != null && marked.add(best.hatch))
            hatches.add(new Mark(best.hatch.x(), best.hatch.y(), best.hatch.z(), null));
        return best;
    }

    private static Set<Cell> blocksOf(final Machine machine) {
        final Set<Cell> out = new HashSet<>();
        final StructureGhosts.Ghost ghost = StructureGhosts.peek(machine.stack, machine.needs, machine.size);
        final List<int[]> blocks = ghost == null ? List.of() : StructureGhosts.blocks(ghost);
        if (blocks.isEmpty()) {
            out.add(new Cell(machine.x(), machine.y(), machine.z()));
            return out;
        }
        for (final int[] block : blocks) {
            final int[] at = world(machine, block[0], block[1], block[2]);
            out.add(new Cell(at[0], at[1], at[2]));
        }
        return out;
    }

    /** A structure block's place in the world, the controller turned to face the way it was placed. */
    private static int[] world(final Machine machine, final int dx, final int dy, final int dz) {
        final int[] turned = StructureGhosts.turn(dx, dz, machine.facing);
        return new int[] { machine.x() + turned[0], machine.y() + dy, machine.z() + turned[1] };
    }

    /** A hatch front's step, turned the same way. */
    private static int[] step(final int facing, final int fx, final int fy, final int fz) {
        final int[] turned = StructureGhosts.turn(fx, fz, facing);
        return new int[] { Integer.signum(turned[0]), Integer.signum(fy), Integer.signum(turned[1]) };
    }

    @Nullable
    private static ItemStack stack(final Load load) {
        final PipeSelect.Choice choice = PipeSelect.choose(
            load.fluid ? PipeCatalog.Kind.FLUID : PipeCatalog.Kind.ITEM,
            load.flow,
            Math.max(1, load.stackSize),
            load.temperature,
            load.gas);
        if (choice == null || choice.spec() == null) return null;
        return pipeItem(
            choice.spec()
                .meta());
    }

    private static float thickness(final ItemStack stack) {
        try {
            final Object machine = gregtech.api.GregTechAPI.METATILEENTITIES[stack.getItemDamage()];
            if (machine instanceof final gregtech.api.metatileentity.MetaPipeEntity pipe) return pipe.getThickness();
        } catch (final RuntimeException | LinkageError ignored) {}
        return 0.375f;
    }

    @Nullable
    private static ItemStack pipeItem(final int meta) {
        final ItemStack known = STACKS.get(meta);
        if (known != null) return known;
        final Object item = Item.itemRegistry.getObject("gregtech:gt.blockmachines");
        if (!(item instanceof final Item found)) return null;
        final ItemStack stack = new ItemStack(found, 1, meta);
        STACKS.put(meta, stack);
        return stack;
    }

    private static double square(final double x, final double y, final double z) {
        return x * x + y * y + z * z;
    }

    private record Join(Cell exit, @Nullable Cell hatch) {}

    /** What one resource asks of a block, so a shared block keeps the hotter, gassier, less stackable case. */
    private static final class Load {

        final String resource;
        final boolean fluid;
        double flow;
        int stackSize;
        int temperature;
        boolean gas;

        Load(final Link link, final double flow) {
            this.resource = link.resource;
            this.fluid = link.fluid;
            this.flow = flow;
            this.stackSize = link.stackSize;
            this.temperature = link.temperature;
            this.gas = link.gas;
        }

        /** The same resource adds; a different one keeps the block it already has. */
        static Load add(final Load left, final Load right) {
            if (!left.resource.equals(right.resource)) return left;
            left.flow += right.flow;
            left.stackSize = Math.min(left.stackSize, right.stackSize);
            left.temperature = Math.max(left.temperature, right.temperature);
            left.gas |= right.gas;
            return left;
        }
    }
}
