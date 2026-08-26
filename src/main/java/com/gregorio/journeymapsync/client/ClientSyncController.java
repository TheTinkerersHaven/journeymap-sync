package com.gregorio.journeymapsync.client;

import com.gregorio.journeymapsync.Config;
import com.gregorio.journeymapsync.JourneyMapSync;
import com.gregorio.journeymapsync.capture.PngTileBody;
import com.gregorio.journeymapsync.capture.PngTileCodec;
import com.gregorio.journeymapsync.capture.PngTileScanner;
import com.gregorio.journeymapsync.net.SyncNetwork;
import com.gregorio.journeymapsync.net.inner.DigestPayload;
import com.gregorio.journeymapsync.net.inner.DigestRequestPayload;
import com.gregorio.journeymapsync.net.inner.TilesRequestPayload;
import com.gregorio.journeymapsync.net.msg.HelloMsg;
import com.gregorio.journeymapsync.net.msg.RelayMsg;
import com.gregorio.journeymapsync.net.msg.TileMsg;
import com.gregorio.journeymapsync.store.TileStore;
import cpw.mods.fml.common.FMLCommonHandler;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.entity.player.EntityPlayer;

import net.minecraft.util.ChatComponentText;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.ChunkEvent;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

/**
 * Client brain: capture triggers (section 3), outbound throttle, catchup handshake
 * (section 6), relay availability and peer tracking, chat feedback (section 7).
 *
 * Network handlers run on netty threads; everything here that touches world/store runs
 * on the client thread via the {@link #tasks} bridge.
 */
public final class ClientSyncController
{
    private static final int HELLO_INTERVAL_MS = 30_000;
    private static final int RELAY_WARN_AFTER_MS = 10_000;
    private static final int PEER_REPEAT_HELLO_MS = 60_000;
    private static final int DIGEST_WINDOW_MS = 15_000;
    private static final int DIGEST_PACE_MS = 250;
    private static final int SWEEP_INTERVAL_TICKS = 20;
    private static final int MAX_CONCURRENT_WINDOWS = 3;
    private static final long RETRY_TTL_MS = 60_000L;

    private static final Comparator<TileMsg> OLDEST_FIRST = new Comparator<TileMsg>()
    {
        @Override
        public int compare(TileMsg a, TileMsg b)
        {
            return Long.compare(a.ts, b.ts);
        }
    };

    private static final ClientSyncController INSTANCE = new ClientSyncController();

    public static ClientSyncController get()
    {
        return INSTANCE;
    }

    private ClientSyncController()
    {
    }

    // session state
    private boolean sessionActive;
    private long sessionStartAt;
    private int lastDim;
    private boolean relayAvailable;
    private boolean warnedNoRelay;
    private long lastHelloAt;
    private int tickCount;

    // client-thread task bridge from netty handlers
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<Runnable>();

    // peers: senderName -> last hello seen
    private final Map<String, Long> peersLastSeen = new HashMap<String, Long>();

    // outbound scheduler: token bucket + live/replay queues (all client-thread accessed)
    private float tokens;
    private long lastRefillAt;
    private final ArrayDeque<TileMsg> liveQueue = new ArrayDeque<TileMsg>();
    private final PriorityQueue<TileMsg> replayQueue = new PriorityQueue<TileMsg>(64, OLDEST_FIRST);
    private long lastReplayRequestAt = Long.MIN_VALUE;
    // client-thread queue for received tiles pending PNG write (bounded)
    private final ArrayDeque<TileMsg> pendingWrites = new ArrayDeque<TileMsg>();
    // CRC32 dedupe of last sent tile per chunk key
    private final Map<Long, Long> lastSentCrc = new HashMap<Long, Long>();

    // catchup state machine (section 6)
    private long pendingInitialCatchupAt;
    private boolean initialCatchupDone;
    private long peerCatchupScheduledAt;
    private final Set<Integer> pendingDigestRequests = new HashSet<Integer>();
    private final List<CatchupWindow> windows = new ArrayList<CatchupWindow>();

    // digest answering
    private int digestAnswerReqId;
    private List<List<DigestPayload.DigestEntry>> digestFragments;
    private long nextFragmentAt;
    // Incremental sweep state (fix stutter: was scanning 289 chunks in one tick)
    private int sweepCursor = 0;
    private final List<ChunkCoordIntPair> sweepList = new ArrayList<ChunkCoordIntPair>();
    private int lastSweepPcx = Integer.MIN_VALUE;
    private int lastSweepPcz = Integer.MIN_VALUE;
    private final Set<Long> dirtyChunks = new HashSet<Long>();
    private final Map<Long, Long> pendingRetryUntil = new java.util.LinkedHashMap<Long, Long>();

    private final Random rng = new Random();

