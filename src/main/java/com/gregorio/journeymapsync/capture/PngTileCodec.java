package com.gregorio.journeymapsync.capture;

import java.io.ByteArrayOutputStream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Encodes/decodes {@link PngTileBody} for transport inside {@link net.gregorio.journeymapsync.net.msg.TileMsg}.
 *
 * The whole body is DEFLATEd (matches the 30000-byte compressed cap in TileMsg).
 */
public final class PngTileCodec
{
    private PngTileCodec()
    {
    }

    public static byte[] encode(PngTileBody body)
    {
        byte[] raw = body.serialize();
        return deflate(raw);
    }

    /** @return null when the compressed bytes are corrupt or truncated. */
    public static PngTileBody decode(byte[] compressed)
    {
        byte[] raw = inflate(compressed);
        if (raw == null)
        {
            return null;
        }
        return PngTileBody.deserialize(raw);
    }

    /** CRC32 of the encoded (DEFLATEd) form — used to skip re-sending unchanged tiles. */
    public static long crcOfEncoded(byte[] encoded)
    {
        if (encoded == null)
        {
            return 0L;
        }
        CRC32 crc = new CRC32();
        crc.update(encoded);
        return crc.getValue();
    }

    private static byte[] deflate(byte[] raw)
    {
        Deflater d = new Deflater(Deflater.BEST_SPEED);
        try
        {
            d.setInput(raw);
            d.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 2);
            byte[] buf = new byte[4096];
            while (!d.finished())
            {
                int n = d.deflate(buf);
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
        finally
        {
            d.end();
        }
    }

    private static byte[] inflate(byte[] compressed)
    {
        if (compressed == null)
        {
            return null;
        }
        Inflater inf = new Inflater();
        try
        {
            inf.setInput(compressed);
            ByteArrayOutputStream out = new ByteArrayOutputStream(65536);
            byte[] buf = new byte[8192];
            while (!inf.finished())
            {
                int n = inf.inflate(buf);
                if (n == 0)
                {
                    if (inf.needsInput() || inf.needsDictionary())
                    {
                        break;
                    }
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
        catch (Exception e)
        {
            return null;
        }
        finally
        {
            inf.end();
        }
    }
}
