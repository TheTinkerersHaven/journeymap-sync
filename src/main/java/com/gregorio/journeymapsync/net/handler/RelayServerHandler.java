package com.gregorio.journeymapsync.net.handler;

import com.gregorio.journeymapsync.net.SyncNetwork;
import com.gregorio.journeymapsync.net.msg.RelayMsg;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

/**
 * Server relay for catchup traffic: validate size, then broadcast to all except sender.
 */
public class RelayServerHandler implements IMessageHandler<RelayMsg, IMessage>
{
    @Override
    public IMessage onMessage(RelayMsg message, MessageContext ctx)
    {
        if (!message.valid || !message.withinRelayCap())
        {
            return null;
        }
        SyncNetwork.relayToAllExcept(ctx, message);
        return null;
    }
}
