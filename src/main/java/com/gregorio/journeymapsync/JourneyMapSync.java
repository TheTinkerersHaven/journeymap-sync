package com.gregorio.journeymapsync;

import com.gregorio.journeymapsync.client.ClientSyncController;
import com.gregorio.journeymapsync.client.JmsyncCommand;
import net.minecraftforge.client.ClientCommandHandler;
import com.gregorio.journeymapsync.net.SyncNetwork;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.relauncher.Side;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;

/**
 * JourneyMap Sync - relays explored JourneyMap chunk tiles between players through the
 * game server itself (FML channel), for Minecraft 1.7.10 / GTNH with JourneyMap 5.2.x.
 *
 * Install the same jar on the server (relay) and on every client (capture + injection).
 * No coremod, no access transformers.
 */
@Mod(
    modid = JourneyMapSync.MODID,
    name = JourneyMapSync.NAME,
    version = JourneyMapSync.VERSION,
    acceptedMinecraftVersions = "[1.7.10]",
    dependencies = "required-after:Forge@[10.13.4.1614,)",
    acceptableRemoteVersions = "*"
)
public class JourneyMapSync
{
    public static final String MODID = "journeymapsync";
    public static final String NAME = "JourneyMap Sync";
    public static final String VERSION = "0.2.3";

    public static final Logger LOGGER = LogManager.getLogger(MODID);

    @EventHandler
    public void preInit(FMLPreInitializationEvent event)
    {
        LOGGER.info("JourneyMap Sync " + VERSION + " pre-init");
        Config.init(new File(event.getModConfigurationDirectory(), MODID + ".cfg"));
        SyncNetwork.init();
    }

    @EventHandler
    public void init(FMLInitializationEvent event)
    {
        if (event.getSide() == Side.CLIENT)
        {
            ClientSyncController.get().register();
            ClientCommandHandler.instance.registerCommand(new JmsyncCommand());
        }
        LOGGER.info("JourneyMap Sync initialized (side " + event.getSide() + ")");
    }
}
