package com.gtnhplanner.client;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

/** The client command a plan's chat line runs when clicked ({@link ChatShare}): opens that plan. */
public class ImportCommand extends CommandBase {

    public static final String COMMAND_NAME = "gtnhplanner_import";

    /** The command that opens the plan a chat line keeps under {@code key}. */
    public static String command(final String key) {
        return "/" + COMMAND_NAME + " " + key;
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
        final String problem = args.length == 1 ? ChatShare.open(args[0]) : "That plan is gone: ask for it again";
        if (problem != null) sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + problem));
    }

    @Override
    public boolean canCommandSenderUseCommand(final ICommandSender sender) {
        return true;
    }
}
