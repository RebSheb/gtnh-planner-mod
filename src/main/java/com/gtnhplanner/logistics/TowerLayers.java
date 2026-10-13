package com.gtnhplanner.logistics;

import java.util.List;

/**
 * Where a distillation tower's fluid leaves: the controller and item bus sit on layer 0, and each fluid output in
 * recipe order has its own ring above that. The first fluid is layer 1, so the fifth fluid (recipe index 4, if 0 is
 * the item / controller) is layer 5.
 */
public final class TowerLayers {

    private TowerLayers() {}

    /**
     * How far above the controller that fluid's hatch sits, or -1 when the resource is not a fluid on this recipe.
     */
    public static int layer(final List<String> fluids, final String resource) {
        if (resource == null) return -1;
        int index = 0;
        for (final String fluid : fluids) {
            if (resource.equals(fluid)) return index + 1;
            index++;
        }
        return -1;
    }
}
