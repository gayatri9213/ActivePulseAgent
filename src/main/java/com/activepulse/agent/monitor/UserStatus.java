package com.activepulse.agent.monitor;

/**
 * UserStatus — activity state of the monitored user.
 *
 * WORKING  — sustained keyboard/mouse activity above threshold
 * NEUTRAL  — light activity (some input but below working threshold)
 * IDLE     — no input for IDLE_TIMEOUT minutes
 * AWAY     — system locked (Win+L) or no input for AWAY_TIMEOUT minutes
 * STOPPED  — agent shutting down
 */
public enum UserStatus {

    WORKING ("Working",  "🟢"),  // green
    NEUTRAL ("Neutral",  "⚪"),  // gray/neutral (closest match)
    IDLE    ("Idle",     "⚫"),  // darker muted state
    AWAY    ("Away",     "🟠"),  // amber/orange
    STOPPED ("Stopped",  "🔴");  // red

    private final String label;
    private final String icon;

    UserStatus(String label, String icon) {
        this.label = label;
        this.icon  = icon;
    }

    public String getLabel() { return label; }
    public String getIcon()  { return icon;  }

    @Override
    public String toString() { return icon + " " + label; }
}