package com.gtnhplanner.cloud.map;

import javax.annotation.Nullable;

import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.importer.FfIds;
import com.gtnhplanner.importer.NodeMaker;
import com.gtnhplanner.importer.RecipeIndex;
import com.gtnhplanner.library.PlanExport;
import com.gtnhplanner.power.Energy;

/**
 * What mapping a plan needs from the game: the export's names for its items, fluids and machines
 * ({@link PlanExport.World}), the importer's recipes and node maker for cards the website made, and the board's key
 * for a resource the website names. The game's own is {@code cloud.map.game.GameCloud}; tests stand in plain data.
 */
public interface CloudGame extends PlanExport.World {

    RecipeIndex recipes();

    NodeMaker maker();

    /**
     * The board's resource key for an item or fluid as the website names it (a drawer or a plan icon the website
     * set); null when the game lacks it.
     */
    @Nullable
    default String key(final String kind, final String id) {
        return "power".equals(kind) ? Energy.KEY : FfIds.toKey(kind, id);
    }

    /**
     * The world this game is in, as placed machines are kept by world: a card shows the placements made in its own
     * world and carries the rest. Empty for none.
     */
    default String world() {
        return "";
    }

    /**
     * A node read back from the game's own data for it ({@code Serializer.jsonToNode}, which reads its ports from its
     * recipe in NEI, or its power source): nothing more to do in game. Tests, with no NEI, give it its ports here.
     */
    default void rebuilt(final Node node) {}
}
