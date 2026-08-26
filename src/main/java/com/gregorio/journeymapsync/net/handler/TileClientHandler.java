package com.gregorio.journeymapsync.net.handler;

import com.gregorio.journeymapsync.client.ClientSyncController;
import com.gregorio.journeymapsync.net.msg.TileMsg;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

/**
 * Client receive path for tiles (both direct S→C and relayed C→S copies — indistinguishable
 * and both fine: newest-wins makes duplicates idempotent). Hands off to the client thread.
 */
public class TileClientHandler implements IMessageHandler<TileMsg, IMessage>
{
    @Override
    public IMessage onMessage(TileMsg message, MessageContext ctx)
    {
        if (message.valid)
        {
            ClientSyncController.get().onTileReceived(message);
        }
        return null;
    }
}
