package com.gregorio.journeymapsync.net.inner;

import io.netty.buffer.ByteBuf;

/**
 * DigestRequest{u32 reqId} — broadcast; recipients answer after a random 1-5s delay with
 * {@link DigestPayload} fragments paced at 250ms.
 */
public final class DigestRequestPayload
{
    public final int reqId;

    public DigestRequestPayload(int reqId)
    {
        this.reqId = reqId;
    }

    public void write(ByteBuf buf)
    {
        buf.writeInt(reqId);
    }

    /** @return null when truncated/malformed. */
    public static DigestRequestPayload read(ByteBuf buf)
    {
        if (buf.readableBytes() < 4)
        {
            return null;
        }
        return new DigestRequestPayload(buf.readInt());
    }
}
