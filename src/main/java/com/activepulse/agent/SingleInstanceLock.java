package com.activepulse.agent;

import com.activepulse.agent.util.PathResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;

/**
 * Per-user single-instance guard using an exclusive file lock.
 *
 * Because the lock file lives in the user's AppData/Library dir, each AD user
 * gets their own lock — so two different users on the same machine can run
 * independent agent instances (which is correct for machine-wide installs).
 */
public final class SingleInstanceLock {

    private static final Logger log = LoggerFactory.getLogger(SingleInstanceLock.class);

    private RandomAccessFile raf;
    private FileChannel channel;
    private FileLock lock;

    public boolean acquire() {
        Path lockFile = PathResolver.lockFile();
        try {
            raf = new RandomAccessFile(lockFile.toFile(), "rw");
            channel = raf.getChannel();
            lock = channel.tryLock();
            if (lock == null) {
                log.warn("Another agent instance is already running for this user. Exiting.");
                release();
                return false;
            }
            // Write our PID for diagnostics
            try {
                long pid = ProcessHandle.current().pid();
                raf.setLength(0);
                raf.writeBytes(Long.toString(pid));
            } catch (IOException ignored) {}
            log.info("Single-instance lock acquired at {}", lockFile);
            return true;
        } catch (Throwable t) {
            log.error("Failed to acquire lock: {}", t.getMessage());
            release();
            return false;
        }
    }

    public void release() {
        try { if (lock    != null) lock.release(); }  catch (Exception ignored) {}
        try { if (channel != null) channel.close(); } catch (Exception ignored) {}
        try { if (raf     != null) raf.close(); }     catch (Exception ignored) {}
        lock = null; channel = null; raf = null;
    }
}
