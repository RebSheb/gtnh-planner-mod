package com.gtnhplanner.ui.group;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Groups;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.card.CardModel;

/**
 * What a collapsed group's summary card says ({@code docs/design/groups.md}, "What the summary says"), from the
 * plan-wide solve over its members and the groups inside it: the machines to build (as the overview counts them), their
 * EU/t, and per resource what goes in (more used than made) and comes out (more made than used), busiest first.
 */
public record GroupSummary(int machines, double euPerTick, List<Flow> inputs, List<Flow> outputs) {

    /** One resource crossing the group's edge, a second. */
    public record Flow(String key, String name, ItemStack item, FluidStack fluid, double perSecond) {}

    public static final GroupSummary EMPTY = new GroupSummary(0, 0, List.of(), List.of());

    private static final double EPS = 1e-6;

    /** Rows the summary card draws: the longer list, one at least. */
    public int rows() {
        return Math.max(1, Math.max(inputs.size(), outputs.size()));
    }

    public static GroupSummary of(final BoardSession session, final UUID group) {
        final Graph g = session.graph();
        final Set<UUID> inside = new HashSet<>();
        for (final UUID id : Groups.descendants(g, group)) if (g.nodes.containsKey(id)) inside.add(id);
        int machines = 0;
        double eu = 0;
        final Map<String, double[]> net = new LinkedHashMap<>();
        final Map<String, Flow> looks = new LinkedHashMap<>();
        final Set<UUID> hosts = new HashSet<>();
        for (final UUID id : inside) {
            final CardModel card = session.models()
                .get(id);
            if (card == null || com.gtnhplanner.power.CustomRate.is(card.node)) continue;
            // A shared machine is one machine, however many recipes are on it.
            if (hosts.add(session.hostOf(id)) && (card.machines > 0 || card.pinned)
                && !com.gtnhplanner.power.FluidTank.is(card.node))
                machines += (int) Math
                    .ceil(com.gtnhplanner.machines.game.CropCards.builds(card.node, card.machines) - 1e-9);
            eu += session.power(card);
            for (final CardModel.PortView p : card.inputs) add(session, net, looks, p, p.perSecond());
            for (final CardModel.PortView p : card.outputs) add(session, net, looks, p, -p.perSecond());
        }
        final List<Flow> in = new ArrayList<>(), out = new ArrayList<>();
        for (final Map.Entry<String, double[]> e : net.entrySet()) {
            final double v = e.getValue()[0];
            if (Math.abs(v) <= EPS) continue;
            final Flow look = looks.get(e.getKey());
            final Flow f = new Flow(e.getKey(), look.name(), look.item(), look.fluid(), Math.abs(v));
            (v > 0 ? in : out).add(f);
        }
        in.sort((a, b) -> Double.compare(b.perSecond(), a.perSecond()));
        out.sort((a, b) -> Double.compare(b.perSecond(), a.perSecond()));
        return new GroupSummary(machines, eu, List.copyOf(in), List.copyOf(out));
    }

    private static void add(final BoardSession session, final Map<String, double[]> net, final Map<String, Flow> looks,
        final CardModel.PortView p, final double amount) {
        if (p.key() == null) return;
        final String key = session.canonicalKey(p.key());
        net.computeIfAbsent(key, k -> new double[1])[0] += amount;
        looks.putIfAbsent(key, new Flow(key, p.name(), p.item(), p.fluid(), 0));
    }
}
