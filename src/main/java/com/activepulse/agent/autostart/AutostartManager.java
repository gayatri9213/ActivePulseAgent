package com.activepulse.agent.autostart;

public interface AutostartManager {
    boolean install();
    boolean uninstall();
    boolean isInstalled();
}
