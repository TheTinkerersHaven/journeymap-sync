package com.gregorio.journeymapsync.net.handler;

import com.gregorio.journeymapsync.net.SyncNetwork;
import com.gregorio.journeymapsync.net.msg.HelloMsg;
import com.gregorio.journeymapsync.net.msg.PongMsg;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

/**
 * Server side of Hello: answer sender-only with Pong (relay availability probe), then
 * relay the Hello to every other client so peers can react (section 6 catchup trigger).
 */
public class HelloServerHandler implements IMessageHandler<HelloMsg, IMessage>
{
    @Override
    public IMessage onMessage(HelloMsg message, MessageContext ctx)
    {
        if (!message.valid)
        {
            return null;
        }
        SyncNetwork.INSTANCE.sendTo(new PongMsg(), ctx.getServerHandler().playerEntity);
        SyncNetwork.relayToAllExcept(ctx, message);
        return null;
    }
}
