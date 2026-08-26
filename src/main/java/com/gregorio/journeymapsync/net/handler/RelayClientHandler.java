package com.gregorio.journeymapsync.net.handler;

import com.gregorio.journeymapsync.client.ClientSyncController;
import com.gregorio.journeymapsync.net.inner.DigestPayload;
import com.gregorio.journeymapsync.net.inner.DigestRequestPayload;
import com.gregorio.journeymapsync.net.inner.TilesRequestPayload;
import com.gregorio.journeymapsync.net.msg.RelayMsg;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

/**
 * Client receiver for relayed catchup traffic (DigestRequest / Digest / TilesRequest).
 */
public class RelayClientHandler implements IMessageHandler<RelayMsg, IMessage>
{
    @Override
    public IMessage onMessage(RelayMsg message, MessageContext ctx)
    {
        if (!message.valid)
        {
            return null;
        }
        switch (message.innerType)
        {
            case RelayMsg.T_DIGEST_REQUEST:
                ClientSyncController.get().onDigestRequest(((DigestRequestPayload) message.payload).reqId);
                break;
            case RelayMsg.T_DIGEST:
                ClientSyncController.get().onDigestReceived(message.senderName, (DigestPayload) message.payload);
                break;
            case RelayMsg.T_TILES_REQUEST:
                ClientSyncController.get().onTilesRequest(message.senderName, (TilesRequestPayload) message.payload);
                break;
        }
        return null;
    }
}
