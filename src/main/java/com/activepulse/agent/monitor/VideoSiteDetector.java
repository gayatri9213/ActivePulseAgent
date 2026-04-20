package com.activepulse.agent.monitor;

import java.util.Set;

/**
 * VideoSiteDetector — checks if the current domain is a known video
 * streaming site. Used to extend idle timeout while watching videos.
 *
 * When the user is on a video site and hasn't moved the mouse/keyboard,
 * we keep status as WORKING instead of flipping to IDLE.
 *
 * Add more domains to VIDEO_DOMAINS as needed.
 */
public class VideoSiteDetector {

    private static final Set<String> VIDEO_DOMAINS = Set.of(
            // ── Video streaming ───────────────────────────────────────────
            "youtube.com",
            "youtu.be",
            "netflix.com",
            "primevideo.com",
            "hotstar.com",
            "disneyplus.com",
            "hulu.com",
            "vimeo.com",
            "twitch.tv",
            "dailymotion.com",
            "sonyliv.com",
            "zee5.com",
            "jiocinema.com",
            "mxplayer.in",
            "erosnow.com",
            "altbalaji.com",

            // ── Video calls / meetings ─────────────────────────────────────
            "meet.google.com",
            "zoom.us",
            "teams.microsoft.com",
            "webex.com",
            "whereby.com",
            "gotomeeting.com",

            // ── Online learning (video lectures) ──────────────────────────
            "udemy.com",
            "coursera.org",
            "edx.org",
            "pluralsight.com",
            "linkedin.com/learning"
    );

    private static final VideoSiteDetector INSTANCE = new VideoSiteDetector();
    private VideoSiteDetector() {}

    public static VideoSiteDetector getInstance() { return INSTANCE; }

    /**
     * Returns true if the given domain is a known video/streaming site.
     * Handles subdomains e.g. "www.youtube.com" → matches "youtube.com"
     */
    public boolean isVideoSite(String domain) {
        if (domain == null || domain.isBlank()) return false;
        String d = domain.toLowerCase().trim();
        // Strip www. prefix
        if (d.startsWith("www.")) d = d.substring(4);
        // Direct match
        if (VIDEO_DOMAINS.contains(d)) return true;
        // Suffix match — catches subdomains like studio.youtube.com
        for (String v : VIDEO_DOMAINS) {
            if (d.endsWith("." + v) || d.equals(v)) return true;
        }
        return false;
    }
}