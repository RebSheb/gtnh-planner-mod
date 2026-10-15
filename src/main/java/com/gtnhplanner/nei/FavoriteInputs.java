package com.gtnhplanner.nei;

import net.minecraft.item.ItemStack;

import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;

import codechicken.nei.FavoriteRecipes;

/**
 * The items a player favorited in a slot of NEI's recipe pages (its Toggle Favorite key, Shift+F by default, over a
 * slot that takes several items) are the ones NEI then shows in every such slot: a new card's inputs start on them too.
 */
public final class FavoriteInputs {

    private FavoriteInputs() {}

    /**
     * Sets each input of a new card whose slot takes several items, and whose recipe's own item is not a favorite, to
     * the first of its others that is. NEI builds without item favorites leave the card as it is.
     */
    public static void apply(final Node node) {
        try {
            for (int i = 0; i < node.inputs.size(); i++) {
                final Port<?> port = node.inputs.get(i);
                if (!port.hasChoices() || port.own() instanceof final ItemStack own && FavoriteRecipes.containsManual(own))
                    continue;
                for (final Object alt : port.getAlternatives()) {
                    if (alt instanceof final ItemStack stack && FavoriteRecipes.containsManual(stack)) {
                        node.pickInput(i, port.keyOf(alt));
                        break;
                    }
                }
            }
        } catch (final LinkageError | RuntimeException ignored) {
            // An older NEI, or favorites not loaded yet: the recipe's own items.
        }
    }
}
