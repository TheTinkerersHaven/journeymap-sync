package com.gregorio.journeymapsync.net.handler;

import com.gregorio.journeymapsync.client.ClientSyncController;
import com.gregorio.journeymapsync.net.msg.HelloMsg;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

/**
 * Client side of a relayed Hello: peer discovery trigger for the catchup handshake.
 */
public class HelloClientHandler implements IMessageHandler<HelloMsg, IMessage>
{
    @Override
    public IMessage onMessage(HelloMsg message, MessageContext ctx)
    {
        if (message.valid && !message.senderName.isEmpty())
        {
            ClientSyncController.get().onHelloReceived(message.senderName);
        }
        return null;
    }
}
