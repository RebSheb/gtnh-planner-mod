package com.gtnhplanner.client;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;

import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Plan;
import com.gtnhplanner.data.flowchart.Serializer;

import codechicken.nei.NEIClientConfig;
import codechicken.nei.NEIClientUtils;
import codechicken.nei.bookmark.BookmarkPayload;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * A whole plan shared in the game's chat, with no website: it rides on NEI's chat item link, which NEI's server side
 * (on every GTNH server) passes from any player to everyone. The link is a paper named after the plan; players with
 * the planner see its line turned into one that opens the plan, others see NEI's own bookmark line.
 * <p>
 * NEI from GTNH 2.9's beta-3 sends a link as a payload, kept by every client under a short id, and puts only that id
 * and the item in the chat line: the plan goes in the payload beside the item, so the line stays small. Before beta-3
 * NEI links an item and writes it whole into the line twice (its hover and its click), so the plan goes in the item
 * and must stay well under the 32 KiB a chat packet holds: a bigger line would drop every player it is sent to.
 */
public final class ChatShare {

    public static final ChatShare INSTANCE = new ChatShare();

    /** The payload's keys (and the item's, before beta-3) for the plan and its name. */
    private static final String DATA_KEY = "gtnhplanner_data";
    private static final String NAME_KEY = "gtnhplanner_name";
    /** What the plan's key was before the rename: still read. */
    private static final String LEGACY_DATA_KEY = "plannh_data";

    /** The most a payload may take, as NBT: NEI's packet to the server holds under 32 KiB. */
    private static final int MAX_PAYLOAD_BYTES = 30_000;
    /** The most an encoded plan may take before beta-3, written twice into a chat line of at most 32 KiB. */
    private static final int MAX_ITEM_PLAN_CHARS = 14_000;
    private static final int MAX_NAME_CHARS = 64;

    private static final String ITEM_LINK = "nei.chat.item_link.text";
    private static final String BOOKMARK_COMMAND = "/nei_bookmark ";

