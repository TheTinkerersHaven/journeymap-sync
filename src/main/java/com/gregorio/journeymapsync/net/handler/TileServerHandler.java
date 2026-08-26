package com.gregorio.journeymapsync.net.handler;

import com.gregorio.journeymapsync.net.SyncNetwork;
import com.gregorio.journeymapsync.net.msg.TileMsg;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

/**
 * Server relay for tiles: broadcast to all except sender (generic relay, no routing).
 */
public class TileServerHandler implements IMessageHandler<TileMsg, IMessage>
{
    @Override
    public IMessage onMessage(TileMsg message, MessageContext ctx)
    {
        // Decoder already enforces the 30000-byte compressed cap; belt-and-braces check.
        if (!message.valid || message.compressed.length > TileMsg.MAX_COMPRESSED_LEN)
        {
            return null;
        }
        SyncNetwork.relayToAllExcept(ctx, message);
        return null;
    }
}
