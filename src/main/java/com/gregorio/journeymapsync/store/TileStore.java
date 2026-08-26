package com.gregorio.journeymapsync.store;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Local tile store (section 4), rooted at
 * {@code <minecraft-dir>/journeymapsync/<serverKey>/<dimId>/r.<rx>.<rz>.bin}.
 *
 * Region file: magic 'JMSR' + u8 version(1), then append-only records
 * {u16 chunkLocalIdx, i64 ts, u16 len, bytes[len] deflate(tileBody)}; latest record per
 * idx wins. Sidecar r.<rx>.<rz>.idx ('JMSI' + u8 version) holds lastAppliedTs records
 * {u16 idx, i64 ts}, same latest-wins rule.
 *
 * Compaction bookkeeping: live/dead counts recomputed in the single load-time pass that
 * builds the index map (scan every record once per region file when first opened), kept
 * in memory thereafter, updated on append; compaction rewrites purely from the index map
 * when file > 8MB AND dead ratio > 50%. Nothing about liveness is persisted.
 *
 * All public methods are synchronized: callers span the client thread and the injection
 * worker thread.
 */
public final class TileStore
{
    public static final int REGION_CHUNKS = 32 * 32;
    private static final long COMPACTION_SIZE_THRESHOLD = 8L * 1024 * 1024;
    private static final int RECORD_HEADER_BYTES = 12; // u16 idx + i64 ts + u16 len

    private static File root;
    private static String serverKey = "singleplayer";
    private static final Map<String, Region> regions = new HashMap<String, Region>();

    private TileStore()
    {
    }

    public static void init(File minecraftDir)
    {
        root = new File(minecraftDir, "journeymapsync");
        root.mkdirs();
    }

    /** Called on world join/switch with the sanitized server IP or "singleplayer". */
    public static synchronized void setServerKey(String key)
    {
        String next = key == null || key.isEmpty() ? "host" : key;
        if (!next.equals(serverKey))
        {
            regions.clear(); // open handles die with process exit; files are append-safe
        }
        serverKey = next;
    }

    public static int localIdx(int cx, int cz)
    {
        return ((cz & 31) << 5) | (cx & 31);
    }

    /** @return stored timestamp, 0 when absent. */
    public static synchronized long getTs(int dimId, int cx, int cz)
    {
        Long ts = region(dimId, cx, cz).ts.get(localIdx(cx, cz));
        return ts == null ? 0L : ts;
    }

    /**
     * Append a tile record (newest-wins merge is decided by callers comparing getTs).
     */
    public static synchronized void put(int dimId, int cx, int cz, long ts, byte[] compressedBody)
    {
        if (ts <= 0 || compressedBody == null || compressedBody.length > 0xFFFF)
        {
            return;
        }
        try
        {
            Region r = region(dimId, cx, cz);
            int idx = localIdx(cx, cz);
            Long current = r.ts.get(idx);
            if (current != null && current >= ts)
            {
                // Newest-wins at the store too, so file order == ts order on reload.
                return;
            }
            r.ensureAppend();
            long offset = r.append.length();
            r.append.seek(offset);
            r.append.writeShort(idx);
            r.append.writeLong(ts);
            r.append.writeShort(compressedBody.length);
            r.append.write(compressedBody);
            if (r.ts.containsKey(idx))
            {
                r.deadRecords++;
            }
            else
            {
                r.liveRecords++;
            }
            r.ts.put(idx, ts);
            r.bodyOffsets.put(idx, offset);
            maybeCompact(r);
        }
        catch (IOException e)
        {
            // Storage failure must never take down gameplay; drop the tile.
        }
    }

    /** @return the stored DEFLATEd body, or null. */
    public static synchronized byte[] getCompressed(int dimId, int cx, int cz)
    {
        Region r = region(dimId, cx, cz);
        Long offset = r.bodyOffsets.get(localIdx(cx, cz));
        if (offset == null || !r.file.isFile())
        {
            return null;
        }
        RandomAccessFile raf = null;
        try
        {
            raf = new RandomAccessFile(r.file, "r");
            raf.seek(offset);
            raf.readUnsignedShort(); // idx
            raf.readLong();          // ts
            int len = raf.readUnsignedShort();
            byte[] data = new byte[len];
            raf.readFully(data);
            return data;
        }
        catch (IOException e)
        {
            return null;
        }
        finally
        {
            closeQuietly(raf);
        }
    }

    public static synchronized long getLastApplied(int dimId, int cx, int cz)
    {
        Long ts = region(dimId, cx, cz).lastApplied.get(localIdx(cx, cz));
        return ts == null ? 0L : ts;
    }

