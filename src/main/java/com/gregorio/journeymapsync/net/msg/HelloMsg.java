package com.gregorio.journeymapsync.net.msg;

import com.gregorio.journeymapsync.net.Wire;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/**
 * Discriminator 0, C→S. Sent on join and every 30s. Server answers sender-only with a
 * {@link PongMsg} and relays the Hello to every other client (peer discovery).
 */
public class HelloMsg implements IMessage
{
    public String senderName = "";

    public HelloMsg()
    {
    }

    public HelloMsg(String senderName)
    {
        this.senderName = senderName;
    }

    @Override
    public void toBytes(ByteBuf buf)
    {
        Wire.writeHeader(buf, senderName);
    }

    @Override
    public void fromBytes(ByteBuf buf)
    {
        String name = Wire.readHeader(buf);
        senderName = name == null ? "" : name;
        valid = name != null;
    }

    /** Set false when the header was malformed; handlers drop silently. */
    public transient boolean valid = true;
}