    private static final class CatchupWindow
    {
        final int reqId;
        final long expiresAt;
        final Map<Long, TilesRequestPayload.TileRef> wanted = new HashMap<Long, TilesRequestPayload.TileRef>();

        CatchupWindow(int reqId, long expiresAt)
        {
            this.reqId = reqId;
            this.expiresAt = expiresAt;
        }
    }

    // ---- lifecycle ------------------------------------------------------------

    public void register()
    {
        TileStore.init(Minecraft.getMinecraft().mcDataDir);
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
    }

    // ---- events ----------------------------------------------------------------

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event)
    {
        if (!Config.enabled || event.world == null || !event.world.isRemote)
        {
            return;
        }
        final Chunk chunk = event.getChunk();
        enqueueTask(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    Minecraft mc = Minecraft.getMinecraft();
                    int dim = event.world.provider.dimensionId;
                    boolean captured = captureChunk(mc, dim, chunk.xPosition, chunk.zPosition);
                    if (!captured)
                    {
                        long key = packKey(dim, chunk.xPosition, chunk.zPosition);
                        pendingRetryUntil.put(key, System.currentTimeMillis() + RETRY_TTL_MS);
                    }
                }
                catch (Throwable t)
                {
                    JourneyMapSync.LOGGER.warn("chunk capture failed: " + t);
                }
            }
        });
    }

    @SubscribeEvent
    public void onBlockPlace(BlockEvent.PlaceEvent event)
    {
        if (!Config.enabled || event.world == null || !event.world.isRemote) return;
        long key = ((long) (event.x >> 4) << 32) | (event.z >> 4 & 0xffffffffL);
        dirtyChunks.add(key);
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event)
    {
        if (!Config.enabled || event.world == null || !event.world.isRemote) return;
        long key = ((long) (event.x >> 4) << 32) | (event.z >> 4 & 0xffffffffL);
        dirtyChunks.add(key);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END || !Config.enabled)
        {
            return;
        }
        try
        {
            tick();
        }
        catch (Throwable t)
        {
            JourneyMapSync.LOGGER.warn("client tick sync failed: " + t);
        }
    }

    private void tick()
    {
        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.theWorld;
        if (world == null || !world.isRemote)
        {
            if (sessionActive)
            {
                endSession();
            }
            return;
        }
        if (!sessionActive)
        {
            startSession(mc, world);
        }

        drainTasks(mc);
        long now = System.currentTimeMillis();

        int dim = world.provider.dimensionId;
        if (dim != lastDim)
        {
            lastDim = dim;
            onDimensionChanged(mc);
        }

        if (now - lastHelloAt >= HELLO_INTERVAL_MS)
        {
            sendHello(now);
        }

        if (!relayAvailable)
        {
            if (!warnedNoRelay && now - sessionStartAt >= RELAY_WARN_AFTER_MS)
            {
                warnedNoRelay = true;
                chat(mc, "journeymapsync: server has no relay (install the jar server-side)");
            }
        }
        else
        {
            if (pendingInitialCatchupAt > 0 && now >= pendingInitialCatchupAt)
            {
                pendingInitialCatchupAt = 0;
                initialCatchupDone = true;
                sendDigestRequest(now);
            }
            if (peerCatchupScheduledAt > 0 && now >= peerCatchupScheduledAt && windows.isEmpty())
            {
                peerCatchupScheduledAt = 0;
                sendDigestRequest(now);
            }
            if (digestAnswerReqId != 0 && now >= nextFragmentAt)
            {
                sendDueDigestFragments();
            }
            expireWindows(now);
        }

        if (relayAvailable && tickCount % SWEEP_INTERVAL_TICKS == 0)
        {
            sweep(mc);
        }
        pumpOutbound(now);
        writeQueuedTiles(mc);

        tickCount++;
    }

    /**
     * Write received tile PNGs to JM's data directory on the client thread.
     * JM's own loader picks up the files on its next render cycle — no fabrication,
     * no GL context issues.
     */
    private void writeQueuedTiles(Minecraft mc)
    {
        if (!sessionActive)
        {
            return;
        }
        World world = mc.theWorld;
        if (world == null || !world.isRemote)
        {
            return;
        }
        File jmWorldDir = PngTileScanner.getJmWorldDir(mc);
        if (jmWorldDir == null)
        {
            return; // JM not present or path unavailable
        }

        for (int i = 0; i < 8; i++)
        {
            TileMsg msg;
            synchronized (pendingWrites)
            {
                msg = pendingWrites.poll();
            }
            if (msg == null)
            {
                break;
            }
            try
            {
                // Gate: only write for the current dimension
                if (msg.dimId != world.provider.dimensionId)
                {
                    continue;
                }

                PngTileBody body = PngTileCodec.decode(msg.compressed);
                if (body == null)
                {
                    continue;
                }

                // Build target path: <jmWorldDir>/DIM<dimId>/<mapTypeDir>/<rx>,<rz>.png
                int rx = msg.cx >> 5; // region coord = chunk >> 5
                int rz = msg.cz >> 5;
                int chunkXInRegion = msg.cx - (rx << 5);
                int chunkZInRegion = msg.cz - (rz << 5);

                File mapTypeDir = new File(new File(jmWorldDir, "DIM" + msg.dimId), body.mapTypeDir);
                File regionFile = new File(mapTypeDir, rx + "," + rz + ".png");
                if (!regionFile.exists())
                {
                    // Region file doesn't exist yet — skip (JM will create it on its own render)
                    continue;
                }

                // Read the existing region PNG, splice in our 16x16 chunk, write back.
                BufferedImage regionImg = ImageIO.read(regionFile);
                if (regionImg == null)
                {
                    continue;
                }

                BufferedImage chunkImg = decodePng(body.pngChunk);
                if (chunkImg == null)
                {
                    continue;
                }

                // Splice the chunk sub-image into the region at the right offset.
                // Only if the region is 512x512 (standard JM format).
                if (regionImg.getWidth() == 512 && regionImg.getHeight() == 512
                        && chunkImg.getWidth() == 16 && chunkImg.getHeight() == 16)
                {
                    Graphics2D g = regionImg.createGraphics();
                    g.drawImage(chunkImg, chunkXInRegion * 16, chunkZInRegion * 16, null);
                    g.dispose();
                }

                // Write the updated region PNG back to disk.
                javax.imageio.ImageIO.write(regionImg, "PNG", regionFile);

                // Trigger JM to reload the region image from disk on its next render pass.
                triggerJmRegionReload(jmWorldDir, msg.dimId, rx, rz);

                TileStore.setLastApplied(msg.dimId, msg.cx, msg.cz, msg.ts);
                ClientSyncStats.injected.incrementAndGet();
                if (Config.verboseLogging)
                {
                    JourneyMapSync.LOGGER.info("Injected tile dim=" + msg.dimId + " chunk=[" + msg.cx + "," + msg.cz + "] ts=" + msg.ts);
                }
            }
            catch (Throwable t)
            {
                JourneyMapSync.LOGGER.warn("Tile write failed at [" + msg.cx + "," + msg.cz + "] dim " + msg.dimId + ": " + t);
            }
        }
    }

    private static BufferedImage decodePng(byte[] pngBytes)
    {
        try
        {
            return ImageIO.read(new java.io.ByteArrayInputStream(pngBytes));
        }
        catch (IOException e)
        {
            return null;
        }
    }

    /**
     * Trigger JourneyMap's {@link journeymap.client.model.RegionImageCache} to reload
     * the region image set for the region we just wrote, so JM re-reads the PNG from
     * disk on its next render pass.
     */
    private static void triggerJmRegionReload(File jmWorldDir, int dimId, int rx, int rz)
    {
        try
        {
            journeymap.client.model.RegionImageCache cache = journeymap.client.model.RegionImageCache.instance();
            journeymap.client.model.RegionCoord rc =
                    new journeymap.client.model.RegionCoord(jmWorldDir, rx, rz, dimId);
            cache.getRegionImageSet(rc);
        }
        catch (Throwable t)
        {
            // JM not present or API changed — file will be picked up on next render cycle
        }
    }

    private void startSession(Minecraft mc, World world)
    {
        sessionActive = true;
        sessionStartAt = System.currentTimeMillis();
        lastDim = world.provider.dimensionId;
        relayAvailable = false;
        warnedNoRelay = false;
        pendingInitialCatchupAt = 0;
        initialCatchupDone = false;
        peerCatchupScheduledAt = 0;
        nextFragmentAt = 0;
        digestAnswerReqId = 0;
        digestFragments = null;
        windows.clear();
        pendingDigestRequests.clear();
        peersLastSeen.clear();
        liveQueue.clear();
        replayQueue.clear();
        synchronized (pendingWrites)
        {
            pendingWrites.clear();
        }
        pendingRetryUntil.clear();
        dirtyChunks.clear();
        lastRefillAt = sessionStartAt;
        TileStore.setServerKey(resolveServerKey(mc));
        JourneyMapSync.LOGGER.info("Sync session started (server key '" + resolveServerKey(mc) + "', dim " + lastDim + ")");
    }

    private void endSession()
    {
        sessionActive = false;
        relayAvailable = false;
        warnedNoRelay = false;
        pendingInitialCatchupAt = 0;
        initialCatchupDone = false;
        peerCatchupScheduledAt = 0;
        digestAnswerReqId = 0;
        digestFragments = null;
        windows.clear();
        pendingDigestRequests.clear();
        peersLastSeen.clear();
        liveQueue.clear();
        replayQueue.clear();
        synchronized (pendingWrites)
        {
            pendingWrites.clear();
        }
        pendingRetryUntil.clear();
    }

    private static String resolveServerKey(Minecraft mc)
    {
        ServerData data = mc.func_147104_D();
        if (data == null || data.serverIP == null || data.serverIP.isEmpty())
        {
            return "singleplayer";
        }
        return data.serverIP.replaceAll("\\W+", "_");
    }

    // ---- network callbacks (netty threads -> tasks) ------------------------------

    public void onPong()
    {
        enqueueTask(new Runnable()
        {
            @Override
            public void run()
            {
                boolean wasDown = !relayAvailable;
                relayAvailable = true;
                if (wasDown && sessionActive)
                {
                    chat(Minecraft.getMinecraft(), "journeymapsync: relay detected - sharing map data");
                    if (!initialCatchupDone)
                    {
                        pendingInitialCatchupAt = System.currentTimeMillis() + rnd(1000, 5000);
                    }
                }
            }
        });
    }

    public void onHelloReceived(final String senderName)
    {
        enqueueTask(new Runnable()
        {
            @Override
            public void run()
            {
                if (!sessionActive)
                {
                    return;
                }
                long now = System.currentTimeMillis();
                Long seen = peersLastSeen.get(senderName);
                boolean freshPeer = seen == null || now - seen > PEER_REPEAT_HELLO_MS;
                peersLastSeen.put(senderName, now);
                if (freshPeer && windows.isEmpty() && peerCatchupScheduledAt == 0)
                {
                    peerCatchupScheduledAt = now + rnd(1000, 5000);
                }
            }
        });
    }

    public void onTileReceived(final TileMsg msg)
    {
        enqueueTask(new Runnable()
        {
            @Override
            public void run()
            {
                handleTile(msg);
            }
        });
    }

    public void onDigestRequest(final int reqId)
    {
        enqueueTask(new Runnable()
        {
            @Override
            public void run()
            {
                if (!sessionActive)
                {
                    return;
                }
                digestAnswerReqId = reqId;
                digestFragments = null;
                nextFragmentAt = System.currentTimeMillis() + rnd(1000, 5000);
            }
        });
    }

    public void onDigestReceived(final String sender, final DigestPayload payload)
    {
        enqueueTask(new Runnable()
        {
            @Override
            public void run()
            {
                CatchupWindow window = findWindow(payload.reqId);
                if (window == null || !sessionActive)
                {
                    return; // only requesters with matching pending reqIds consume
                }
                for (DigestPayload.DigestEntry e : payload.entries)
                {
                    for (int idx = 0; idx < TileStore.REGION_CHUNKS; idx++)
                    {
                        long peerTs = e.chunkTs[idx];
                        if (peerTs <= 0)
                        {
                            continue;
                        }
                        int cx = (e.regionX << 5) | (idx & 31);
                        int cz = (e.regionZ << 5) | (idx >> 5);
                        long mine = Math.max(TileStore.getLastApplied(e.dimId, cx, cz),
                                TileStore.getTs(e.dimId, cx, cz));
                        if (peerTs > mine)
                        {
                            window.wanted.put(packKey(e.dimId, cx, cz),
                                    new TilesRequestPayload.TileRef(e.dimId, cx, cz, peerTs));
                        }
                    }
                }
                if (Config.verboseLogging)
                {
                    JourneyMapSync.LOGGER.info("Digest from " + sender + ": " + payload.entries.size()
                            + " regions, wanted set now " + window.wanted.size());
                }
            }
        });
    }

    public void onTilesRequest(final String sender, final TilesRequestPayload payload)
    {
        enqueueTask(new Runnable()
        {
            @Override
            public void run()
            {
                if (!sessionActive)
                {
                    return;
                }
                int offered = 0;
                for (TilesRequestPayload.TileRef ref : payload.entries)
                {
                    long mine = TileStore.getTs(ref.dimId, ref.cx, ref.cz);
                    if (mine > ref.myTs)
                    {
                        byte[] body = TileStore.getCompressed(ref.dimId, ref.cx, ref.cz);
                        if (body != null && body.length <= TileMsg.MAX_COMPRESSED_LEN)
                        {
                            replayQueue.add(new TileMsg(myName(), ref.dimId, ref.cx, ref.cz, mine, body));
                            offered++;
                        }
                    }
                }
                if (Config.verboseLogging && offered > 0)
                {
                    JourneyMapSync.LOGGER.info("TilesRequest from " + sender + ": queueing " + offered + " replay tiles");
                }
            }
        });
    }

    // ---- client-thread work ------------------------------------------------------

    private void handleTile(TileMsg msg)
    {
        if (!sessionActive)
        {
            return;
        }
        ClientSyncStats.received.incrementAndGet();
        if (lastReplayRequestAt != Long.MIN_VALUE
                && System.currentTimeMillis() - lastReplayRequestAt < DIGEST_WINDOW_MS + 30_000L)
        {
            ClientSyncStats.replayed.incrementAndGet();
        }
        long storedTs = TileStore.getTs(msg.dimId, msg.cx, msg.cz);
        if (storedTs >= msg.ts)
        {
            return; // newest-wins merge: stale or duplicate
        }
        TileStore.put(msg.dimId, msg.cx, msg.cz, msg.ts, msg.compressed);
        ClientSyncStats.stored.incrementAndGet();
        // Queue for client-thread PNG write to JM's data directory.
        synchronized (pendingWrites)
        {
            pendingWrites.add(msg);
            if (pendingWrites.size() > 4096)
            {
                pendingWrites.poll(); // backpressure: drop oldest
            }
        }
        if (Config.verboseLogging)
        {
            JourneyMapSync.LOGGER.info("Received tile dim=" + msg.dimId + " [" + msg.cx + "," + msg.cz
                    + "] ts=" + msg.ts + " from " + msg.senderName);
        }
    }

    /**
     * Extract + persist (+ maybe send) one chunk tile. Runs on the client thread.
     *
     * @return true if a tile was captured (PNG existed and was sent or stored),
     *         false if JM hasn't rendered the chunk yet (caller should retry later).
     */
    private boolean captureChunk(Minecraft mc, int dim, int cx, int cz)
    {
        if (!sessionActive || mc.theWorld == null || !mc.theWorld.isRemote)
        {
            return false;
        }
        // Read the rendered PNG sub-image for this chunk from JM's data dir.
        PngTileBody body = PngTileScanner.scan(mc, dim, cx, cz);
        if (body == null)
        {
            return false; // JM hasn't rendered this chunk/region yet
        }
        byte[] encoded = PngTileCodec.encode(body);
        long crc = PngTileCodec.crcOfEncoded(encoded);
        Long prev = lastSentCrc.get(packKey(dim, cx, cz));
        if (prev != null && prev == crc)
        {
            return true; // unchanged since last send — still a success
        }

        long ts = System.currentTimeMillis();
        // Persist BEFORE sending so history survives regardless of delivery.
        TileStore.put(dim, cx, cz, ts, encoded);
        ClientSyncStats.stored.incrementAndGet();
        lastSentCrc.put(packKey(dim, cx, cz), crc);

        if (encoded.length > TileMsg.MAX_COMPRESSED_LEN)
        {
            return true; // PNG too large (pathological case): keep in store, never send
        }
        submitLive(new TileMsg(myName(), dim, cx, cz, ts, encoded));
        return true;
    }

    private void submitLive(TileMsg msg)
    {
        if (tokens >= 1f)
        {
            tokens -= 1f;
            SyncNetwork.INSTANCE.sendToServer(msg);
            ClientSyncStats.sent.incrementAndGet();
        }
        else
        {
            liveQueue.add(msg); // newest terrain outranks replay backlog
        }
    }

    private void pumpOutbound(long now)
    {
        float rate = Math.max(1, Config.maxTilesPerSecond);
        long dt = now - lastRefillAt;
        if (dt > 0)
        {
            tokens = Math.min(rate, tokens + rate * (dt / 1000f));
            lastRefillAt = now;
        }
        while (tokens >= 1f)
        {
            TileMsg msg = liveQueue.poll();
            if (msg == null)
            {
                msg = replayQueue.poll(); // lowest ts first
            }
            if (msg == null)
            {
                break;
            }
            tokens -= 1f;
            SyncNetwork.INSTANCE.sendToServer(msg);
            ClientSyncStats.sent.incrementAndGet();
        }
    }

    private void sweep(Minecraft mc)
    {
        if (!sessionActive)
        {
            return;
        }
        EntityPlayer player = mc.thePlayer;
        World world = mc.theWorld;
        if (player == null || world == null)
        {
            return;
        }
        int budget = Math.max(2, Config.maxTilesPerSecond);
        int pcx = ((int) player.posX) >> 4;
        int pcz = ((int) player.posZ) >> 4;

        // 1) Prioritize dirty chunks (placed/broken blocks) - immediate visibility
        if (!dirtyChunks.isEmpty())
        {
            int dim = world.provider.dimensionId;
            List<Long> toScan = new ArrayList<Long>(dirtyChunks);
            dirtyChunks.clear();
            for (Long key : toScan)
            {
                if (budget <= 0) { dirtyChunks.add(key); continue; }
                int cx = (int) (key >> 32);
                int cz = (int) (key.longValue());
                try
                {
                    if (!captureChunk(mc, dim, cx, cz))
                    {
                        pendingRetryUntil.put(key, System.currentTimeMillis() + RETRY_TTL_MS);
                    }
                }
                catch (Throwable t)
                {
                    JourneyMapSync.LOGGER.warn("dirty capture failed at [" + cx + "," + cz + "]: " + t);
                    pendingRetryUntil.put(key, System.currentTimeMillis() + RETRY_TTL_MS);
                }
                budget--;
            }
            if (budget <= 0) return;
        }

        // 2) Retry pending chunks whose PNGs weren't ready on first attempt
        if (!pendingRetryUntil.isEmpty() && budget > 0)
        {
            int dim = world.provider.dimensionId;
            Iterator<Map.Entry<Long, Long>> it = pendingRetryUntil.entrySet().iterator();
            while (it.hasNext() && budget > 0)
            {
                Map.Entry<Long, Long> entry = it.next();
                long now = System.currentTimeMillis();
                if (entry.getValue() < now)
                {
                    it.remove(); // TTL expired — give up
                    continue;
                }
                long key = entry.getKey();
                int cx = (int) (key >> 32);
                int cz = (int) (key);
                try
                {
                    if (captureChunk(mc, dim, cx, cz))
                    {
                        it.remove(); // success — no more retries needed
                    }
                }
                catch (Throwable t)
                {
                    JourneyMapSync.LOGGER.warn("retry capture failed at [" + cx + "," + cz + "]: " + t);
                }
                budget--;
            }
            if (budget <= 0) return;
        }

        // 3) Incremental round-robin scan of nearby chunks — read JM's PNGs
        int dim = world.provider.dimensionId;
        int radius = Config.sendRadiusChunks;
        boolean rebuild = sweepList.isEmpty() || pcx != lastSweepPcx || pcz != lastSweepPcz || tickCount % 100 == 0;
        if (rebuild)
        {
            sweepList.clear();
            List<ChunkCoordIntPair> tmp = new ArrayList<ChunkCoordIntPair>();
            for (int dz = -radius; dz <= radius; dz++)
            {
                for (int dx = -radius; dx <= radius; dx++)
                {
                    tmp.add(new ChunkCoordIntPair(pcx + dx, pcz + dz));
                }
            }
            final int fpcx = pcx, fpcz = pcz;
            java.util.Collections.sort(tmp, new Comparator<ChunkCoordIntPair>()
            {
                @Override
                public int compare(ChunkCoordIntPair a, ChunkCoordIntPair b)
                {
                    int da = (a.chunkXPos - fpcx) * (a.chunkXPos - fpcx) + (a.chunkZPos - fpcz) * (a.chunkZPos - fpcz);
                    int db = (b.chunkXPos - fpcx) * (b.chunkXPos - fpcx) + (b.chunkZPos - fpcz) * (b.chunkZPos - fpcz);
                    return Integer.compare(da, db);
                }
            });
            sweepList.addAll(tmp);
            sweepCursor = 0;
            lastSweepPcx = pcx;
            lastSweepPcz = pcz;
        }
        for (int i = 0; i < budget; i++)
        {
            if (sweepList.isEmpty()) break;
            if (sweepCursor >= sweepList.size()) sweepCursor = 0;
            ChunkCoordIntPair coord = sweepList.get(sweepCursor++);
            try { captureChunk(mc, dim, coord.chunkXPos, coord.chunkZPos); } catch (Throwable t) { JourneyMapSync.LOGGER.warn("sweep capture failed at [" + coord.chunkXPos + "," + coord.chunkZPos + "]: " + t); }
        }
    }

    // ---- catchup handshake (section 6) ---------------------------------------------

    private void sendHello(long now)
    {
        lastHelloAt = now;
        SyncNetwork.INSTANCE.sendToServer(new HelloMsg(myName()));
    }

    private void sendDigestRequest(long now)
    {
        if (windows.size() >= MAX_CONCURRENT_WINDOWS)
        {
            return;
        }
        int reqId = rng.nextInt();
        while (!pendingDigestRequests.add(reqId))
        {
            reqId = rng.nextInt();
        }
        windows.add(new CatchupWindow(reqId, now + DIGEST_WINDOW_MS));
        SyncNetwork.INSTANCE.sendToServer(new RelayMsg(myName(), RelayMsg.T_DIGEST_REQUEST,
                new DigestRequestPayload(reqId)));
    }

    private CatchupWindow findWindow(int reqId)
    {
        for (CatchupWindow w : windows)
        {
            if (w.reqId == reqId)
            {
                return w;
            }
        }
        return null;
    }

    private void expireWindows(long now)
    {
        Iterator<CatchupWindow> it = windows.iterator();
        while (it.hasNext())
        {
            CatchupWindow w = it.next();
            if (now < w.expiresAt)
            {
                continue;
            }
            it.remove();
            pendingDigestRequests.remove(w.reqId);
            if (w.wanted.isEmpty())
            {
                continue;
            }
            List<TilesRequestPayload.TileRef> refs = new ArrayList<TilesRequestPayload.TileRef>(w.wanted.values());
            for (int i = 0; i < refs.size(); i += TilesRequestPayload.MAX_ENTRIES_PER_MSG)
            {
                List<TilesRequestPayload.TileRef> batch =
                        refs.subList(i, Math.min(refs.size(), i + TilesRequestPayload.MAX_ENTRIES_PER_MSG));
                SyncNetwork.INSTANCE.sendToServer(new RelayMsg(myName(), RelayMsg.T_TILES_REQUEST,
                        new TilesRequestPayload(w.reqId, batch)));
            }
            lastReplayRequestAt = now;
            JourneyMapSync.LOGGER.info("Catchup: requesting " + refs.size() + " tiles");
        }
    }

    private void sendDueDigestFragments()
    {
        if (digestFragments == null)
        {
            digestFragments = buildDigestFragments();
            if (digestFragments.isEmpty())
            {
                digestAnswerReqId = 0;
                digestFragments = null;
                return;
            }
        }
        List<DigestPayload.DigestEntry> frag = digestFragments.remove(0);
        SyncNetwork.INSTANCE.sendToServer(new RelayMsg(myName(), RelayMsg.T_DIGEST,
                new DigestPayload(digestAnswerReqId, frag)));
        if (Config.verboseLogging)
        {
            JourneyMapSync.LOGGER.info("Digest fragment sent (" + frag.size() + " regions, "
                    + digestFragments.size() + " fragments left)");
        }
        if (digestFragments.isEmpty())
        {
            digestAnswerReqId = 0;
            digestFragments = null;
        }
        else
        {
            nextFragmentAt = System.currentTimeMillis() + DIGEST_PACE_MS;
        }
    }

    /** All non-empty regions across dimensions, fragmented at 3 entries per message. */
    private List<List<DigestPayload.DigestEntry>> buildDigestFragments()
    {
        List<DigestPayload.DigestEntry> all = new ArrayList<DigestPayload.DigestEntry>();
        for (int dim : TileStore.listDimensions())
        {
            for (TileStore.RegionSnapshot snap : TileStore.listRegions(dim))
            {
                all.add(new DigestPayload.DigestEntry(dim, snap.rx, snap.rz, snap.chunkTs));
            }
        }
        List<List<DigestPayload.DigestEntry>> fragments = new ArrayList<List<DigestPayload.DigestEntry>>();
        for (int i = 0; i < all.size(); i += DigestPayload.MAX_ENTRIES_PER_MSG)
        {
            fragments.add(all.subList(i, Math.min(all.size(), i + DigestPayload.MAX_ENTRIES_PER_MSG)));
        }
        return fragments;
    }

    /** Local replay path: current-dim store entries never injected into JM this session. */
    private void onDimensionChanged(Minecraft mc)
    {
        int dim = lastDim;
        int queued = 0;
        for (TileStore.RegionSnapshot snap : TileStore.listRegions(dim))
        {
            for (int idx = 0; idx < TileStore.REGION_CHUNKS; idx++)
            {
                long ts = snap.chunkTs[idx];
                if (ts <= 0)
                {
                    continue;
                }
                int cx = (snap.rx << 5) | (idx & 31);
                int cz = (snap.rz << 5) | (idx >> 5);
                if (ts > TileStore.getLastApplied(dim, cx, cz))
                {
                    byte[] body = TileStore.getCompressed(dim, cx, cz);
                    if (body != null)
                    {
                        synchronized (pendingWrites)
                        {
                            pendingWrites.add(new TileMsg(myName(), dim, cx, cz, ts, body));
                            if (pendingWrites.size() > 4096)
                            {
                                pendingWrites.poll();
                            }
                        }
                        queued++;
                    }
                }
            }
        }
        if (queued > 0)
        {
            JourneyMapSync.LOGGER.info("Dimension change: replaying " + queued + " stored tiles into JM");
        }
    }

    // ---- helpers -----------------------------------------------------------------

    private void enqueueTask(Runnable r)
    {
        synchronized (tasks)
        {
            tasks.add(r);
        }
    }

    private void drainTasks(Minecraft mc)
    {
        while (true)
        {
            Runnable r;
            synchronized (tasks)
            {
                r = tasks.poll();
            }
            if (r == null)
            {
                break;
            }
            try
            {
                r.run();
            }
            catch (Throwable t)
            {
                JourneyMapSync.LOGGER.warn("sync task failed: " + t);
            }
        }
    }

    private String myName()
    {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.getSession() != null ? mc.getSession().getUsername() : "player";
    }

    private int rnd(int minInclusive, int maxExclusive)
    {
        return minInclusive + rng.nextInt(Math.max(1, maxExclusive - minInclusive));
    }

    private void chat(Minecraft mc, String message)
    {
        if (mc.thePlayer != null)
        {
            mc.thePlayer.addChatMessage(new ChatComponentText(message));
        }
    }

    public static long packKey(int dim, int cx, int cz)
    {
        return ((dim & 0xFFFL) << 52) | ((cx & 0x1FFFFFL) << 26) | (cz & 0x1FFFFFL);
    }

    // ---- status / debug (section 7) -----------------------------------------------

    public String[] statusLines()
    {
        List<String> lines = new ArrayList<String>();
        lines.add("journeymapsync status:");
        lines.add("  enabled: " + Config.enabled + ", relay available: " + relayAvailable);
        lines.add("  write queue: " + pendingWrites.size());
        lines.add("  peers seen (" + peersLastSeen.size() + "): " + peersLastSeen.keySet());
        lines.add("  tiles sent: " + ClientSyncStats.sent.get()
                + ", received: " + ClientSyncStats.received.get()
                + " (replayed: " + ClientSyncStats.replayed.get() + ")");
        lines.add("  tiles stored: " + ClientSyncStats.stored.get()
                + ", injected into JM: " + ClientSyncStats.injected.get());
        lines.add("  outbound queued live/replay: " + liveQueue.size() + "/" + replayQueue.size());
        return lines.toArray(new String[lines.size()]);
    }

    /**
     * /jmsync inject <cx> <cz>: create a synthetic 16x16 PNG tile (distinctive
     * magenta/cyan checkerboard pattern) and push it through the normal write
     * path so JM splices it into its region file on the next render cycle.
     */
    public boolean debugInject(int cx, int cz)
    {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null)
        {
            return false;
        }
        // Build a 16x16 checkerboard: magenta on odd columns, cyan on even.
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        for (int y = 0; y < 16; y++)
        {
            for (int x = 0; x < 16; x++)
            {
                boolean odd = ((x ^ y) & 1) != 0;
                g.setColor(odd ? java.awt.Color.MAGENTA : java.awt.Color.CYAN);
                g.drawLine(x, y, x, y);
            }
        }
        g.dispose();

        // Encode the chunk PNG
        ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
        try
        {
            javax.imageio.ImageIO.write(img, "PNG", baos);
        }
        catch (IOException e)
        {
            JourneyMapSync.LOGGER.warn("debug inject failed: " + e);
            return false;
        }
        byte[] pngChunk = baos.toByteArray();

        // Use "day" map type so we know where to splice
        PngTileBody body = new PngTileBody("day", pngChunk);
        byte[] compressed = PngTileCodec.encode(body);
        int dim = mc.theWorld.provider.dimensionId;
        synchronized (pendingWrites)
        {
            pendingWrites.add(new TileMsg(myName(), dim, cx, cz, System.currentTimeMillis(), compressed));
        }
        chat(mc, "journeymapsync: synthetic PNG tile queued for [" + cx + "," + cz + "]");
        return true;
    }

    /**
     * /jmsync sync: force a global rescan of all chunks within the send radius.
     * Clears the CRC dedupe cache so all tiles are re-sent, forces the sweep to
     * rebuild its list, and triggers a catchup digest request to pull tiles
     * from peers.
     *
     * @return true if the sync was triggered, false if not in a world or no relay.
     */
    public boolean triggerGlobalSync()
    {
        Minecraft mc = Minecraft.getMinecraft();
        if (!sessionActive || mc.theWorld == null || !mc.theWorld.isRemote)
        {
            return false;
        }
        if (!relayAvailable)
        {
            return false;
        }
        // Clear CRC dedupe so all tiles within radius are re-sent
        lastSentCrc.clear();
        // Force sweep to rebuild its list on the next tick
        lastSweepPcx = Integer.MIN_VALUE;
        lastSweepPcz = Integer.MIN_VALUE;
        sweepList.clear();
        sweepCursor = 0;
        // Clear pending retries so stale ones don't interfere
        pendingRetryUntil.clear();
        // Trigger a catchup digest to pull missing tiles from peers
        enqueueTask(new Runnable()
        {
            @Override
            public void run()
            {
                sendDigestRequest(System.currentTimeMillis());
            }
        });
        chat(mc, "journeymapsync: global sync triggered (rescanning radius + pulling from peers)");
        JourneyMapSync.LOGGER.info("Global sync triggered: clearing CRC cache, forcing sweep rebuild, requesting digest");
        return true;
    }
}
