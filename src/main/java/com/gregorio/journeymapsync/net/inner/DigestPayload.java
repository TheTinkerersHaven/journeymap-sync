package com.gregorio.journeymapsync.net.inner;

import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Digest{u32 reqId, u8 entryCount, entries[]}, entry = {i32 dimId, i16 regionX, i16 regionZ,
 * long[1024] chunkTs} (region = 32x32 chunks, index (cz&amp;31)*32+(cx&amp;31), 0 = absent).
 * One entry is ~8.2KB so at most 3 entries ride in one message (~24.6KB, under the 32768
 * relay cap); larger region sets are split across multiple Digests sharing reqId.
 */
public final class DigestPayload
{
    public static final int MAX_ENTRIES_PER_MSG = 3;
    public static final int REGION_CHUNKS = 32 * 32;
    /** wire bytes of one entry: 4 (dim) + 2 (rx) + 2 (rz) + 8192 (timestamps) */
    public static final int ENTRY_BYTES = 4 + 2 + 2 + 8 * REGION_CHUNKS;

    public final int reqId;
    public final List<DigestEntry> entries;

    public DigestPayload(int reqId, List<DigestEntry> entries)
    {
        this.reqId = reqId;
        this.entries = entries;
    }

    public void write(ByteBuf buf)
    {
        buf.writeInt(reqId);
        int count = Math.min(entries.size(), MAX_ENTRIES_PER_MSG);
        buf.writeByte(count);
        for (int i = 0; i < count; i++)
        {
            DigestEntry e = entries.get(i);
            buf.writeInt(e.dimId);
            buf.writeShort(e.regionX);
            buf.writeShort(e.regionZ);
            for (int c = 0; c < REGION_CHUNKS; c++)
            {
                buf.writeLong(e.chunkTs[c]);
            }
        }
    }

    /** @return null when truncated/malformed or over the entry cap. */
    public static DigestPayload read(ByteBuf buf)
    {
        if (buf.readableBytes() < 5)
        {
            return null;
        }
        int reqId = buf.readInt();
        int count = buf.readUnsignedByte();
        if (count > MAX_ENTRIES_PER_MSG || buf.readableBytes() < count * ENTRY_BYTES)
        {
            return null;
        }
        List<DigestEntry> entries = new ArrayList<DigestEntry>(count);
        for (int i = 0; i < count; i++)
        {
            int dimId = buf.readInt();
            int rx = buf.readShort();
            int rz = buf.readShort();
            long[] chunkTs = new long[REGION_CHUNKS];
            for (int c = 0; c < REGION_CHUNKS; c++)
            {
                chunkTs[c] = buf.readLong();
            }
            entries.add(new DigestEntry(dimId, rx, rz, chunkTs));
        }
        return new DigestPayload(reqId, entries);
    }

    public static final class DigestEntry
    {
        public final int dimId;
        public final int regionX;
        public final int regionZ;
        public final long[] chunkTs;

        public DigestEntry(int dimId, int regionX, int regionZ, long[] chunkTs)
        {
            this.dimId = dimId;
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.chunkTs = chunkTs;
        }
    }
}
