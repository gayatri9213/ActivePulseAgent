package com.activepulse.agent;

import com.activepulse.agent.monitor.UserStatus;
import com.activepulse.agent.monitor.UserStatusTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * SystemTrayManager — minimal tray icon, coloured status dot only.
 * No tooltip. No right-click menu.
 * Icon colour reflects current UserStatus.
 */
public class SystemTrayManager {

    private static final Logger log = LoggerFactory.getLogger(SystemTrayManager.class);

    private TrayIcon   trayIcon;
    private SystemTray tray;

    private static volatile SystemTrayManager instance;
    private SystemTrayManager() {}

    public static SystemTrayManager getInstance() {
        if (instance == null) {
            synchronized (SystemTrayManager.class) {
                if (instance == null) instance = new SystemTrayManager();
            }
        }
        return instance;
    }

    // ─────────────────────────────────────────────────────────────────
    //  Install
    // ─────────────────────────────────────────────────────────────────

    public void install() {
        try {
            Toolkit.getDefaultToolkit();
        } catch (Exception e) {
            log.error("AWT Toolkit init failed: {}", e.getMessage());
            return;
        }

        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            log.warn("System tray not available.");
            return;
        }

        CountDownLatch latch = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            try {
                tray     = SystemTray.getSystemTray();
                trayIcon = new TrayIcon(buildIcon(UserStatus.IDLE));
                trayIcon.setImageAutoSize(true);
                // ── No tooltip ────────────────────────────────────────
                // ── No popup menu ─────────────────────────────────────
                tray.add(trayIcon);
                log.info("System tray icon installed.");
                UserStatusTracker.getInstance().setOnStatusChanged(this::refresh);
            } catch (AWTException e) {
                log.error("Failed to install tray icon: {}", e.getMessage());
            } finally {
                latch.countDown();
            }
        });

        try { latch.await(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public void remove() {
        if (tray != null && trayIcon != null) {
            SwingUtilities.invokeLater(() -> tray.remove(trayIcon));
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Refresh on status change
    // ─────────────────────────────────────────────────────────────────

    public void refresh() {
        if (trayIcon == null) return;
        SwingUtilities.invokeLater(() -> {
            UserStatus s = UserStatusTracker.getInstance().getStatus();
            trayIcon.setImage(buildIcon(s));
        });
    }

    // ─────────────────────────────────────────────────────────────────
    //  Icon — custom PNG or coloured dot fallback
    // ─────────────────────────────────────────────────────────────────

    private Image buildIcon(UserStatus status) {
        BufferedImage base = loadResource("tray-icon.png");
        if (base != null) return overlayDot(base, status);
        return buildDot(status);
    }

    private Image overlayDot(BufferedImage base, UserStatus status) {
        int size = 64;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(base, 0, 0, size, size, null);
        int d = 18, x = size - d - 2, y = size - d - 2;
        g.setColor(Color.WHITE);
        g.fillOval(x - 2, y - 2, d + 4, d + 4);
        g.setColor(dotColor(status));
        g.fillOval(x, y, d, d);
        g.dispose();
        return img;
    }

    private Image buildDot(UserStatus status) {
        int size = 64;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(0, 0, size, size);
        g.setColor(new Color(0, 0, 0, 60));
        g.fillOval(4, 6, size - 6, size - 6);
        g.setColor(dotColor(status));
        g.fillOval(2, 2, size - 6, size - 6);
        g.setColor(new Color(255, 255, 255, 80));
        g.fillOval(10, 8, size / 3, size / 3);
        g.dispose();
        return img;
    }

    private Color dotColor(UserStatus status) {
        return switch (status) {
            case WORKING -> new Color(34,  197, 94);
            case NEUTRAL -> new Color(234, 179, 8);
            case IDLE    -> new Color(156, 163, 175);
            case AWAY    -> new Color(249, 115, 22);
            case STOPPED -> new Color(239, 68,  68);
        };
    }

    private BufferedImage loadResource(String name) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(name)) {
            if (is == null) return null;
            return ImageIO.read(is);
        } catch (Exception e) {
            return null;
        }
    }
}