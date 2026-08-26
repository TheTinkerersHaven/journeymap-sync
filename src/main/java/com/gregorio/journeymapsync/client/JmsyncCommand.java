package com.gregorio.journeymapsync.client;

import com.gregorio.journeymapsync.JourneyMapSync;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;

import java.util.Arrays;
import java.util.List;

/**
 * /jmsync status | inject <chunkX> <chunkZ> | sync (section 7). Client-side command.
 */
public class JmsyncCommand extends CommandBase
{
    @Override
    public String getCommandName()
    {
        return "jmsync";
    }

    @Override
    public String getCommandUsage(ICommandSender sender)
    {
        return "/jmsync status | /jmsync inject <chunkX> <chunkZ> | /jmsync sync";
    }

    @Override
    public int getRequiredPermissionLevel()
    {
        return 0;
    }

    @Override
    public List<String> getCommandAliases()
    {
        return Arrays.asList("jms");
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args)
    {
        ClientSyncController controller = ClientSyncController.get();
        if (args.length == 0 || "status".equalsIgnoreCase(args[0]))
        {
            for (String line : controller.statusLines())
            {
                sender.addChatMessage(new ChatComponentText(line));
            }
            return;
        }
        if ("inject".equalsIgnoreCase(args[0]) && args.length >= 3)
        {
            try
            {
                int cx = parseInt(sender, args[1]);
                int cz = parseInt(sender, args[2]);
                if (!controller.debugInject(cx, cz))
                {
                    sender.addChatMessage(new ChatComponentText(
                            "journeymapsync: injection unavailable (no JourneyMap or not in a world)"));
                }
            }
            catch (NumberFormatException e)
            {
                sender.addChatMessage(new ChatComponentText("journeymapsync: bad coordinates"));
            }
            return;
        }
        if ("sync".equalsIgnoreCase(args[0]))
        {
            if (!controller.triggerGlobalSync())
            {
                sender.addChatMessage(new ChatComponentText(
                        "journeymapsync: global sync unavailable (not in a world or no relay)"));
            }
            return;
        }
        sender.addChatMessage(new ChatComponentText("journeymapsync: usage " + getCommandUsage(sender)));
        JourneyMapSync.LOGGER.debug("/jmsync invoked with " + Arrays.toString(args));
    }
}
