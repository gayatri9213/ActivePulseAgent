package com.activepulse.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * InstanceLock — dual-layer single instance guard.
 *
 * Layer 1: File lock  (~/.activepulse/agent.lock)
 *   - Acquired via NIO FileLock (OS-level, released on process death)
 *   - Works even if two processes start within milliseconds of each other
 *
 * Layer 2: Socket lock (port 47892)
 *   - Secondary check, useful for detecting already-running instances
 *
 * Both layers are released automatically when the JVM exits.
 */
public class InstanceLock {

    private static final Logger log = LoggerFactory.getLogger(InstanceLock.class);

    private static final Path   LOCK_FILE = Paths.get(
            System.getProperty("user.home"), ".activepulse", "agent.lock");
    private static final int    LOCK_PORT = 47892;

    private static FileChannel  fileChannel;
    private static FileLock     fileLock;
    private static ServerSocket socketLock;

    private InstanceLock() {}

    /**
     * Must be called as the VERY FIRST line in main() before any other code.
     * @return true  = this is the only instance, safe to continue
     *         false = another instance is running, caller must System.exit()
     */
    public static boolean acquire() {
        // ── Layer 1: File lock ────────────────────────────────────────
        try {
            Files.createDirectories(LOCK_FILE.getParent());
            fileChannel = FileChannel.open(LOCK_FILE,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);

            // tryLock() returns null immediately if another process holds it
            fileLock = fileChannel.tryLock();

            if (fileLock == null) {
                log.warn("Another ActivePulse instance holds the file lock — exiting.");
                closeQuietly();
                return false;
            }

        } catch (IOException e) {
            log.warn("File lock failed: {} — trying socket lock only.", e.getMessage());
        }

        // ── Layer 2: Socket lock ──────────────────────────────────────
        try {
            socketLock = new ServerSocket(LOCK_PORT, 1,
                    InetAddress.getByName("127.0.0.1"));
            socketLock.setReuseAddress(false);
            log.info("Instance lock acquired (file + socket).");
        } catch (BindException e) {
            log.warn("Socket port {} already bound — another instance running.", LOCK_PORT);
            closeQuietly();
            return false;
        } catch (IOException e) {
            log.warn("Socket lock failed: {} — continuing with file lock only.", e.getMessage());
        }

        // Release both locks on JVM exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            closeQuietly();
            // Delete lock file on clean exit
            try { Files.deleteIfExists(LOCK_FILE); } catch (IOException ignored) {}
        }, "instance-lock-release"));

        return true;
    }

    public static void release() {
        closeQuietly();
        try { Files.deleteIfExists(LOCK_FILE); } catch (IOException ignored) {}
    }

    private static void closeQuietly() {
        try { if (fileLock    != null && fileLock.isValid()) fileLock.release(); }
        catch (IOException ignored) {}
        try { if (fileChannel != null && fileChannel.isOpen()) fileChannel.close(); }
        catch (IOException ignored) {}
        try { if (socketLock  != null && !socketLock.isClosed()) socketLock.close(); }
        catch (IOException ignored) {}
    }
}