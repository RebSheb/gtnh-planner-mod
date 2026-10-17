package com.gtnhplanner.power;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.gtnhplanner.power.sources.Endgame;
import com.gtnhplanner.power.sources.Engines;
import com.gtnhplanner.power.sources.Railcraft;
import com.gtnhplanner.power.sources.Reactors;
import com.gtnhplanner.power.sources.Singleblocks;
import com.gtnhplanner.power.sources.Sofc;
import com.gtnhplanner.power.sources.SteamMakers;
import com.gtnhplanner.power.sources.Turbines;

/**
 * Every placeable power source, in the website's order (registry.ts). Source ids are stored in plans; never rename a
 * shipped id.
 */
public final class PowerRegistry {

    private static List<PowerSource> sources;
    private static Map<String, PowerSource> byId;

    public static synchronized List<PowerSource> sources() {
        if (sources == null) {
            final List<PowerSource> all = new ArrayList<>();
            all.addAll(Singleblocks.sources());
            all.addAll(Engines.sources());
            all.addAll(Sofc.sources());
            all.addAll(SteamMakers.sources());
            all.addAll(Turbines.sources());
            all.addAll(Reactors.reactorSources());
            all.addAll(Reactors.passiveSources());
            all.addAll(Endgame.sources());
            // The Other column's one source, after its planning cards.
            all.addAll(Railcraft.otherSources());
            final Map<String, PowerSource> index = new LinkedHashMap<>();
            for (final PowerSource source : all) index.put(source.id(), source);
            sources = Collections.unmodifiableList(all);
            byId = index;
        }
        return sources;
    }

    @Nullable
    public static PowerSource get(final String sourceId) {
        // The planning cards are listed apart from the generator catalog (others()).
        if (CustomRate.ID.equals(sourceId)) return CustomRate.SOURCE;
        if (FluidTank.ID.equals(sourceId)) return FluidTank.SOURCE;
        sources();
        return byId.get(sourceId);
    }

    /**
     * The planning cards, first in the picker's Other column: never in {@link #sources()}, the generator catalog. The
     * column's sources (the Railcraft Water Tank) follow them ({@link #inGroup}).
     */
    public static List<PowerSource> others() {
        return List.of(CustomRate.SOURCE, FluidTank.SOURCE);
    }

    public static List<PowerSource> inGroup(final PowerGroup group) {
        final List<PowerSource> out = new ArrayList<>();
        if (group == PowerGroup.OTHER) out.addAll(others());
        for (final PowerSource source : sources()) {
            if (source.group() == group) out.add(source);
        }
        return out;
    }

    private PowerRegistry() {}
}