    /** The plans in chat lines this session, by the key their line's click names; the newest 50. */
    private static final Map<String, Shared> SHARED = new LinkedHashMap<>(16, 0.75f, true) {

        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, Shared> eldest) {
            return size() > 50;
        }
    };
    private static int nextKey;

    /** A plan in a chat line: its name, its code, and the plan its first click made (opened again on the next). */
    private static final class Shared {

        final String name;
        final String data;
        @Nullable
        Graph opened;

        Shared(final String name, final String data) {
            this.name = name;
            this.data = data;
        }
    }

    private ChatShare() {}

    /**
     * Sends {@code graph} to the chat as a link everyone on the server can open.
     *
     * @return null once sent, or what stopped it, as a notice to show
     */
    @Nullable
    public static String send(final Graph graph) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) return "Join a world to share a plan in chat";
        if (!NEIClientConfig.hasSMPCounterPart()) return "This server can't pass plans through chat";
        final String name = nameOf(graph);
        final String data = Serializer.encode(graph);
        final ItemStack paper = paper(name);
        if (payloads()) {
            final NBTTagCompound payload = BookmarkPayload.of(paper, null)
                .toNBT();
            payload.setString(DATA_KEY, data);
            payload.setString(NAME_KEY, name);
            if (bytes(payload) > MAX_PAYLOAD_BYTES) return "This plan is too big for chat: copy its code instead";
            NEIClientUtils.sendChatItemLink(payload);
            return null;
        }
        if (data.length() > MAX_ITEM_PLAN_CHARS) return "This plan is too big for chat: copy its code instead";
        paper.getTagCompound()
            .setString(DATA_KEY, data);
        try {
            NEIClientUtils.class.getMethod("sendChatItemLink", ItemStack.class)
                .invoke(null, paper);
            return null;
        } catch (final ReflectiveOperationException e) {
            GtnhPlanner.LOG.warn("[compat] this NEI cannot link items in chat", e);
            return "This NEI can't pass plans through chat";
        }
    }

    /** Turns a chat line carrying a plan into one that opens it. */
    @SubscribeEvent
    public void onChat(final ClientChatReceivedEvent event) {
        if (!(event.message instanceof ChatComponentTranslation line) || !ITEM_LINK.equals(line.getKey())) return;
        final Object[] args = line.getFormatArgs();
        if (args.length < 2 || !(args[1] instanceof IChatComponent link)) return;
        final ClickEvent click = link.getChatStyle()
            .getChatClickEvent();
        if (click == null || click.getAction() != ClickEvent.Action.RUN_COMMAND
            || !click.getValue()
                .startsWith(BOOKMARK_COMMAND))
            return;
        final String rest = click.getValue()
            .substring(BOOKMARK_COMMAND.length())
            .trim();
        final Shared shared;
        final String key;
        if (rest.startsWith("{")) {
            shared = fromItem(rest);
            key = "c" + ++nextKey;
        } else {
            shared = fromPayload(rest);
            key = rest;
        }
        if (shared == null) return;
        SHARED.put(key, shared);
        final String sender = args[0] instanceof IChatComponent c ? c.getFormattedText() : String.valueOf(args[0]);
        event.message = line(sender, shared.name, key);
    }

    /** The line players with the planner see: who shared which plan, the name a link that opens it. */
    private static IChatComponent line(final String sender, final String name, final String key) {
        final IChatComponent hover = new ChatComponentText("Click to open it in your planner");
        final IChatComponent link = new ChatComponentText("[" + name + "]");
        link.getChatStyle()
            .setColor(EnumChatFormatting.AQUA)
            .setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover))
            .setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, ImportCommand.command(key)));
        final IChatComponent out = new ChatComponentText(sender + " shared the plan ");
        out.getChatStyle()
            .setColor(EnumChatFormatting.GRAY);
        return out.appendSibling(link);
    }

    /**
     * Adds the plan a chat line's click names as a new plan and opens the planner on it.
     *
     * @return null once open, or what went wrong, for the chat
     */
    @Nullable
    public static String open(final String key) {
        final Shared shared = SHARED.get(key);
        if (shared == null) return "That plan is gone: ask for it again";
        final Plan plans = Plan.getInstance();
        final int already = indexOf(plans, shared.opened);
        if (already >= 0) {
            plans.setActiveIndex(already);
            com.gtnhplanner.ui.Planner.open();
            return null;
        }
        final Graph graph;
        try {
            graph = Serializer.decode(shared.data);
        } catch (final RuntimeException e) {
            GtnhPlanner.LOG.warn("Could not read a plan shared in chat", e);
            return "That plan could not be read";
        }
        if (graph.getName() == null || graph.getName()
            .isBlank()) graph.setName(shared.name);
        com.gtnhplanner.importer.game.FactoryFlowImport.addAsSlot(graph);
        shared.opened = graph;
        com.gtnhplanner.ui.Planner.open();
        return null;
    }

    private static int indexOf(final Plan plans, @Nullable final Graph graph) {
        if (graph == null) return -1;
        for (int i = 0; i < plans.getGraphs()
            .size(); i++)
            if (plans.getGraphs()
                .get(i) == graph) return i;
        return -1;
    }

    /** The plan in a payload NEI kept under {@code id}, or null. */
    @Nullable
    private static Shared fromPayload(final String id) {
        final NBTTagCompound payload = cachedPayload(id);
        if (payload == null || !payload.hasKey(DATA_KEY)) return null;
        return new Shared(nameOr(payload.getString(NAME_KEY)), payload.getString(DATA_KEY));
    }

    /** The plan in an item written into the line (NEI before beta-3), or null. */
    @Nullable
    private static Shared fromItem(final String nbt) {
        try {
            final NBTBase parsed = JsonToNBT.func_150315_a(nbt);
            if (!(parsed instanceof NBTTagCompound item)) return null;
            final NBTTagCompound tag = item.getCompoundTag("tag");
            final String key = tag.hasKey(DATA_KEY) ? DATA_KEY : LEGACY_DATA_KEY;
            if (!tag.hasKey(key)) return null;
            return new Shared(
                nameOr(
                    tag.getCompoundTag("display")
                        .getString("Name")),
                tag.getString(key));
        } catch (final Exception e) {
            return null;
        }
    }

    private static String nameOr(final String name) {
        final String plain = EnumChatFormatting.getTextWithoutFormattingCodes(name);
        return plain == null || plain.isBlank() ? "Shared plan" : plain;
    }

    /**
     * The plan's name for the link: no colour codes, and no quotes or backslashes, which NEI before beta-3 cannot read.
     */
    private static String nameOf(final Graph graph) {
        final String name = graph.getName() == null ? ""
            : EnumChatFormatting.getTextWithoutFormattingCodes(graph.getName())
                .replace('"', '\'')
                .replace('\\', '/')
                .trim();
        if (name.isEmpty()) return "Shared plan";
        return name.length() > MAX_NAME_CHARS ? name.substring(0, MAX_NAME_CHARS) : name;
    }

    /** The link's item: a paper named after the plan, which is all players without the planner see of it. */
    private static ItemStack paper(final String name) {
        final ItemStack stack = new ItemStack(Items.paper);
        final NBTTagCompound display = new NBTTagCompound();
        display.setString("Name", name);
        final NBTTagList lore = new NBTTagList();
        lore.appendTag(new NBTTagString(EnumChatFormatting.GRAY + "A plan from GTNH Planner"));
        display.setTag("Lore", lore);
        stack.setTagInfo("display", display);
        return stack;
    }

    private static int bytes(final NBTTagCompound tag) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (final DataOutputStream data = new DataOutputStream(out)) {
            CompressedStreamTools.write(tag, data);
        } catch (final IOException e) {
            return Integer.MAX_VALUE;
        }
        return out.size();
    }

    /** True when this NEI links payloads (GTNH 2.9's beta-3 and on). */
    private static boolean payloads() {
        if (payloads == null) {
            try {
                NEIClientUtils.class.getMethod("sendChatItemLink", NBTTagCompound.class);
                Class.forName("codechicken.nei.bookmark.BookmarkPayload");
                payloads = true;
            } catch (final ReflectiveOperationException | LinkageError e) {
                payloads = false;
            }
        }
        return payloads;
    }

    private static Boolean payloads;

    private static Method getCachedPayload;

    /** The payload NEI kept for a chat link's id (NEI keeps them for its own click), or null. */
    @Nullable
    private static NBTTagCompound cachedPayload(final String id) {
        try {
            if (getCachedPayload == null) {
                getCachedPayload = Class.forName("codechicken.nei.commands.CommandBookmarkAdd")
                    .getDeclaredMethod("getCachedPayload", String.class);
                getCachedPayload.setAccessible(true);
            }
            return (NBTTagCompound) getCachedPayload.invoke(null, id);
        } catch (final ReflectiveOperationException | LinkageError | ClassCastException e) {
            return null;
        }
    }
}
