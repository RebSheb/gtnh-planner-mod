package com.gtnhplanner.ui.task;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import com.gtnhplanner.cloud.map.PlanIds;
import com.gtnhplanner.cloud.map.game.GameCloud;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.TaskEdits;
import com.gtnhplanner.data.flowchart.Tasks;
import com.gtnhplanner.ui.BoardSession;

import codechicken.nei.NEIClientConfig;

/**
 * A task's things as the task box shows them: a machine by its card (its icon, the plan it is on, and where it is
 * placed in this world), a location by its spot (how far and which way from the player, or the dimension or world it
 * is in), a screenshot by its thumbnail, a quest by its icon and name (and whether the player has done it), and an
 * older save's line by its words. A card an older task linked with no thing of its own shows as a machine too (its id
 * is {@code link:} and the card), so nothing it held is lost.
 */
public final class TaskThings {

    private TaskThings() {}

    /** A loose card's id among the things: never a thing's own. */
    static final String LOOSE = "link:";

    /** Between the parts of a thing's line under its words, which wraps a whole part at a time. */
    static final String SEP = " · ";

    /**
     * A thing as shown: the item it is (a loose card's is made up, never in the plan), its kind, its words and the line
     * under them, its icon; {@code gone} for a card whose plan or card is no longer there; the plan and node of a card.
     */
    public record Shown(Tasks.Item item, String kind, String words, String detail, @Nullable ItemStack icon,
        boolean gone, @Nullable Graph plan, @Nullable Node node) {

        public boolean loose() {
            return item.id()
                .startsWith(LOOSE);
        }
    }

    /** A task's things in its order, then the cards it links that no thing names. */
    public static List<Shown> of(final BoardSession session, final Graph holder, final String taskId) {
        final List<Shown> out = new ArrayList<>();
        for (final Tasks.Item i : TaskEdits.items(holder, taskId)) out.add(shown(session, holder, i));
        int n = 0;
        for (final Tasks.Link l : TaskEdits.looseLinks(holder, taskId)) {
            final Tasks.Item made = new Tasks.Item(
                LOOSE + n++ + ":" + l.plan() + ":" + l.node(),
                taskId,
                Tasks.MACHINE,
                "",
                false,
                "",
                l,
                null,
                null,
                null);
            out.add(shown(session, holder, made));
        }
        return out;
    }

    static Shown shown(final BoardSession session, final Graph holder, final Tasks.Item i) {
        return switch (i.shownKind()) {
            case Tasks.MACHINE -> machine(session, holder, i);
            case Tasks.LOCATION -> location(i);
            case Tasks.QUEST -> quest(i);
            case Tasks.PICTURE -> new Shown(
                i,
                Tasks.PICTURE,
                "Screenshot",
                taken(i.picture()),
                null,
                false,
                null,
                null);
            default -> new Shown(i, "", i.text(), "", null, false, null, null);
        };
    }

    private static Shown machine(final BoardSession session, final Graph holder, final Tasks.Item i) {
        final Tasks.Link link = i.link();
        final Graph on = link == null ? null : session.planOf(link, holder);
        final Node node = on == null ? null : on.nodes.get(PlanIds.uuid(link.node()));
        if (node == null) {
            final String words = i.text()
                .isEmpty() ? "A machine" : i.text();
            return new Shown(
                i,
                Tasks.MACHINE,
                words,
                on == null ? "Its plan is gone" : "Its card is gone from " + on.getName(),
                null,
                true,
                on,
                null);
        }
        ItemStack icon = null;
        String name = node.machineName;
        if (name != null && name.startsWith("item:")) {
            icon = com.gtnhplanner.ui.Resources.item(name);
            name = icon != null ? icon.getDisplayName() : null;
        }
        final String words = !i.text()
            .isEmpty() ? i.text() : name != null ? name : "A machine";
        String detail = "On " + on.getName();
        final String placed = placed(node);
        if (placed != null) detail += SEP + placed;
        return new Shown(i, Tasks.MACHINE, words, detail, icon, false, on, node);
    }

