package com.activepulse.agent.screenshot;

import com.activepulse.agent.db.ScreenshotDao;
import com.activepulse.agent.monitor.AppConfigManager;
import com.activepulse.agent.util.EnvConfig;
import com.activepulse.agent.util.PathResolver;
import com.activepulse.agent.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

/**
 * Captures the full virtual screen (all monitors) and writes it as JPG
 * to the screenshots directory. Records an entry in the screenshots table.
 */
public final class ScreenshotCapture {

    private static final Logger log = LoggerFactory.getLogger(ScreenshotCapture.class);

    private ScreenshotCapture() {}

    public static void captureNow() {
        if (GraphicsEnvironment.isHeadless()) {
            log.warn("Headless environment — cannot capture screenshot.");
            return;
        }
        try {
            Rectangle bounds = virtualScreenBounds();
            Robot robot = new Robot();
            BufferedImage image = robot.createScreenCapture(bounds);

            String format = EnvConfig.get("SCREENSHOT_FORMAT", "jpg").toLowerCase();
            float  quality = (float) EnvConfig.getDouble("SCREENSHOT_QUALITY", 0.6);

            String ts = java.time.LocalDateTime.now(TimeUtil.IST)
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String fileName = "scr_" + ts + "." + format;
            Path   dir      = PathResolver.screenshotsDir();
            Path   out      = dir.resolve(fileName);

            if ("jpg".equals(format) || "jpeg".equals(format)) {
                writeJpg(image, out.toFile(), quality);
            } else {
                ImageIO.write(image, "png", out.toFile());
            }

            long size = Files.size(out);
            ScreenshotDao.insert(out.toString(), size);
            log.info("Screenshot saved: {} ({} KB) for user {}",
                    fileName, size / 1024, AppConfigManager.getInstance().getUsername());
        } catch (Throwable t) {
            log.error("Screenshot capture failed: {}", t.getMessage());
        }
    }

    private static Rectangle virtualScreenBounds() {
        Rectangle r = new Rectangle();
        for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            for (GraphicsConfiguration gc : gd.getConfigurations()) {
                r = r.union(gc.getBounds());
            }
        }
        return r;
    }

    private static void writeJpg(BufferedImage src, File out, float quality) throws Exception {
        // JPG can't encode alpha; repaint onto opaque RGB.
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.drawImage(src, 0, 0, Color.BLACK, null);
        } finally {
            g.dispose();
        }

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) throw new IllegalStateException("No JPG writer available");
        ImageWriter writer = writers.next();
        ImageWriteParam p = writer.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(Math.max(0.1f, Math.min(1.0f, quality)));

        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(rgb, null, null), p);
        } finally {
            writer.dispose();
        }
    }
}
