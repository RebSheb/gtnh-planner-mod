package com.gtnhplanner.power.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.importer.game.GameIds;
import com.gtnhplanner.machines.game.WebCards;
import com.gtnhplanner.power.sources.Reactors;

/**
 * NEI recipes whose card is a non-recipe machine here. The HTGR's NEI page (kubatech's HTGRRecipes map) shows each
 * fuel's whole making as one recipe: the dust it starts from in, and the mixture, the BISO and TRISO steps, the fuel,
 * the burned fuel and what that breaks down to all out. The reactor runs none of that: it takes the finished TRISO fuel
 * and gives back the burned fuel, as the HTGR card does (power/sources/Reactors, the website's HTGR). So a card from
 * that page is the HTGR card, burning the page's fuel.
 */
public final class RecipePower {

    /** kubatech's HTGR recipe map, as the game names it. */
    public static final String HTGR_MAP = "kubatech.htgrrecipes";

    /** A non-recipe machine at its settings. */
    public record Card(String source, Map<String, String> settings) {}

    /** The non-recipe machine an NEI recipe's card is, or null for a recipe card. */
    @Nullable
    public static Card of(final Node node) {
        if (!HTGR_MAP.equals(WebCards.mapId(node))) return null;
        final List<String> items = new ArrayList<>();
        for (final Port<?> port : node.outputs)
            if (port.getValue() instanceof final ItemStack stack && stack.getItem() != null)
                items.add(GameIds.itemId(stack));
        final String pebble = Reactors.htgrPebble(items);
        return pebble == null ? null : new Card(Reactors.HTGR, Map.of(Reactors.HTGR_PEBBLE, pebble));
    }

    private RecipePower() {}
}
