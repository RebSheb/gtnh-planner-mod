package com.gtnhplanner.machines;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * A crop's outputs scale by the item they carry (issue 487): every drop of an item counts, so an item the table lists
 * twice is one output with both drops' worth, and two items keep their own.
 */
class CropDropsTest {

    private static final CropsNh.Drop TWO = new CropsNh.Drop("cropsnh:berry@1", 2, 2000),
        ONE = new CropsNh.Drop("cropsnh:berry@1", 1, 3000), OTHER = new CropsNh.Drop("minecraft:dye@4", 1, 5000);

    private static final CropsNh.Stats CROP = new CropsNh.Stats(
        3,
        600,
        Math.pow(0.95, 3),
        256,
        1,
        false,
        -1,
        null,
        List.of(TWO, ONE, OTHER));

    @Test
    void anItemListedTwiceScalesByBothDrops() {
        // Gain 10 on an LV Crop Manager (1.05 drop rounds).
        final Map<String, Object> settings = Map.of(CropsNh.GAIN, "10", CropsNh.MANAGER_TIER, "1");
        final double reference = CropsNh.expectedDrop(CROP, 31, TWO) + CropsNh.expectedDrop(CROP, 31, ONE);
        final double current = CropsNh.expectedDrop(CROP, 10, TWO) + CropsNh.expectedDrop(CROP, 10, ONE);
        assertEquals(current / reference * 1.05, CropsNh.outputMultiplier(CROP, settings, "cropsnh:berry@1"), 1e-12);
        assertEquals(
            CropsNh.expectedDrop(CROP, 10, OTHER) / CropsNh.expectedDrop(CROP, 31, OTHER) * 1.05,
            CropsNh.outputMultiplier(CROP, settings, "minecraft:dye@4"),
            1e-12);
    }
}
