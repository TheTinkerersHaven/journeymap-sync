package com.gregorio.journeymapsync.net.inner;

import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * TilesRequest{u32 reqId, entries[]}, entry = {i32 dimId, i32 cx, i32 cz, i64 myTs};
 * cap 256 entries/request. Holders reply with TileMsgs under the shared outbound token
 * bucket, lowest ts first.
 */
public final class TilesRequestPayload
{
    public static final int MAX_ENTRIES_PER_MSG = 256;
    /** wire bytes of one entry: 4 + 4 + 4 + 8 */
    public static final int ENTRY_BYTES = 20;
    /** full message bound incl. header/type: keeps us under the 32768 relay cap */
    public static final int WIRE_MAX = 4 + 2 + MAX_ENTRIES_PER_MSG * ENTRY_BYTES;

    public final int reqId;
    public final List<TileRef> entries;

    public TilesRequestPayload(int reqId, List<TileRef> entries)
    {
        this.reqId = reqId;
        this.entries = entries;
    }

    public void write(ByteBuf buf)
    {
        buf.writeInt(reqId);
        int count = Math.min(entries.size(), MAX_ENTRIES_PER_MSG);
        buf.writeShort(count);
        for (int i = 0; i < count; i++)
        {
            TileRef e = entries.get(i);
            buf.writeInt(e.dimId);
            buf.writeInt(e.cx);
            buf.writeInt(e.cz);
            buf.writeLong(e.myTs);
        }
    }

    /** @return null when truncated/malformed or over the entry cap. */
    public static TilesRequestPayload read(ByteBuf buf)
    {
        if (buf.readableBytes() < 6)
        {
            return null;
        }
        int reqId = buf.readInt();
        int count = buf.readUnsignedShort();
        if (count > MAX_ENTRIES_PER_MSG || buf.readableBytes() < count * ENTRY_BYTES)
        {
            return null;
        }
        List<TileRef> entries = new ArrayList<TileRef>(count);
        for (int i = 0; i < count; i++)
        {
            int dimId = buf.readInt();
            int cx = buf.readInt();
            int cz = buf.readInt();
            long ts = buf.readLong();
            entries.add(new TileRef(dimId, cx, cz, ts));
        }
        return new TilesRequestPayload(reqId, entries);
    }

    public static final class TileRef
    {
        public final int dimId;
        public final int cx;
        public final int cz;
        public final long myTs;

        public TileRef(int dimId, int cx, int cz, long myTs)
        {
            this.dimId = dimId;
            this.cx = cx;
            this.cz = cz;
            this.myTs = myTs;
        }
    }
}
