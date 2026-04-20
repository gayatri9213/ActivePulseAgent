package com.activepulse.agent.db;

import com.activepulse.agent.monitor.AppConfigManager;
import com.activepulse.agent.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public final class ScreenshotDao {

    private static final Logger log = LoggerFactory.getLogger(ScreenshotDao.class);

    private ScreenshotDao() {}

    public static void insert(String filePath, long fileSize) {
        String sql = """
            INSERT INTO screenshots
                (username, deviceid, file_path, captured_at, file_size, synced)
            VALUES (?, ?, ?, ?, ?, 0)
        """;
        AppConfigManager cfg = AppConfigManager.getInstance();
        Connection conn = DatabaseManager.getInstance().getConnection();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cfg.getUsernameShort());
            ps.setString(2, cfg.getDeviceId());
            ps.setString(3, filePath);
            ps.setString(4, TimeUtil.nowIST());
            ps.setLong(5, fileSize);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("ScreenshotDao.insert failed: {}", e.getMessage());
        }
    }
}
