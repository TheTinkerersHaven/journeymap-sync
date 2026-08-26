package com.gregorio.journeymapsync.net.msg;

import com.gregorio.journeymapsync.net.Wire;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/**
 * Discriminator 1, S→C. Direct answer to a client's Hello; marks relayAvailable=true.
 */
public class PongMsg implements IMessage
{
    @Override
    public void toBytes(ByteBuf buf)
    {
        Wire.writeHeader(buf, "");
    }

    @Override
    public void fromBytes(ByteBuf buf)
    {
        valid = Wire.readHeader(buf) != null;
    }

    public transient boolean valid = true;
}
