package com.gtnhplanner.client;

import javax.annotation.Nullable;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.event.ClickEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import com.gtnhplanner.data.flowchart.Plan;

/**
 * The client command a chat line about a plan shared with the player runs when its [Open] is clicked: opens the planner
 * on that plan (the account's plan by its key, {@code <ownerId>:<designId>}).
 */
public class OpenPlanCommand extends CommandBase {

    public static final String COMMAND_NAME = "gtnhplanner_open";

    /** The command that opens the plan with this key. */
    public static String command(final String key) {
        return "/" + COMMAND_NAME + " " + key;
    }

    /** "Owner shared Plan with you [Open]": the chat line, its [Open] clickable. */
    public static IChatComponent line(final String text, final String key) {
        final IChatComponent out = new ChatComponentText(text + " ");
        final IChatComponent open = new ChatComponentText("[Open]");
        open.getChatStyle()
            .setColor(EnumChatFormatting.AQUA)
            .setUnderlined(true)
            .setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command(key)));
        return out.appendSibling(open);
    }

    /** Opens the planner on the plan with this key; null once open, else what went wrong. */
    @Nullable
    public static String open(final String key) {
        final Plan plans = Plan.getInstance();
        for (int i = 0; i < plans.getGraphs()
            .size(); i++) {
            if (!plans.getGraphs()
                .get(i)
                .isPlan(key)) continue;
            plans.setActiveIndex(i);
            com.gtnhplanner.ui.Planner.open();
            return null;
        }
        return "That plan has not arrived yet: open Plans in a moment";
    }

    @Override
    public String getCommandName() {
        return COMMAND_NAME;
    }

    @Override
    public String getCommandUsage(final ICommandSender sender) {
        return "/" + COMMAND_NAME + " <key>";
    }

    @Override
    public void processCommand(final ICommandSender sender, final String[] args) {
        final String problem = args.length == 1 ? open(args[0]) : "That plan is gone";
        if (problem != null) sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + problem));
    }

    @Override
    public boolean canCommandSenderUseCommand(final ICommandSender sender) {
        return true;
    }
}