    public static synchronized void setLastApplied(int dimId, int cx, int cz, long ts)
    {
        Region r = region(dimId, cx, cz);
        try
        {
            r.dimDir().mkdirs();
            if (r.idxOut == null)
            {
                r.idxOut = new FileOutputStream(r.idxFile, true);
            }
            r.idxOut.write(shortBytes(localIdx(cx, cz)));
            r.idxOut.write(longBytes(ts));
            r.idxOut.flush();
        }
        catch (IOException e)
        {
            return;
        }
        r.lastApplied.put(localIdx(cx, cz), ts);
    }

    /**
     * Snapshot of every non-empty region of a dimension, for Digest answering and local
     * replay. Sorted by (rx, rz) so fragmentation is deterministic.
     */
    public static synchronized List<RegionSnapshot> listRegions(int dimId)
    {
        List<RegionSnapshot> out = new ArrayList<RegionSnapshot>();
        File dimDir = new File(new File(root, serverKey), Integer.toString(dimId));
        File[] files = dimDir.listFiles();
        if (files != null)
        {
            for (File f : files)
            {
                String name = f.getName();
                if (!name.startsWith("r.") || !name.endsWith(".bin"))
                {
                    continue;
                }
                String[] parts = name.split("\\.");
                if (parts.length != 4)
                {
                    continue;
                }
                try
                {
                    int rx = Integer.parseInt(parts[1]);
                    int rz = Integer.parseInt(parts[2]);
                    Region r = regionByPos(dimId, rx, rz);
                    long[] chunkTs = new long[REGION_CHUNKS];
                    boolean any = false;
                    for (Map.Entry<Integer, Long> e : r.ts.entrySet())
                    {
                        chunkTs[e.getKey()] = e.getValue();
                        any = any || e.getValue() != 0;
                    }
                    if (any)
                    {
                        out.add(new RegionSnapshot(rx, rz, chunkTs));
                    }
                }
                catch (NumberFormatException ignored)
                {
                }
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    public static final class RegionSnapshot implements Comparable<RegionSnapshot>
    {
        public final int rx;
        public final int rz;
        public final long[] chunkTs;

        RegionSnapshot(int rx, int rz, long[] chunkTs)
        {
            this.rx = rx;
            this.rz = rz;
            this.chunkTs = chunkTs;
        }

        @Override
        public int compareTo(RegionSnapshot o)
        {
            int c = Integer.compare(rx, o.rx);
            return c != 0 ? c : Integer.compare(rz, o.rz);
        }
    }

    // ---- internals ------------------------------------------------------------

    private static Region region(int dimId, int cx, int cz)
    {
        return regionByPos(dimId, cx >> 5, cz >> 5);
    }
    private static Region regionByPos(int dimId, int rx, int rz)
    {
        String key = serverKey + "/" + dimId + "/" + rx + "." + rz;
        Region r = regions.get(key);
        if (r == null)
        {
            File dimDir = new File(new File(root, serverKey), Integer.toString(dimId));
            r = new Region(new File(dimDir, "r." + rx + "." + rz + ".bin"),
                    new File(dimDir, "r." + rx + "." + rz + ".idx"));
            r.load();
            regions.put(key, r);
        }
        return r;
    }

    private static void maybeCompact(Region r)
    {
        try
        {
            if (r.file.length() > COMPACTION_SIZE_THRESHOLD
                    && r.deadRecords > r.liveRecords)
            {
                compact(r);
            }
        }
        catch (IOException ignored)
        {
        }
    }

    /** Rewrite purely from the index map: one live record per chunkLocalIdx. */
    private static void compact(Region r) throws IOException
    {
        if (!r.file.isFile())
        {
            return;
        }
        closeAppend(r);
        File tmp = new File(r.file.getParentFile(), r.file.getName() + ".tmp");
        RandomAccessFile src = new RandomAccessFile(r.file, "r");
        RandomAccessFile dst = new RandomAccessFile(tmp, "rw");
        try
        {
            dst.setLength(0);
            dst.write('J');
            dst.write('M');
            dst.write('S');
            dst.write('R');
            dst.write(1);
            for (Map.Entry<Integer, Long> e : r.bodyOffsets.entrySet())
            {
                src.seek(e.getValue());
                byte[] header = new byte[RECORD_HEADER_BYTES];
                src.readFully(header);
                int len = ((header[10] & 0xFF) << 8) | (header[11] & 0xFF);
                dst.write(header);
                byte[] body = new byte[len];
                src.readFully(body);
                dst.write(body);
            }
        }
        finally
        {
            closeQuietly(src);
            closeQuietly(dst);
        }
        File old = new File(r.file.getParentFile(), r.file.getName() + ".old");
        if (r.file.renameTo(old))
        {
            if (!tmp.renameTo(r.file))
            {
                // best effort: restore
                old.renameTo(r.file);
                tmp.delete();
            }
            else
            {
                old.delete();
            }
        }
        else
        {
            tmp.delete();
        }
        // Reopen state: counts collapse to all-live.
        r.deadRecords = 0;
        r.liveRecords = r.ts.size();
    }
    /** Numeric dimension directories under the current server key. */
    public static synchronized List<Integer> listDimensions()
    {
        List<Integer> dims = new ArrayList<Integer>();
        File serverDir = new File(root, serverKey);
        File[] files = serverDir.listFiles();
        if (files != null)
        {
            for (File f : files)
            {
                if (!f.isDirectory())
                {
                    continue;
                }
                try
                {
                    dims.add(Integer.parseInt(f.getName()));
                }
                catch (NumberFormatException ignored)
                {
                }
            }
        }
        java.util.Collections.sort(dims);
        return dims;
    }


    private static void closeAppend(Region r)
    {
        if (r.append != null)
        {
            try
            {
                r.append.close();
            }
            catch (IOException ignored)
            {
            }
            r.append = null;
        }
        if (r.idxOut != null)
        {
            try
            {
                r.idxOut.close();
            }
            catch (IOException ignored)
            {
            }
            r.idxOut = null;
        }
    }

    private static void closeQuietly(RandomAccessFile f)
    {
        if (f != null)
        {
            try
            {
                f.close();
            }
            catch (IOException ignored)
            {
            }
        }
    }

    private static byte[] shortBytes(int v)
    {
        return new byte[]{(byte) ((v >> 8) & 0xFF), (byte) (v & 0xFF)};
    }

    private static byte[] longBytes(long v)
    {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++)
        {
            b[i] = (byte) (v >>> (56 - i * 8));
        }
        return b;
    }

    private static final class Region
    {
        final File file;
        final File idxFile;
        final Map<Integer, Long> ts = new HashMap<Integer, Long>();
        final Map<Integer, Long> bodyOffsets = new HashMap<Integer, Long>();
        final Map<Integer, Long> lastApplied = new HashMap<Integer, Long>();
        int liveRecords;
        int deadRecords;
        RandomAccessFile append;
        FileOutputStream idxOut;

        Region(File file, File idxFile)
        {
            this.file = file;
            this.idxFile = idxFile;
        }

        File dimDir()
        {
            return file.getParentFile();
        }

        void ensureAppend() throws IOException
        {
            dimDir().mkdirs();
            if (append == null)
            {
                boolean fresh = !file.isFile() || file.length() == 0;
                append = new RandomAccessFile(file, "rw");
                if (fresh)
                {
                    append.seek(0);
                    append.write('J');
                    append.write('M');
                    append.write('S');
                    append.write('R');
                    append.write(1);
                }
            }
        }

        /** Single load-time pass building the index map + live/dead counters. */
        void load()
        {
            if (!file.isFile())
            {
                loadLastApplied();
                return;
            }
            RandomAccessFile raf = null;
            try
            {
                raf = new RandomAccessFile(file, "r");
                if (raf.length() < 5)
                {
                    return;
                }
                byte[] magic = new byte[4];
                raf.readFully(magic);
                int version = raf.readUnsignedByte();
                if (magic[0] != 'J' || magic[1] != 'M' || magic[2] != 'S' || magic[3] != 'R' || version != 1)
                {
                    return;
                }
                long pos = 5;
                long total = raf.length();
                while (pos + RECORD_HEADER_BYTES <= total)
                {
                    raf.seek(pos);
                    int idx = raf.readUnsignedShort();
                    long recTs = raf.readLong();
                    int len = raf.readUnsignedShort();
                    long end = pos + RECORD_HEADER_BYTES + len;
                    if (end > total)
                    {
                        break; // torn tail write: keep what parsed
                    }
                    if (ts.containsKey(idx))
                    {
                        deadRecords++;
                    }
                    else
                    {
                        liveRecords++;
                    }
                    ts.put(idx, recTs);
                    bodyOffsets.put(idx, pos);
                    pos = end;
                }
            }
            catch (IOException ignored)
            {
                // treat as truncated store; keep what parsed
            }
            finally
            {
                closeQuietly(raf);
            }
            loadLastApplied();
        }

        /** Sidecar: lastAppliedTs per chunkLocalIdx, latest record wins. */
        void loadLastApplied()
        {
            if (!idxFile.isFile())
            {
                return;
            }
            RandomAccessFile raf = null;
            try
            {
                raf = new RandomAccessFile(idxFile, "r");
                if (raf.length() < 5)
                {
                    return;
                }
                byte[] magic = new byte[4];
                raf.readFully(magic);
                int version = raf.readUnsignedByte();
                if (magic[0] != 'J' || magic[1] != 'M' || magic[2] != 'S' || magic[3] != 'I' || version != 1)
                {
                    return;
                }
                long pos = 5;
                long total = raf.length();
                while (pos + 10 <= total)
                {
                    raf.seek(pos);
                    int idx = raf.readUnsignedShort();
                    long appliedTs = raf.readLong();
                    lastApplied.put(idx, appliedTs);
                    pos += 10;
                }
            }
            catch (IOException ignored)
            {
            }
            finally
            {
                closeQuietly(raf);
            }
        }
    }
}
