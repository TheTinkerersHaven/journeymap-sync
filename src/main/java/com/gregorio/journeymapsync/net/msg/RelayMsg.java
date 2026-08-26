package com.gregorio.journeymapsync.net.msg;

import com.gregorio.journeymapsync.net.Wire;
import com.gregorio.journeymapsync.net.inner.DigestPayload;
import com.gregorio.journeymapsync.net.inner.DigestRequestPayload;
import com.gregorio.journeymapsync.net.inner.TilesRequestPayload;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/**
 * Discriminator 2, C→S. Generic broadcast carrier for catchup traffic. The server relays
 * every received RelayMsg verbatim to all other connected clients.
 *
 * Inner wire: u8 innerType + payload bytes:
 *   1 = DigestRequest{u32 reqId}
 *   2 = Digest{...}
 *   3 = TilesRequest{...}
 */
public class RelayMsg implements IMessage
{
    public static final byte T_DIGEST_REQUEST = 1;
    public static final byte T_DIGEST = 2;
    public static final byte T_TILES_REQUEST = 3;

    /** conservative whole-packet bound used by the server before relaying */
    public static final int MAX_WIRE_BYTES = 32768;

    public String senderName = "";
    public byte innerType;
    /** decoded payload: DigestRequestPayload | DigestPayload | TilesRequestPayload */
    public transient Object payload;
    public transient boolean valid = true;

    public RelayMsg()
    {
    }

    public RelayMsg(String senderName, byte innerType, Object payload)
    {
        this.senderName = senderName;
        this.innerType = innerType;
        this.payload = payload;
    }

    @Override
    public void toBytes(ByteBuf buf)
    {
        Wire.writeHeader(buf, senderName);
        buf.writeByte(innerType);
        if (payload instanceof DigestRequestPayload)
        {
            ((DigestRequestPayload) payload).write(buf);
        }
        else if (payload instanceof DigestPayload)
        {
            ((DigestPayload) payload).write(buf);
        }
        else if (payload instanceof TilesRequestPayload)
        {
            ((TilesRequestPayload) payload).write(buf);
        }
    }

    @Override
    public void fromBytes(ByteBuf buf)
    {
        String name = Wire.readHeader(buf);
        if (name == null || buf.readableBytes() < 1)
        {
            valid = false;
            return;
        }
        senderName = name;
        innerType = buf.readByte();
        switch (innerType)
        {
            case T_DIGEST_REQUEST:
                payload = DigestRequestPayload.read(buf);
                break;
            case T_DIGEST:
                payload = DigestPayload.read(buf);
                break;
            case T_TILES_REQUEST:
                payload = TilesRequestPayload.read(buf);
                break;
            default:
                payload = null;
        }
        valid = payload != null;
    }

    /**
     * Server-side size gate: validate total packet stays under the relay cap.
     */
    public boolean withinRelayCap()
    {
        int innerLen;
        if (payload instanceof DigestPayload)
        {
            innerLen = 5 + ((DigestPayload) payload).entries.size() * DigestPayload.ENTRY_BYTES;
        }
        else if (payload instanceof TilesRequestPayload)
        {
            innerLen = 6 + ((TilesRequestPayload) payload).entries.size() * TilesRequestPayload.ENTRY_BYTES;
        }
        else if (payload instanceof DigestRequestPayload)
        {
            innerLen = 4;
        }
        else
        {
            return false;
        }
        return HEADER_BYTES_ESTIMATE + 1 + innerLen <= MAX_WIRE_BYTES;
    }

    private static final int HEADER_BYTES_ESTIMATE = Wire.HEADER_OVERHEAD;
}
