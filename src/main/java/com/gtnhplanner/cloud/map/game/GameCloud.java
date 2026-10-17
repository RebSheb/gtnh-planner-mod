package com.gtnhplanner.cloud.map.game;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

import javax.annotation.Nullable;

import com.google.gson.JsonObject;
import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.api.PlanAPI;
import com.gtnhplanner.cloud.map.CloudGame;
import com.gtnhplanner.cloud.net.LiveClient;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.importer.NodeMaker;
import com.gtnhplanner.importer.RecipeIndex;
import com.gtnhplanner.importer.game.GameNodeMaker;
import com.gtnhplanner.importer.game.NeiRecipeIndex;
import com.gtnhplanner.library.CommunityApi;
import com.gtnhplanner.library.PlanExport;
import com.gtnhplanner.library.Posting;
import com.gtnhplanner.machines.Bees;
import com.gtnhplanner.machines.web.Web;
import com.gtnhplanner.power.Energy;

import codechicken.nei.NEIClientConfig;

/**
 * The game's side of a shared plan's mapping: the posting export's names (library/Posting), NEI's recipes and the
 * importer's node maker, and the game's own key for what the website names. Client thread only (NEI).
 */
public final class GameCloud implements CloudGame {

    /** Cards are written at the count they are set to, never the solve's: the export's count is never asked. */
    private final PlanExport.World world = Posting.world(id -> 0d);
    private final RecipeIndex recipes = new NeiRecipeIndex();
    private final NodeMaker maker = new GameNodeMaker();
    private final String worldKey = worldKey();

    /**
     * The world placed machines are kept under: a server by its address (NEI's name for it), so everyone playing there
     * shares them; a single-player world by an id made for it the first time, kept beside its plans. Empty outside a
     * world. Hashed: a plan shared with others never names anyone's server or save.
     */
    public static String worldKey() {
        final String path = NEIClientConfig.getWorldPath();
        if (path == null || path.isEmpty()) return "";
        if (path.startsWith("remote/")) return "server:" + LiveClient.serverHash(path)
            .substring(0, 16);
        final File f = new File(
            PlanAPI.getSaveFile()
                .getParentFile(),
            "world-id.txt");
        try {
            if (f.isFile()) {
                final String id = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
                if (!id.isEmpty()) return "world:" + id;
            }
            final String id = UUID.randomUUID()
                .toString();
            f.getParentFile()
                .mkdirs();
            Files.write(f.toPath(), id.getBytes(StandardCharsets.UTF_8));
            return "world:" + id;
        } catch (final IOException e) {
            GtnhPlanner.LOG.warn("Could not keep this world's id; its placements go under its name", e);
            return "world:" + LiveClient.serverHash(path)
                .substring(0, 16);
        }
    }

    @Override
    public String world() {
        return worldKey;
    }

    @Override
    public RecipeIndex recipes() {
        return recipes;
    }

    @Override
    public NodeMaker maker() {
        return maker;
    }

    @Override
    @Nullable
    public String key(final String kind, final String id) {
        if ("power".equals(kind)) return Energy.KEY;
        return Posting.keyOf(new CommunityApi.Resource(kind, id, "", 0));
    }

    @Override
    @Nullable
    public PlanExport.Res port(final Port<?> port) {
        return world.port(port);
    }

    @Override
    @Nullable
    public PlanExport.Res resource(final String key) {
        return world.resource(key);
    }

    @Override
    public String machine(final Node node) {
        return world.machine(node);
    }

    @Override
    public double machines(final Node node) {
        return node.machineConfig.getMachineCount();
    }

    @Override
    public String tier(final Node node) {
        return world.tier(node);
    }

    @Override
    public boolean multiblock(final Node node) {
        return world.multiblock(node);
    }

    @Override
    @Nullable
    public String handlerId(final Node node) {
        return world.handlerId(node);
    }

    @Override
    @Nullable
    public PlanExport.Crop crop(final Node node) {
        return world.crop(node);
    }

    @Override
    @Nullable
    public Web.Recipe gregtech(final Node node) {
        return world.gregtech(node);
    }

    @Override
    @Nullable
    public JsonObject neiRecipe(final Node node) {
        return world.neiRecipe(node);
    }

    @Override
    @Nullable
    public Bees.Species bee(final Node node) {
        return world.bee(node);
    }

    /** As the card names its machine (ui/card/CardModel): a crop's harvester, a bee's species, else its machine. */
    @Override
    public String pinName(final Node node) {
        final com.gtnhplanner.machines.CropsNh.Setup crop = com.gtnhplanner.machines.game.CropCards.setup(node);
        if (crop != null) return com.gtnhplanner.machines.game.CropCards.harvesterName(crop);
        if (com.gtnhplanner.machines.game.BeeCards.setup(node) != null)
            return com.gtnhplanner.machines.game.BeeCards.cardName(node);
        return world.machine(node);
    }
}
