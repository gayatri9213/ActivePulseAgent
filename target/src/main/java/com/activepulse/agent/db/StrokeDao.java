package com.activepulse.agent.db;

import com.activepulse.agent.monitor.AppConfigManager;
import com.activepulse.agent.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public final class StrokeDao {

    private static final Logger log = LoggerFactory.getLogger(StrokeDao.class);

    private StrokeDao() {}

    public static void insert(int keyboardCount, int mouseCount) {
        String sql = """
            INSERT INTO keyboard_mouse_strokes
                (username, deviceid, recorded_at, keyboardcount, keymousecount, synced)
            VALUES (?, ?, ?, ?, ?, 0)
        """;
        AppConfigManager cfg = AppConfigManager.getInstance();
        Connection conn = DatabaseManager.getInstance().getConnection();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cfg.getUsernameShort());
            ps.setString(2, cfg.getDeviceId());
            ps.setString(3, TimeUtil.nowIST());
            ps.setInt(4, keyboardCount);
            ps.setInt(5, mouseCount);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("StrokeDao.insert failed: {}", e.getMessage());
        }
    }
}
