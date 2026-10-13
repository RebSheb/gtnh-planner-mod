package com.gtnhplanner.ui.world;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.item.ItemStack;

import org.lwjgl.opengl.GL11;

import com.gtnhplanner.ui.gt.StructureGhosts;

/**
 * Drawing marks in the world, between {@link #begin()} and {@link #end()} in a world-last render: block outlines that
 * show through walls (faint where hidden, bright where seen), beams, lines, and labels that face the camera.
 * Positions are world coordinates.
 */
final class WorldMarks {

    private WorldMarks() {}

    private static double cx, cy, cz;

    /** Sets the state the marks draw in: untextured, blended, unlit, relative to the camera. */
    static void begin() {
        cx = RenderManager.renderPosX;
        cy = RenderManager.renderPosY;
        cz = RenderManager.renderPosZ;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_LINE_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glPushMatrix();
        GL11.glTranslated(-cx, -cy, -cz);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_FOG);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
    }

    static void end() {
        GL11.glPopMatrix();
        GL11.glPopAttrib();
        GL11.glDepthMask(true);
        GL11.glColor4f(1, 1, 1, 1);
    }

    /** The camera's distance to a block's centre. */
    static double distance(final int x, final int y, final int z) {
        final double dx = x + 0.5 - cx, dy = y + 0.5 - cy, dz = z + 0.5 - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    static double cameraX() {
        return RenderManager.renderPosX;
    }

    static double cameraY() {
        return RenderManager.renderPosY;
    }

    static double cameraZ() {
        return RenderManager.renderPosZ;
    }

    /**
     * A block's outline with a faint fill: faint through whatever hides it, full where it is in view. {@code grow}
     * pushes it out past the block's faces.
     */
    static void outline(final int x, final int y, final int z, final int rgb, final float grow, final float width) {
        outline(x, y, z, rgb, grow, width, 1f);
    }

    /** {@link #outline} at a fraction of its strength. */
    static void outline(final int x, final int y, final int z, final int rgb, final float grow, final float width,
        final float strength) {
        outline(new double[] { x, y, z, x + 1, y + 1, z + 1 }, rgb, grow, width, strength);
    }

    /** {@link #outline} of a box {x0, y0, z0, x1, y1, z1}: a whole structure's. */
    static void outline(final double[] box, final int rgb, final float grow, final float width, final float strength) {
        final double x0 = box[0] - grow, y0 = box[1] - grow, z0 = box[2] - grow, x1 = box[3] + grow, y1 = box[4] + grow,
            z1 = box[5] + grow;
        GL11.glLineWidth(width);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        box(x0, y0, z0, x1, y1, z1, rgb, 0.06f * strength);
        edges(x0, y0, z0, x1, y1, z1, rgb, 0.35f * strength);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        edges(x0, y0, z0, x1, y1, z1, rgb, strength);
    }

    /** A soft column of light from the top of a block up into the sky, seen through everything. */
    static void beam(final int x, final int y, final int z, final int rgb) {
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        final double r = 0.12, mx = x + 0.5, mz = z + 0.5;
        final Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        final int top = 256;
        final double[][] corner = { { -r, -r }, { r, -r }, { r, r }, { -r, r } };
        for (int side = 0; side < 4; side++) {
            final double[] a = corner[side], b = corner[(side + 1) % 4];
            t.setColorRGBA_I(rgb, 90);
            t.addVertex(mx + a[0], y + 1, mz + a[1]);
            t.addVertex(mx + b[0], y + 1, mz + b[1]);
            t.setColorRGBA_I(rgb, 0);
            t.addVertex(mx + b[0], top, mz + b[1]);
            t.addVertex(mx + a[0], top, mz + a[1]);
        }
        t.draw();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    /** A straight line between two points, faint through walls and full where seen. */
    static void line(final double x0, final double y0, final double z0, final double x1, final double y1,
        final double z1, final int rgb, final float width) {
        GL11.glLineWidth(width);
        final Tessellator t = Tessellator.instance;
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        t.startDrawing(GL11.GL_LINES);
        t.setColorRGBA_I(rgb, 120);
        t.addVertex(x0, y0, z0);
        t.addVertex(x1, y1, z1);
        t.draw();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        t.startDrawing(GL11.GL_LINES);
        t.setColorRGBA_I(rgb, 230);
        t.addVertex(x0, y0, z0);
        t.addVertex(x1, y1, z1);
        t.draw();
    }

    /**
     * What a machine placed on (x, y, z) facing that way fills: its block, or its whole structure (as its card needs
     * it, at {@code size}, 0 as its recipe needs) once its ghost is built. {x0, y0, z0, x1, y1, z1}.
     */
    static double[] extent(@javax.annotation.Nullable final ItemStack machine, final StructureGhosts.Needs needs,
        final int size, final int x, final int y, final int z, final int facing) {
        final StructureGhosts.Ghost structure = StructureGhosts.peek(machine, needs, size);
        final double[] b = structure == null ? new double[] { 0, 0, 0, 1, 1, 1 } : structure.box(facing);
        return new double[] { x + b[0], y + b[1], z + b[2], x + b[3], y + b[4], z + b[5] };
    }

    /**
     * A machine's ghost's first pass: a multiblock's nearest faces into the depth buffer (see
     * {@link StructureGhosts#depth}); nothing for a single block. Every ghost's before any's {@link #machineGhost}.
     */
    static void ghostDepth(final ItemStack machine, final StructureGhosts.Needs needs, final int size, final int x,
        final int y, final int z, final int facing) {
        final StructureGhosts.Ghost structure = StructureGhosts.get(machine, needs, size);
        if (structure != null) StructureGhosts.depth(structure, x, y, z, facing);
    }

    /**
     * A placed machine's ghost: its whole structure as its card needs it, turned to {@code facing}, when it is a
     * GregTech multiblock that can be ghosted (its outside, after {@link #ghostDepth}); else its block, unless
     * {@code builtHere}.
     */
    static void machineGhost(final ItemStack machine, final StructureGhosts.Needs needs, final int size, final int x,
        final int y, final int z, final int facing, final boolean builtHere) {
        machineGhost(machine, needs, size, x, y, z, facing, builtHere, false);
    }

    /** {@link #machineGhost}, or with {@code solid} as solid blocks, opaque and behind real blocks as they would be. */
    static void machineGhost(final ItemStack machine, final StructureGhosts.Needs needs, final int size, final int x,
        final int y, final int z, final int facing, final boolean builtHere, final boolean solid) {
        final StructureGhosts.Ghost structure = StructureGhosts.get(machine, needs, size);
        if (structure != null) StructureGhosts.colour(structure, x, y, z, facing, solid);
        else if (!builtHere) ghost(machine, x, y, z, facing, solid);
    }

    private static final net.minecraft.client.renderer.RenderBlocks GHOST = new net.minecraft.client.renderer.RenderBlocks();

    /**
     * A ghost of a machine on a block: the machine's own block model, see-through and drawn over the world (front faces
     * only), so it shows whatever is on the block now, a stand-in or the ground. Not drawn for an item that is not a
     * block.
     */
    static void ghost(final net.minecraft.item.ItemStack machine, final int x, final int y, final int z,
        final int facing) {
        ghost(machine, x, y, z, facing, false);
    }

    /** {@link #ghost}, or with {@code solid} as a solid block: opaque, and hidden behind real blocks as one is. */
    static void ghost(final net.minecraft.item.ItemStack machine, final int x, final int y, final int z,
        final int facing, final boolean solid) {
        final net.minecraft.block.Block block = net.minecraft.block.Block.getBlockFromItem(machine.getItem());
        if (block == null || block == net.minecraft.init.Blocks.air) return;
        Minecraft.getMinecraft()
            .getTextureManager()
            .bindTexture(net.minecraft.client.renderer.texture.TextureMap.locationBlocksTexture);
        GL11.glPushMatrix();
        GL11.glTranslated(x + 0.5, y + 0.5, z + 0.5);
        // Its front turned to the facing: drawn as an item, a block shows its front one way (GregTech's machines to the
        // west, as their item; anything else to the south), turned as a structure's ghost is.
        GL11.glRotatef(-90f * (facing - itemFront(block)), 0, 1, 0);
        GL11.glScalef(0.96f, 0.96f, 0.96f);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_CULL_FACE);
        if (solid) {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDepthMask(true);
        } else {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
        }
        GL11.glEnable(GL11.GL_BLEND);
        // See-through however the model sets its colours: blend by a constant alpha (none, solid).
        org.lwjgl.opengl.GL14.glBlendColor(1, 1, 1, solid ? 1f : 0.55f);
        GL11.glBlendFunc(0x8003, 0x8004); // GL_CONSTANT_ALPHA, GL_ONE_MINUS_CONSTANT_ALPHA
        GL11.glColor4f(1, 1, 1, 1);
        // Transparent texels left out, as everywhere else: the constant alpha above ignores a texture's own, and the
        // clear parts of GregTech's glowing layers (a working machine's top) hold colours that would show.
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
        net.minecraft.client.renderer.RenderHelper.enableStandardItemLighting();
        try {
            GHOST.renderBlockAsItem(block, machine.getItemDamage(), 1f);
        } catch (final RuntimeException ignored) {}
        net.minecraft.client.renderer.RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDepthMask(false);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glPopMatrix();
    }

    /** The way a block drawn as an item has its front: GregTech's machines west (1), anything else south (0). */
    private static int itemFront(final net.minecraft.block.Block block) {
        final Object name = net.minecraft.block.Block.blockRegistry.getNameForObject(block);
        return name != null && name.toString()
            .startsWith("gregtech:gt.blockmachines") ? 1 : 0;
    }

    /**
     * Which way a placed machine faces, on its box {x0, y0, z0, x1, y1, z1}: the face its front is on, lit, and an
     * arrow on the ground out from it (facing 0 south, 1 west, 2 north, 3 east), so a turn always shows, whatever the
     * machine looks like.
     */
    static void front(final double[] b, final int facing, final int rgb, final float strength) {
        final int f = facing & 3;
        final double nx = f == 1 ? -1 : f == 3 ? 1 : 0, nz = f == 0 ? 1 : f == 2 ? -1 : 0;
        final double g = 0.01;
        // The face: its corners, from bottom left to top left as seen from in front.
        final double fx0, fz0, fx1, fz1;
        if (nz != 0) {
            final double z = nz > 0 ? b[5] + g : b[2] - g;
            fx0 = b[0];
            fx1 = b[3];
            fz0 = fz1 = z;
        } else {
            final double x = nx > 0 ? b[3] + g : b[0] - g;
            fz0 = b[2];
            fz1 = b[5];
            fx0 = fx1 = x;
        }
        final double y0 = b[1], y1 = b[4];
        final Tessellator t = Tessellator.instance;
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        t.startDrawingQuads();
        t.setColorRGBA_I(rgb, (int) (40 * strength));
        t.addVertex(fx0, y0, fz0);
        t.addVertex(fx1, y0, fz1);
        t.addVertex(fx1, y1, fz1);
        t.addVertex(fx0, y1, fz0);
        t.draw();
        // The arrow: out from the middle of the face's bottom edge, along the ground.
        final double mx = (fx0 + fx1) / 2, mz = (fz0 + fz1) / 2, ay = y0 + 0.03;
        final double px = -nz, pz = nx;
        final double[][] arrow = { { mx + nx * 0.15 + px * 0.35, mz + nz * 0.15 + pz * 0.35 },
            { mx + nx * 0.15 - px * 0.35, mz + nz * 0.15 - pz * 0.35 }, { mx + nx * 0.85, mz + nz * 0.85 } };
        for (final boolean seen : new boolean[] { false, true }) {
            if (seen) {
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDepthFunc(GL11.GL_LEQUAL);
            }
            t.startDrawing(GL11.GL_TRIANGLES);
            t.setColorRGBA_I(rgb, (int) ((seen ? 200 : 70) * strength));
            for (final double[] a : arrow) t.addVertex(a[0], ay, a[1]);
            t.draw();
            GL11.glLineWidth(seen ? 2.5f : 1.5f);
            t.startDrawing(GL11.GL_LINE_LOOP);
            t.setColorRGBA_I(rgb, (int) ((seen ? 255 : 90) * strength));
            t.addVertex(fx0, y0, fz0);
            t.addVertex(fx1, y0, fz1);
            t.addVertex(fx1, y1, fz1);
            t.addVertex(fx0, y1, fz0);
            t.draw();
        }
    }

    /**
     * A pipe ghost whose arms follow {@code connections} (Forge side bits: down 1, up 2, north 4, south 8, west 16,
     * east 32), so a run turns at corners instead of every block facing the same way.
     */
    static void ghostPipe(final ItemStack machine, final int x, final int y, final int z, final int connections,
        final float thickness) {
        final net.minecraft.block.Block block = net.minecraft.block.Block.getBlockFromItem(machine.getItem());
        if (block == null || block == net.minecraft.init.Blocks.air) return;
        if (connections == 0) {
            ghost(machine, x, y, z);
            return;
        }
        Minecraft.getMinecraft()
            .getTextureManager()
            .bindTexture(net.minecraft.client.renderer.texture.TextureMap.locationBlocksTexture);
        GL11.glPushMatrix();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_BLEND);
        org.lwjgl.opengl.GL14.glBlendColor(1, 1, 1, 0.55f);
        GL11.glBlendFunc(0x8003, 0x8004);
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
        net.minecraft.client.renderer.RenderHelper.enableStandardItemLighting();
        final float thick = thickness > 0 && thickness < 1 ? thickness : 0.375f;
        final float min = (1f - thick) / 2f, max = 1f - min;
        final int meta = machine.getItemDamage();
        net.minecraft.util.IIcon tex = null;
        try {
            tex = machine.getItem()
                .getIconFromDamage(meta);
        } catch (final RuntimeException ignored) {}
        if (tex == null) tex = icon(block, 0, meta);
        if (tex == null) {
            ghost(machine, x, y, z);
            return;
        }
        final boolean ao = GHOST.enableAO;
        GHOST.enableAO = false;
        final Tessellator tess = Tessellator.instance;
        try {
            tess.startDrawingQuads();
            tess.setColorOpaque_F(1, 1, 1);
            tess.setBrightness(0x00F000F0);
            pipeBox(block, tex, x, y, z, min, min, min, max, max, max);
            if ((connections & 1) != 0) pipeBox(block, tex, x, y, z, min, 0, min, max, min, max);
            if ((connections & 2) != 0) pipeBox(block, tex, x, y, z, min, max, min, max, 1, max);
            if ((connections & 4) != 0) pipeBox(block, tex, x, y, z, min, min, 0, max, max, min);
            if ((connections & 8) != 0) pipeBox(block, tex, x, y, z, min, min, max, max, max, 1);
            if ((connections & 16) != 0) pipeBox(block, tex, x, y, z, 0, min, min, min, max, max);
            if ((connections & 32) != 0) pipeBox(block, tex, x, y, z, max, min, min, 1, max, max);
            tess.draw();
        } catch (final RuntimeException e) {
            try {
                tess.draw();
            } catch (final RuntimeException ignored) {}
            ghost(machine, x, y, z);
        } finally {
            GHOST.enableAO = ao;
        }
        net.minecraft.client.renderer.RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glPopMatrix();
    }

    private static void pipeBox(final net.minecraft.block.Block block, final net.minecraft.util.IIcon tex, final int x,
        final int y, final int z, final float x0, final float y0, final float z0, final float x1, final float y1,
        final float z1) {
        GHOST.setRenderBounds(x0, y0, z0, x1, y1, z1);
        GHOST.renderFaceYNeg(block, x, y, z, tex);
        GHOST.renderFaceYPos(block, x, y, z, tex);
        GHOST.renderFaceZNeg(block, x, y, z, tex);
        GHOST.renderFaceZPos(block, x, y, z, tex);
        GHOST.renderFaceXNeg(block, x, y, z, tex);
        GHOST.renderFaceXPos(block, x, y, z, tex);
    }

    @javax.annotation.Nullable
    private static net.minecraft.util.IIcon icon(final net.minecraft.block.Block block, final int side,
        final int meta) {
        try {
            return block.getIcon(side, meta);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /**
     * Turns the drawing space into a flat panel facing the camera at a world point, a GUI pixel being {@code scale}
     * blocks, x to the right and y down as on screen; {@link #endPanel()} undoes it.
     */
    static void beginPanel(final double x, final double y, final double z, final float scale) {
        final RenderManager rm = RenderManager.instance;
        GL11.glPushMatrix();
        GL11.glTranslated(x, y, z);
        GL11.glRotatef(-rm.playerViewY, 0, 1, 0);
        GL11.glRotatef(
            Minecraft.getMinecraft().gameSettings.thirdPersonView == 2 ? -rm.playerViewX : rm.playerViewX,
            1,
            0,
            0);
        GL11.glScalef(-scale, -scale, scale);
    }

    static void endPanel() {
        GL11.glPopMatrix();
    }

    private static void box(final double x0, final double y0, final double z0, final double x1, final double y1,
        final double z1, final int rgb, final float alpha) {
        final Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.setColorRGBA_I(rgb, (int) (alpha * 255));
        t.addVertex(x0, y0, z0);
        t.addVertex(x1, y0, z0);
        t.addVertex(x1, y0, z1);
        t.addVertex(x0, y0, z1);
        t.addVertex(x0, y1, z0);
        t.addVertex(x0, y1, z1);
        t.addVertex(x1, y1, z1);
        t.addVertex(x1, y1, z0);
        t.addVertex(x0, y0, z0);
        t.addVertex(x0, y1, z0);
        t.addVertex(x1, y1, z0);
        t.addVertex(x1, y0, z0);
        t.addVertex(x0, y0, z1);
        t.addVertex(x1, y0, z1);
        t.addVertex(x1, y1, z1);
        t.addVertex(x0, y1, z1);
        t.addVertex(x0, y0, z0);
        t.addVertex(x0, y0, z1);
        t.addVertex(x0, y1, z1);
        t.addVertex(x0, y1, z0);
        t.addVertex(x1, y0, z0);
        t.addVertex(x1, y1, z0);
        t.addVertex(x1, y1, z1);
        t.addVertex(x1, y0, z1);
        t.draw();
    }

    private static void edges(final double x0, final double y0, final double z0, final double x1, final double y1,
        final double z1, final int rgb, final float alpha) {
        final Tessellator t = Tessellator.instance;
        t.startDrawing(GL11.GL_LINES);
        t.setColorRGBA_I(rgb, (int) (alpha * 255));
        final double[][] c = { { x0, y0, z0 }, { x1, y0, z0 }, { x1, y0, z1 }, { x0, y0, z1 }, { x0, y1, z0 },
            { x1, y1, z0 }, { x1, y1, z1 }, { x0, y1, z1 } };
        final int[][] e = { { 0, 1 }, { 1, 2 }, { 2, 3 }, { 3, 0 }, { 4, 5 }, { 5, 6 }, { 6, 7 }, { 7, 4 }, { 0, 4 },
            { 1, 5 }, { 2, 6 }, { 3, 7 } };
        for (final int[] k : e) {
            t.addVertex(c[k[0]][0], c[k[0]][1], c[k[0]][2]);
            t.addVertex(c[k[1]][0], c[k[1]][1], c[k[1]][2]);
        }
        t.draw();
    }
}
