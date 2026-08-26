package com.gregorio.journeymapsync.net.msg;

import com.gregorio.journeymapsync.net.Wire;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/**
 * Discriminator 3, S→C and C→S (C→S copies are broadcast by the server to everyone else).
 * Wire: header, i32 dimId, i32 cx, i32 cz, i64 ts, u16 compressedLen, bytes[compressedLen]
 * where the payload is DEFLATE(pngTileBody) — see capture.PngTileCodec for the body layout.
 */
public class TileMsg implements IMessage
{
    public static final int MAX_COMPRESSED_LEN = 30000;
    public static final int FIXED_OVERHEAD = Wire.HEADER_OVERHEAD + 4 + 4 + 4 + 8 + 2;

    public String senderName = "";
    public int dimId;
    public int cx;
    public int cz;
    public long ts;
    public byte[] compressed = new byte[0];
    public transient boolean valid = true;

    public TileMsg()
    {
    }

    public TileMsg(String senderName, int dimId, int cx, int cz, long ts, byte[] compressed)
    {
        this.senderName = senderName;
        this.dimId = dimId;
        this.cx = cx;
        this.cz = cz;
        this.ts = ts;
        this.compressed = compressed;
        this.valid = compressed.length <= MAX_COMPRESSED_LEN;
    }

    @Override
    public void toBytes(ByteBuf buf)
    {
        Wire.writeHeader(buf, senderName);
        buf.writeInt(dimId);
        buf.writeInt(cx);
        buf.writeInt(cz);
        buf.writeLong(ts);
        byte[] data = compressed.length > MAX_COMPRESSED_LEN
                ? java.util.Arrays.copyOf(compressed, MAX_COMPRESSED_LEN)
                : compressed;
        buf.writeShort(data.length);
        buf.writeBytes(data);
    }

    @Override
    public void fromBytes(ByteBuf buf)
    {
        String name = Wire.readHeader(buf);
        if (name == null || buf.readableBytes() < 22)
        {
            valid = false;
            return;
        }
        senderName = name;
        dimId = buf.readInt();
        cx = buf.readInt();
        cz = buf.readInt();
        ts = buf.readLong();
        int len = buf.readUnsignedShort();
        if (len > MAX_COMPRESSED_LEN || buf.readableBytes() < len)
        {
            // Hard cap: drop silently.
            valid = false;
            return;
        }
        compressed = new byte[len];
        buf.readBytes(compressed);
    }
}
