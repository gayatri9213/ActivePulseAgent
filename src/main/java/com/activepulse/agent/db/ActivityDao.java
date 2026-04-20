package com.activepulse.agent.db;

import com.activepulse.agent.monitor.AppConfigManager;
import com.activepulse.agent.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public final class ActivityDao {

    private static final Logger log = LoggerFactory.getLogger(ActivityDao.class);

    private ActivityDao() {}

    public static void insert(String startTime, String endTime, String processName,
                              String title, String url, long durationSec, String activityType) {
        String sql = """
            INSERT INTO activity_log
                (username, deviceid, starttime, endtime, processname, title, url,
                 duration, activity_type, recorded_at, synced)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
        """;
        AppConfigManager cfg = AppConfigManager.getInstance();
        Connection conn = DatabaseManager.getInstance().getConnection();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cfg.getUsernameShort());
            ps.setString(2, cfg.getDeviceId());
            ps.setString(3, startTime);
            ps.setString(4, endTime);
            ps.setString(5, processName);
            ps.setString(6, title);
            ps.setString(7, url);
            ps.setLong(8, durationSec);
            ps.setString(9, activityType);
            ps.setString(10, TimeUtil.nowIST());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("ActivityDao.insert failed: {}", e.getMessage());
        }
    }
}
