package com.gregorio.journeymapsync.net;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

/**
 * Shared message header used by every message on the jmsync channel:
 * u8 protoVersion(=1), u8 nameLen, UTF-8 senderName[nameLen]. Big-endian throughout.
 */
public final class Wire
{
    static final byte PROTO_VERSION = 1;
    public static final int HEADER_OVERHEAD = 2 + 255; // worst case

    private Wire()
    {
    }

    public static void writeHeader(ByteBuf buf, String senderName)
    {
        byte[] name = senderName.getBytes(StandardCharsets.UTF_8);
        if (name.length > 255)
        {
            byte[] trimmed = new byte[255];
            System.arraycopy(name, 0, trimmed, 0, 255);
            name = trimmed;
        }
        buf.writeByte(PROTO_VERSION);
        buf.writeByte(name.length);
        buf.writeBytes(name);
    }

    /**
     * @return sender name, or null when the header is malformed / protocol version mismatched.
     */
    public static String readHeader(ByteBuf buf)
    {
        if (buf.readableBytes() < 2)
        {
            return null;
        }
        int proto = buf.readUnsignedByte();
        int nameLen = buf.readUnsignedByte();
        if (proto != PROTO_VERSION || buf.readableBytes() < nameLen)
        {
            return null;
        }
        byte[] name = new byte[nameLen];
        buf.readBytes(name);
        return new String(name, StandardCharsets.UTF_8);
    }
}
