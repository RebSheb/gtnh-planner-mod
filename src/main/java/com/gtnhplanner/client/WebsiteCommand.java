package com.gtnhplanner.client;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

/**
 * The client command behind the Open and Not now of the chat line about a plan the website sent
 * ({@link WebsiteInbox}): {@code open <key>} or {@code drop <key>}, the key naming that plan.
 */
public class WebsiteCommand extends CommandBase {

    public static final String COMMAND_NAME = "gtnhplanner_website";

    @Override
    public String getCommandName() {
        return COMMAND_NAME;
    }

    @Override
    public String getCommandUsage(final ICommandSender sender) {
        return "/" + COMMAND_NAME + " open|drop <key>";
    }

    @Override
    public void processCommand(final ICommandSender sender, final String[] args) {
        if (args.length != 2) return;
        if ("drop".equals(args[0])) {
            WebsiteInbox.drop(args[1]);
            return;
        }
        if (!"open".equals(args[0])) return;
        final String problem = WebsiteInbox.open(args[1]);
        if (problem != null) sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + problem));
    }

    @Override
    public boolean canCommandSenderUseCommand(final ICommandSender sender) {
        return true;
    }
}
