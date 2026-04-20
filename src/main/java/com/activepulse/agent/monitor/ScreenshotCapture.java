package com.activepulse.agent.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * ScreenshotCapture — captures the full screen using AWT Robot.
 *
 * Responsibilities:
 *   1. Ensure the screenshot directory exists
 *   2. Capture all connected monitors (virtual screen bounds)
 *   3. Save as compressed JPEG to ~/.activepulse/screenshots/
 *   4. Return a CaptureResult with the file path + size for DB storage
 */
public class ScreenshotCapture {

    private static final Logger log = LoggerFactory.getLogger(ScreenshotCapture.class);

    private static final Path SCREENSHOT_DIR =
            Paths.get(System.getProperty("user.home"), ".activepulse", "screenshots");

    private static final DateTimeFormatter FILE_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /** JPEG quality: 0.0 (worst) – 1.0 (best). 0.7 balances size vs clarity. */
    private static final float JPEG_QUALITY = 0.7f;

    private final Robot robot;

    // ── Singleton ────────────────────────────────────────────────────
    private static volatile ScreenshotCapture instance;

    private ScreenshotCapture(Robot robot) {
        this.robot = robot;
    }

    public static ScreenshotCapture getInstance() {
        if (instance == null) {
            synchronized (ScreenshotCapture.class) {
                if (instance == null) {
                    try {
                        ensureDirectory();
                        instance = new ScreenshotCapture(new Robot());
                    } catch (AWTException e) {
                        throw new RuntimeException("AWT Robot unavailable — headless env?", e);
                    }
                }
            }
        }
        return instance;
    }

    // ─────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────

    /**
     * Captures all monitors, saves to disk.
     * @return CaptureResult with fileName, filePath, fileSizeBytes
     *         or null if capture failed.
     */
    public CaptureResult capture() {
        try {
            // Build virtual screen rectangle (spans ALL connected monitors)
            Rectangle screenRect = getVirtualScreenBounds();

            // Capture
            BufferedImage image = robot.createScreenCapture(screenRect);

            // Build file name: scr_20260313_104500.jpg
            String fileName = "scr_" + LocalDateTime.now().format(FILE_TS) + ".jpg";
            Path   filePath = SCREENSHOT_DIR.resolve(fileName);

            // Save as JPEG with quality control
            saveAsJpeg(image, filePath);

            long fileSize = Files.size(filePath);
            log.info("Screenshot captured → {} ({} KB)",
                    fileName, fileSize / 1024);

            return new CaptureResult(fileName, filePath.toString(), fileSize);

        } catch (Exception e) {
            log.error("Screenshot capture failed: {}", e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Internals
    // ─────────────────────────────────────────────────────────────────

    /**
     * Returns a Rectangle that covers ALL connected monitors.
     * GraphicsEnvironment gives us each device's bounds; we union them all.
     */
    private Rectangle getVirtualScreenBounds() {
        Rectangle bounds = new Rectangle();
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        for (GraphicsDevice device : ge.getScreenDevices()) {
            for (GraphicsConfiguration config : device.getConfigurations()) {
                bounds = bounds.union(config.getBounds());
            }
        }
        // Fallback: use Toolkit screen size if union is empty
        if (bounds.width == 0 || bounds.height == 0) {
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            bounds = new Rectangle(screen);
        }
        return bounds;
    }

    private void saveAsJpeg(BufferedImage image, Path path) throws IOException {
        // Convert to RGB (removes alpha channel — JPEG doesn't support alpha)
        BufferedImage rgb = new BufferedImage(
                image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();

        // Write with quality setting
        try (var out = Files.newOutputStream(path)) {
            var writers = ImageIO.getImageWritersByFormatName("jpeg");
            if (!writers.hasNext()) throw new IOException("No JPEG writer available");

            var writer = writers.next();
            var params = writer.getDefaultWriteParam();
            params.setCompressionMode(
                    javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(JPEG_QUALITY);

            var ios = ImageIO.createImageOutputStream(out);
            writer.setOutput(ios);
            writer.write(null,
                    new javax.imageio.IIOImage(rgb, null, null), params);
            writer.dispose();
        }
    }

    private static void ensureDirectory() {
        if (!Files.exists(SCREENSHOT_DIR)) {
            try {
                Files.createDirectories(SCREENSHOT_DIR);
            } catch (IOException e) {
                throw new RuntimeException("Cannot create screenshot dir: " + SCREENSHOT_DIR, e);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Result record
    // ─────────────────────────────────────────────────────────────────

    public record CaptureResult(String fileName, String filePath, long fileSizeBytes) {}
}
