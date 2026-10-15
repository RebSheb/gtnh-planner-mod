package com.gtnhplanner.ui.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.harness.GtnhFlowLoader;
import com.gtnhplanner.machines.CropsNh;
import com.gtnhplanner.machines.game.CropCards;
import com.gtnhplanner.ui.gt.StructureGhosts;
import com.gtnhplanner.ui.theme.Fmt;

/**
 * Issue 8: a crop card's count is the crops planted, and the pin list showed that as its machines (an Industrial Farm
 * with a thousand crops read x1000). The machines a crop card builds are the harvesters its crops fill, as the
 * website's machine list counts them; the pin list, the minimap and the world count those.
 */
class CropMachineCountTest {

    /** Lazulia, as CropsNH has it: lapis under it, so a Crop Manager works two layers. */
    private static final CropsNh.Stats LAZULIA = new CropsNh.Stats(
        7,
        2800,
        0.6983373,
        256,
        1,
        false,
        -1,
        "Needs a block of lapis under it to grow",
        List.of(
            new CropsNh.Drop("gregtech:gt.metaitem.01@2526", 1, 6667),
            new CropsNh.Drop("minecraft:dye@4", 1, 3333)));

    @BeforeAll
    static void profiles() {
        GtnhFlowLoader.ensureDefaultMachineProfile();
    }

    private static Node crop(final String harvester, final String tierKey, final String tier) {
        final Node node = new Node(UUID.randomUUID(), 0, 0);
        node.properties.put(CropCards.STATS, CropCards.write(LAZULIA));
        node.machineConfig.settings.put(CropsNh.HARVESTER, harvester);
        node.machineConfig.settings.put(tierKey, tier);
        return node;
    }

    @Test
    void aSolvedCountIsPlantedWhole() {
        assertEquals(0, CropsNh.seeds(0));
        assertEquals(1, CropsNh.seeds(0.2));
        assertEquals(2602, CropsNh.seeds(2601.2));
        assertEquals(2601, CropsNh.seeds(2601.0000001));
    }

    @Test
    void anIndustrialFarmBuildsTheFarmsItsCropsFill() {
        // A HV seed bed: (4 x 4 + 7)^2 = 529 crops a farm.
        final Node farm = crop(CropsNh.FARM, CropsNh.SEED_BED_TIER, "4");
        assertEquals(529, CropCards.cropsPerHarvester(farm));
        assertEquals(1, CropCards.builds(farm, 3));
        assertEquals(1, CropCards.builds(farm, 529));
        assertEquals(2, CropCards.builds(farm, 1000));
        // A solved 529.4 crops are 530 planted: a second farm.
        assertEquals(2, CropCards.builds(farm, 529.4));
        assertEquals(0, CropCards.builds(farm, 0));
    }

    @Test
    void aCropManagerBuildsTheManagersItsCropsFill() {
        // LV: an 11 x 11 square, two layers over the lapis, less its own block.
        final Node manager = crop(CropsNh.MANAGER, CropsNh.MANAGER_TIER, "1");
        assertEquals(241, CropCards.cropsPerHarvester(manager));
        assertEquals(5, CropCards.builds(manager, 1000));
    }

    @Test
    void anyOtherCardBuildsItsCount() {
        final Node plain = new Node(UUID.randomUUID(), 0, 0);
        assertEquals(3.5, CropCards.builds(plain, 3.5), 1e-12);
    }

    @Test
    void aFluidTankIsOneTankHoweverManyCellsItHandles() {
        final Node tank = new Node(UUID.randomUUID(), 0, 0);
        tank.powerSource = com.gtnhplanner.power.FluidTank.ID;
        assertEquals(1, CropCards.builds(tank, 37.5), 1e-12);
        assertEquals(1, CropCards.builds(tank, 0), 1e-12);
    }

    private static PlanSnapshot.Card card(final UUID id, final String name, final String tier, final double count,
        final double builds, final boolean crop) {
        return new PlanSnapshot.Card(
            id,
            List.of(id),
            0,
            0,
            320,
            100,
            name,
            null,
            null,
            -1,
            count,
            true,
            List.of(),
            List.of(),
            0,
            0,
            List.of(),
            tier,
            1,
            null,
            List.of(),
            StructureGhosts.Needs.NONE,
            builds,
            crop,
            false);
    }

    @Test
    void thePinListCountsAFarmsHarvestersNotItsCrops() {
        final Node farm = crop(CropsNh.FARM, CropsNh.SEED_BED_TIER, "4");
        final UUID cropId = UUID.randomUUID(), mixerId = UUID.randomUUID();
        final PlanSnapshot snap = new PlanSnapshot(
            new Graph("Lazulia"),
            "Lazulia",
            0,
            List.of(
                card(cropId, "Industrial Farm", "HV", 1000, CropCards.builds(farm, 1000), true),
                card(mixerId, "Mixer", "LV", 2.5, 2.5, false)),
            List.of(),
            List.of(),
            0,
            0,
            Fmt.RateUnit.SECOND,
            List.of(cropId, mixerId));
        final List<PinList.Line> lines = PinList.lines(snap);
        assertEquals(2, lines.size());
        assertEquals(
            "Industrial Farm",
            lines.get(0)
                .name());
        assertEquals(
            List.of(new PinList.Tiered("HV", 2)),
            lines.get(0)
                .tiers());
        assertEquals(
            List.of(new PinList.Tiered("LV", 3)),
            lines.get(1)
                .tiers());
    }
}