    /** Where a card is placed in this world: "placed 40 m north-east", or "placed in another dimension"; else null. */
    @Nullable
    private static String placed(final Node node) {
        if (node.worldLinks.isEmpty()) return null;
        final EntityPlayer me = Minecraft.getMinecraft().thePlayer;
        if (me == null) return "placed in the world";
        final int dim = me.dimension;
        int[] nearest = null;
        double best = Double.MAX_VALUE;
        for (final int[] l : node.worldLinks) {
            if (l[0] != dim) continue;
            final double dx = l[1] + 0.5 - me.posX, dz = l[3] + 0.5 - me.posZ, d = dx * dx + dz * dz;
            if (d < best) {
                best = d;
                nearest = l;
            }
        }
        if (nearest == null) return "placed in another dimension";
        return "placed " + TaskEdits.howFar(nearest[1] + 0.5 - me.posX, nearest[3] + 0.5 - me.posZ);
    }

    /**
     * A quest: its name and icon as the quest book has them (as the task kept them where the book is not), and under
     * them its line and whether the player has done it.
     */
    private static Shown quest(final Tasks.Item i) {
        final TaskQuests.Seen seen = TaskQuests.seen(i.quest());
        final String words = !seen.name()
            .isEmpty() ? seen.name() : i.text();
        final String state = switch (seen.state()) {
            case DONE -> "Done in your quest book";
            case OPEN -> "Not done yet";
            case GONE -> "Not in this quest book";
            case NO_BOOK -> "No quest book in this game";
        };
        final String detail = seen.line() == null || seen.line()
            .isEmpty() ? state : seen.line() + SEP + state;
        final boolean gone = seen.state() == TaskQuests.State.GONE || seen.state() == TaskQuests.State.NO_BOOK;
        return new Shown(i, Tasks.QUEST, words, detail, seen.icon(), gone, null, null);
    }

    /**
     * A spot: its label, or its block when it has none; under it the block (when labelled), the dimension and how far
     * and which way it is: "120 64 -30 · Overworld · 40 m north-east".
     */
    private static Shown location(final Tasks.Item i) {
        final Tasks.Place p = i.place();
        final String at = p.x() + " " + p.y() + " " + p.z();
        final String words = p.label() != null ? p.label() : at;
        final StringBuilder detail = new StringBuilder();
        if (p.label() != null) detail.append(at);
        final String dim = p.dimName()
            .isEmpty() ? "Dimension " + p.dim() : p.dimName();
        if (detail.length() > 0) detail.append(SEP);
        detail.append(dim);
        final String where = where(p);
        if (!where.isEmpty()) detail.append(SEP)
            .append(where);
        return new Shown(i, Tasks.LOCATION, words, detail.toString(), null, false, null, null);
    }

    /**
     * How far and which way a spot is from the player ("40 m north-east", "where you stand"), "in another world" when
     * it is not in this one; "" when it is in another dimension of it (its name says which).
     */
    public static String where(final Tasks.Place p) {
        final Minecraft mc = Minecraft.getMinecraft();
        final EntityPlayer me = mc.thePlayer;
        if (me == null || mc.theWorld == null
            || !p.world()
                .equals(worldKey()))
            return "in another world";
        if (me.dimension != p.dim()) return "";
        final String far = TaskEdits.howFar(p.x() + 0.5 - me.posX, p.z() + 0.5 - me.posZ);
        return far.equals("here") ? "where you stand" : far;
    }

    /** Whether a spot is in the world being played (any dimension of it). */
    public static boolean inThisWorld(final Tasks.Place p) {
        return Minecraft.getMinecraft().theWorld != null && p.world()
            .equals(worldKey());
    }

    private static String taken(@Nullable final Tasks.Picture p) {
        final String ago = p == null ? "" : TaskLook.ago(p.takenAt());
        return ago.isEmpty() ? "" : "Taken " + ago;
    }

    // region Where the player stands

    /** The world placed machines and spots are kept under, worked out once per world. */
    private static String keyPath, key = "";

    static String worldKey() {
        final String path = NEIClientConfig.getWorldPath();
        if (path == null || path.isEmpty()) return "";
        if (!path.equals(keyPath)) {
            keyPath = path;
            key = GameCloud.worldKey();
        }
        return key;
    }

    /** The block the player stands on, in this world; null outside one. */
    @Nullable
    public static Tasks.Place here() {
        final Minecraft mc = Minecraft.getMinecraft();
        final EntityPlayer me = mc.thePlayer;
        if (me == null || mc.theWorld == null) return null;
        return new Tasks.Place(
            (int) Math.floor(me.posX),
            (int) Math.floor(me.boundingBox.minY + 0.01),
            (int) Math.floor(me.posZ),
            me.dimension,
            mc.theWorld.provider.getDimensionName(),
            worldKey(),
            null);
    }

    // endregion
}
