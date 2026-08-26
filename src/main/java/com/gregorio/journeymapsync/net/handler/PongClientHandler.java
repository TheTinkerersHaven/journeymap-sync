package com.gregorio.journeymapsync.net.handler;

import com.gregorio.journeymapsync.client.ClientSyncController;
import com.gregorio.journeymapsync.net.msg.PongMsg;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

/**
 * Client side of Pong: marks the relay available.
 */
public class PongClientHandler implements IMessageHandler<PongMsg, IMessage>
{
    @Override
    public IMessage onMessage(PongMsg message, MessageContext ctx)
    {
        if (message.valid)
        {
            ClientSyncController.get().onPong();
        }
        return null;
    }
}
