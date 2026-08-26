package com.gregorio.journeymapsync.capture;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

/**
 * Reads a single chunk's PNG sub-image from JourneyMap's on-disk region cache.
 *
 * JM stores region tiles as 512x512 PNG files:
 *   journeymap/data/mp/<server>/DIM<dim>/<mapTypeDir>/<rx>,<rz>.png
 * Each region PNG is 32x32 chunks of 16x16 pixels (512 = 32*16).
 *
 * This scanner extracts the 16x16 pixel sub-image for one chunk (cx, cz) from
 * the region PNG, encodes it as a PNG, and wraps it in a {@link PngTileBody}.
 *
 * Runs on the client tick thread — disk I/O only, no GL context.
 */
public final class PngTileScanner
{
    private PngTileScanner()
    {
    }

    /**
     * Scan JM's region PNGs for the chunk at (cx, cz) in the given dimension.
     * Extracts the 16x16 chunk sub-image and returns it wrapped in a PngTileBody.
     *
     * @return null if JM is not present, the region PNG doesn't exist, or the
     *         chunk coordinates are outside the region's 32x32 chunk bounds.
     */
    public static PngTileBody scan(net.minecraft.client.Minecraft mc, int dimId, int cx, int cz)
    {
        File jmWorldDir = getJmWorldDir(mc);
        if (jmWorldDir == null)
        {
            return null;
        }

        // Compute region coordinates: region = chunk >> 5 (32 chunks per region)
        int rx = cx >> 5;
        int rz = cz >> 5;
        int chunkXInRegion = cx - (rx << 5);
        int chunkZInRegion = cz - (rz << 5);
        if (chunkXInRegion < 0 || chunkXInRegion >= 32
                || chunkZInRegion < 0 || chunkZInRegion >= 32)
        {
            return null;
        }

        // Build path: <jmWorldDir>/DIM<dimId>/<mapTypeDir>/<rx>,<rz>.png
        File dimDir = new File(jmWorldDir, "DIM" + dimId);

        // Priority: surface maps (day, night, topo) then underground slices (0..15)
        String[] priorities = {"day", "night", "topo"};
        for (String mapTypeDir : priorities)
        {
            File regionFile = new File(new File(dimDir, mapTypeDir), rx + "," + rz + ".png");
            if (regionFile.exists() && regionFile.canRead())
            {
                return extractTile(regionFile, mapTypeDir, chunkXInRegion, chunkZInRegion);
            }
        }

        // Underground slices
        for (int s = 0; s < 16; s++)
        {
            String mapTypeDir = String.valueOf(s);
            File regionFile = new File(new File(dimDir, mapTypeDir), rx + "," + rz + ".png");
            if (regionFile.exists() && regionFile.canRead())
            {
                return extractTile(regionFile, mapTypeDir, chunkXInRegion, chunkZInRegion);
            }
        }

        return null;
    }

    /** Extract a 16x16 chunk sub-image from a 512x512 region PNG and re-encode as PNG. */
    private static PngTileBody extractTile(File regionPng, String mapTypeDir,
                                           int chunkXInRegion, int chunkZInRegion)
    {
        BufferedImage regionImg;
        try
        {
            regionImg = ImageIO.read(regionPng);
        }
        catch (IOException e)
        {
            return null;
        }
        if (regionImg == null)
        {
            return null;
        }

        // Extract 16x16 sub-image for this chunk within the region.
        int px = chunkXInRegion * 16;
        int pz = chunkZInRegion * 16;
        if (px + 16 > regionImg.getWidth() || pz + 16 > regionImg.getHeight())
        {
            return null;
        }
        BufferedImage chunkImg = regionImg.getSubimage(px, pz, 16, 16);

        // Re-encode the sub-image as a standalone PNG.
        ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
        try
        {
            ImageIO.write(chunkImg, "PNG", baos);
        }
        catch (IOException e)
        {
            return null;
        }

        return new PngTileBody(mapTypeDir, baos.toByteArray());
    }

    /** Get JourneyMap's world data directory (JM client-only class). */
    public static File getJmWorldDir(net.minecraft.client.Minecraft mc)
    {
        try
        {
            return journeymap.client.io.FileHandler.getJMWorldDir(mc);
        }
        catch (Throwable t)
        {
            return null;
        }
    }
}
