package com.gtnhplanner.ui.gt;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.world.World;

import com.gtnewhorizon.gtnhlib.client.renderer.TessellatorManager;
import com.gtnewhorizon.gtnhlib.client.renderer.vbo.VertexBuffer;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.DefaultVertexFormat;
import com.gtnhplanner.GtnhPlanner;

import blockrenderer6343.client.world.TrackedDummyWorld;

/**
 * A structure's ghost captured once into vertex buffers (GTNHLib's tessellator capture, as GregTech's own models use),
 * a render pass each, and drawn from them at every placement where none of its blocks is built yet: drawing every
 * block of every ghost again each frame was the world view's whole cost once a plan placed hundreds of multiblocks.
 * A placement with some of its blocks built is drawn block by block as before, leaving those out; which placements
 * those are is checked again every half second. Render thread only.
 */
final class GhostMeshes {

    private GhostMeshes() {}

    /** Each ghost's buffers, a pass each (null where the pass draws nothing); null when it could not be captured. */
    private static final Map<StructureGhosts.Ghost, VertexBuffer[]> MESHES = new IdentityHashMap<>();
    /** Set once capturing fails (an older GTNHLib, or none of this works here): every ghost draws block by block. */
    private static boolean broken;

    /** Whether a placement has any of its blocks built, as last checked, and when (ms). */
    private record Seen(boolean built, long at) {}

    private record Placement(StructureGhosts.Ghost ghost, int x, int y, int z, int facing) {}

    private static final Map<Placement, Seen> SEEN = new HashMap<>();
    private static final long RECHECK_MS = 500;

    /**
     * Draws the ghost's passes from its buffers, in the frame its caller set up (the controller's block, turned);
     * false when it has to be drawn block by block instead: some of it is built there, or it cannot be captured.
     */
    static boolean draw(final StructureGhosts.Ghost ghost, final StructureGhostBuilder.Built built,
        @Nullable final World real, final int x, final int y, final int z, final int facing) {
        if (broken || real != null && anyBuilt(ghost, built, real, x, y, z, facing)) return false;
        final VertexBuffer[] passes = meshes(ghost, built);
        if (passes == null) return false;
        for (final VertexBuffer pass : passes) if (pass != null) pass.render();
        return true;
    }

    @Nullable
    private static VertexBuffer[] meshes(final StructureGhosts.Ghost ghost, final StructureGhostBuilder.Built built) {
        if (MESHES.containsKey(ghost)) return MESHES.get(ghost);
        VertexBuffer[] passes = null;
        try {
            passes = new VertexBuffer[] { capture(built, 0), capture(built, 1) };
        } catch (final LinkageError | RuntimeException e) {
            broken = true;
            GtnhPlanner.LOG.warn("Multiblock ghosts cannot be kept as vertex buffers; drawing them block by block", e);
        }
        MESHES.put(ghost, passes);
        return passes;
    }

    /** One render pass of the ghost's blocks, captured; null when nothing in it draws in that pass. */
    @Nullable
    private static VertexBuffer capture(final StructureGhostBuilder.Built built, final int pass) {
        final TrackedDummyWorld world = built.world();
        TessellatorManager.startCapturing();
        boolean any = false;
        try {
            final Tessellator t = TessellatorManager.get();
            t.startDrawingQuads();
            t.setTranslation(
                -MultiblockRenderer.PLACE_X - 0.5,
                -MultiblockRenderer.PLACE_Y,
                -MultiblockRenderer.PLACE_Z - 0.5);
            for (final int[] b : built.blocks()) {
                final int wx = MultiblockRenderer.PLACE_X + b[0], wy = MultiblockRenderer.PLACE_Y + b[1],
                    wz = MultiblockRenderer.PLACE_Z + b[2];
                final Block block = world.getBlock(wx, wy, wz);
                if (!block.canRenderInPass(pass)) continue;
                try {
                    any |= built.render()
                        .renderBlockByRenderType(block, wx, wy, wz);
                } catch (final RuntimeException ignored) {
                    // A block that will not draw outside a real world is left out of the ghost.
                }
            }
            t.draw();
            t.setTranslation(0, 0, 0);
        } catch (final RuntimeException | LinkageError e) {
            // Never left capturing: the game's own drawing goes through the same tessellator.
            TessellatorManager.stopCapturingToBuffer(DefaultVertexFormat.POSITION_TEXTURE_COLOR_LIGHT);
            throw e;
        }
        final VertexBuffer vbo = TessellatorManager
            .stopCapturingToVBO(DefaultVertexFormat.POSITION_TEXTURE_COLOR_LIGHT);
        if (any && vbo.getVertexCount() == 0)
            throw new IllegalStateException("the block renderer drew past the capturing tessellator");
        if (!any) {
            vbo.delete();
            return null;
        }
        return vbo;
    }

    /** Whether any block of the ghost stands built in the real world at this placement, as checked lately. */
    private static boolean anyBuilt(final StructureGhosts.Ghost ghost, final StructureGhostBuilder.Built built,
        final World real, final int x, final int y, final int z, final int facing) {
        final Placement at = new Placement(ghost, x, y, z, facing);
        final long now = System.currentTimeMillis();
        final Seen seen = SEEN.get(at);
        if (seen != null && now - seen.at() < RECHECK_MS) return seen.built();
        boolean any = false;
        final TrackedDummyWorld world = built.world();
        for (final int[] b : built.blocks()) {
            final int wx = MultiblockRenderer.PLACE_X + b[0], wy = MultiblockRenderer.PLACE_Y + b[1],
                wz = MultiblockRenderer.PLACE_Z + b[2];
            if (StructureGhostBuilder
                .builtThere(real, world.getBlock(wx, wy, wz), world.getBlockMetadata(wx, wy, wz), b, x, y, z, facing)) {
                any = true;
                break;
            }
        }
        if (SEEN.size() > 20_000) SEEN.clear();
        SEEN.put(at, new Seen(any, now));
        return any;
    }
}
