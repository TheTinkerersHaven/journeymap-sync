package com.gregorio.journeymapsync.net;

import com.gregorio.journeymapsync.net.handler.HelloClientHandler;
import com.gregorio.journeymapsync.net.handler.HelloServerHandler;
import com.gregorio.journeymapsync.net.handler.PongClientHandler;
import com.gregorio.journeymapsync.net.handler.RelayClientHandler;
import com.gregorio.journeymapsync.net.handler.RelayServerHandler;
import com.gregorio.journeymapsync.net.handler.TileServerHandler;
import com.gregorio.journeymapsync.net.handler.TileClientHandler;
import com.gregorio.journeymapsync.net.msg.HelloMsg;
import com.gregorio.journeymapsync.net.msg.PongMsg;
import com.gregorio.journeymapsync.net.msg.RelayMsg;
import com.gregorio.journeymapsync.net.msg.TileMsg;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;

/**
 * Single FML channel "jmsync" acting as a generic server relay (section 2).
 * Discriminators: 0 Hello(C→S), 1 Pong(S→C), 2 Relay(C→S), 3 Tile(S→C + C→S).
 *
 * The server treats every incoming C→S message as broadcast-to-all-except-sender; FML has
 * no built-in call for this, so each server handler does it explicitly via
 * {@link #relayToAllExcept(MessageContext, IMessage)}.
 */
public final class SyncNetwork
{
    public static final String CHANNEL = "jmsync";

    public static SimpleNetworkWrapper INSTANCE;

    private SyncNetwork()
    {
    }

    public static void init()
    {
        INSTANCE = NetworkRegistry.INSTANCE.newSimpleChannel(CHANNEL);
        INSTANCE.registerMessage(HelloServerHandler.class, HelloMsg.class, 0, Side.SERVER);
        INSTANCE.registerMessage(HelloClientHandler.class, HelloMsg.class, 0, Side.CLIENT);
        INSTANCE.registerMessage(PongClientHandler.class, PongMsg.class, 1, Side.CLIENT);
        INSTANCE.registerMessage(RelayServerHandler.class, RelayMsg.class, 2, Side.SERVER);
        INSTANCE.registerMessage(RelayClientHandler.class, RelayMsg.class, 2, Side.CLIENT);
        INSTANCE.registerMessage(TileServerHandler.class, TileMsg.class, 3, Side.SERVER);
        INSTANCE.registerMessage(TileClientHandler.class, TileMsg.class, 3, Side.CLIENT);
    }

    /**
     * Broadcast an already-validated message to every connected player except the sender.
     */
    public static void relayToAllExcept(MessageContext ctx, IMessage msg)
    {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null)
        {
            return;
        }
        NetHandlerPlayServer senderHandler = ctx.getServerHandler();
        // Copy to avoid concurrent modification while netty writes are scheduled.
        for (Object o : new ArrayList<Object>(server.getConfigurationManager().playerEntityList))
        {
            EntityPlayerMP player = (EntityPlayerMP) o;
            if (player.playerNetServerHandler == senderHandler)
            {
                continue;
            }
            INSTANCE.sendTo(msg, player);
        }
    }
}
