package com.activepulse.agent.monitor;

import com.activepulse.agent.platform.windows.WindowsUserResolver;
import com.activepulse.agent.util.DeviceIdProvider;
import com.activepulse.agent.util.OsType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AppConfigManager {

    private static final Logger log = LoggerFactory.getLogger(AppConfigManager.class);
    private static volatile AppConfigManager instance;

    private final String username;         // Full qualified: DOMAIN\\user
    private final String usernameShort;    // Just user, no domain
    private final String domain;
    private final String deviceId;
    private final OsType os;
    private final String userSource;

    private AppConfigManager() {
        this.os       = OsType.current();
        this.deviceId = DeviceIdProvider.get();

        if (os == OsType.WINDOWS) {
            WindowsUserResolver.Resolved r = WindowsUserResolver.resolve();
            this.domain        = r.domain();
            this.usernameShort = r.username();
            this.username      = r.qualified();          // DOMAIN\\user
            this.userSource    = r.source();
        } else {
            String sys = System.getProperty("user.name", "unknown");
            this.domain        = "";
            this.usernameShort = sys;
            this.username      = sys;
            this.userSource    = "system-property";
        }

        log.info("╔═══ AppConfig resolved ═══");
        log.info("║  os           = {}", os);
        log.info("║  domain       = '{}'", domain);
        log.info("║  usernameShort= '{}'", usernameShort);
        log.info("║  username     = '{}'  ← this is what gets stored", username);
        log.info("║  userSource   = {}", userSource);
        log.info("║  deviceId     = {}", deviceId);
        log.info("╚══════════════════════════");
    }

    public static AppConfigManager getInstance() {
        if (instance == null) {
            synchronized (AppConfigManager.class) {
                if (instance == null) instance = new AppConfigManager();
            }
        }
        return instance;
    }

    /** Full qualified: DOMAIN\\user — this is what DAOs and SyncManager use. */
    public String getUsername() { return username; }

    /** Just the username part, no domain. */
    public String getUsernameShort() { return usernameShort; }

    /** Just the domain, empty for local users. */
    public String getDomain() { return domain; }

    public String getDeviceId() { return deviceId; }
    public OsType getOs()       { return os; }
    public String getUserSource() { return userSource; }
}