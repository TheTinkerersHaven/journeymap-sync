package com.gregorio.journeymapsync.capture;

/**
 * Wraps a single 16x16 chunk PNG sub-image extracted from a JourneyMap region tile.
 *
 * Wire layout (after DEFLATE, see PngTileCodec):
 *   u8  mapTypeLen         // length of mapTypeDir string (e.g. 3 for "day", 1 for "4")
 *   bytes mapTypeLen       // UTF-8 map type directory name: "day", "night", "topo", "0".."15"
 *   u16 pngLen            // length of PNG data (<= 65535)
 *   bytes pngLen          // raw PNG image data for a single 16x16 chunk
 *
 * The PNG is a 16x16 pixel sub-image (one chunk within a 512x512 region tile,
 * which is 32x32 chunks of 16x16 pixels each).
 */
public final class PngTileBody
{
    /** JM directory name for this map type (e.g. "day", "night", "topo", "4"). */
    public final String mapTypeDir;
    /** Raw PNG bytes for a 16x16 chunk sub-image. */
    public final byte[] pngChunk;

    public PngTileBody(String mapTypeDir, byte[] pngChunk)
    {
        this.mapTypeDir = mapTypeDir;
        this.pngChunk = pngChunk;
    }

    /** @return serialized (uncompressed) for PngTileCodec.encode */
    public byte[] serialize()
    {
        byte[] mtBytes = mapTypeDir.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int total = 1 + mtBytes.length + 2 + pngChunk.length;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(total);
        java.io.DataOutputStream d = new java.io.DataOutputStream(out);
        try
        {
            d.writeByte(mtBytes.length);
            d.write(mtBytes);
            d.writeShort(pngChunk.length);
            d.write(pngChunk);
        }
        catch (java.io.IOException e)
        {
            throw new IllegalStateException("unreachable", e);
        }
        return out.toByteArray();
    }

    /** @return deserialized (uncompressed), or null if corrupt/truncated */
    public static PngTileBody deserialize(byte[] raw)
    {
        if (raw == null || raw.length < 4)
        {
            return null;
        }
        try
        {
            java.io.DataInputStream in = new java.io.DataInputStream(
                    new java.io.ByteArrayInputStream(raw));
            int mtLen = in.readByte() & 0xFF;
            if (mtLen < 1 || mtLen > 16 || raw.length < 1 + mtLen + 2)
            {
                return null;
            }
            byte[] mtBytes = new byte[mtLen];
            in.readFully(mtBytes);
            String mt = new String(mtBytes, java.nio.charset.StandardCharsets.UTF_8);
            int pngLen = in.readUnsignedShort();
            if (pngLen < 8 || pngLen > 0xFFFF) // PNG header is 8 bytes min
            {
                return null;
            }
            byte[] png = new byte[pngLen];
            in.readFully(png);
            return new PngTileBody(mt, png);
        }
        catch (java.io.IOException e)
        {
            return null;
        }
    }
}
