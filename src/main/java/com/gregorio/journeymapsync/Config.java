package com.gregorio.journeymapsync;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

/**
 * Config (section 7): config/journeymapsync.cfg
 */
public final class Config
{
    public static boolean enabled;
    public static int sendRadiusChunks;
    public static int maxTilesPerSecond;
    public static boolean verboseLogging;

    private static Configuration cfg;

    private Config()
    {
    }

    public static void init(File file)
    {
        cfg = new Configuration(file);
        load();
    }

    public static void load()
    {
        cfg.load();
        enabled = cfg.getBoolean("enabled", Configuration.CATEGORY_GENERAL, true,
                "Master switch for all tile capture/relay/injection activity.");
        sendRadiusChunks = cfg.getInt("sendRadiusChunks", Configuration.CATEGORY_GENERAL, 8, 1, 32,
                "Radius (in chunks) around the player swept for changed tiles every second.");
        maxTilesPerSecond = cfg.getInt("maxTilesPerSecond", Configuration.CATEGORY_GENERAL, 4, 1, 64,
                "Outbound token bucket rate: live + replay tiles sent per second.");
        verboseLogging = cfg.getBoolean("verboseLogging", Configuration.CATEGORY_GENERAL, false,
                "Log every tile send/receive/inject at info level.");
        if (cfg.hasChanged())
        {
            cfg.save();
        }
    }
}
