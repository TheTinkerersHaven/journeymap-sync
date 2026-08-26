package com.gregorio.journeymapsync.client;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Session-cumulative counters surfaced by /jmsync status.
 */
public final class ClientSyncStats
{
    public static final AtomicLong sent = new AtomicLong();
    public static final AtomicLong received = new AtomicLong();
    public static final AtomicLong stored = new AtomicLong();
    public static final AtomicLong injected = new AtomicLong();
    /** tiles received shortly after we issued a TilesRequest (catchup replay path) */
    public static final AtomicLong replayed = new AtomicLong();

    private ClientSyncStats()
    {
    }
}
