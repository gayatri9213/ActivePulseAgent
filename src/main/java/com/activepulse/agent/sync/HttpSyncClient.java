package com.activepulse.agent.sync;

import com.activepulse.agent.util.EnvConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HttpSyncClient — POSTs a JSON string to the server with retry + backoff.
 * All timeout/retry constants are defined here (no longer in SyncConfig).
 */
public class HttpSyncClient {

    private static final Logger log = LoggerFactory.getLogger(HttpSyncClient.class);

    // ── Constants ─────────────────────────────────────────────────────
    private static final int  CONNECT_TIMEOUT_SEC  = 10;
    private static final int  REQUEST_TIMEOUT_SEC  = 30;
    private static final int  MAX_RETRIES          = 3;
    private static final long RETRY_BASE_DELAY_MS  = 2_000; // doubles each attempt

    private final HttpClient httpClient;

    // ── Singleton ────────────────────────────────────────────────────
    private static volatile HttpSyncClient instance;

    private HttpSyncClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEC))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public static HttpSyncClient getInstance() {
        if (instance == null) {
            synchronized (HttpSyncClient.class) {
                if (instance == null) instance = new HttpSyncClient();
            }
        }
        return instance;
    }

    // ─────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────

    public SyncResult post(String syncId, String json) {
        String url    = SyncConfig.getInstance().getServerUrl();
        String apiKey = EnvConfig.get("API_KEY", "");

        int    attempt   = 0;
        long   delayMs   = RETRY_BASE_DELAY_MS;
        String lastError = null;

        while (attempt < MAX_RETRIES) {
            attempt++;
            log.info("HTTP sync attempt {}/{} — syncId={}", attempt, MAX_RETRIES, syncId);
            try {
                HttpRequest.Builder rb = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SEC))
                        .header("Content-Type", "application/json")
                        .header("Accept",       "application/json")
                        .header("X-Agent-Version", EnvConfig.get("AGENT_VERSION", "1.0.0"))
                        .POST(HttpRequest.BodyPublishers.ofString(json));

                if (!apiKey.isBlank() && !apiKey.equals("YOUR_API_KEY_HERE")) {
                    rb.header("Authorization", "Bearer " + apiKey);
                }

                HttpResponse<String> response = httpClient.send(
                        rb.build(), HttpResponse.BodyHandlers.ofString());

                int status = response.statusCode();
                log.info("HTTP {} for syncId={}", status, syncId);

                if (status >= 200 && status < 300)
                    return SyncResult.success(status, response.body());

                if (status >= 400 && status < 500) {
                    log.warn("HTTP {} — not retrying: {}", status,
                            truncate(response.body(), 200));
                    return SyncResult.failure(status, "HTTP " + status);
                }

                lastError = "HTTP " + status;
                log.warn("HTTP {} — will retry.", status);

            } catch (java.net.ConnectException e) {
                lastError = "Connection refused: " + e.getMessage();
                log.warn("Connection failed (attempt {}/{}): {}", attempt, MAX_RETRIES, lastError);
            } catch (java.net.http.HttpTimeoutException e) {
                lastError = "Timeout: " + e.getMessage();
                log.warn("Request timed out (attempt {}/{})", attempt, MAX_RETRIES);
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.warn("HTTP error (attempt {}/{}): {}", attempt, MAX_RETRIES, lastError);
            }

            if (attempt < MAX_RETRIES) {
                log.info("Waiting {}ms before retry...", delayMs);
                try { Thread.sleep(delayMs); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                delayMs *= 2;
            }
        }

        log.error("All {} attempts failed for syncId={} — {}", MAX_RETRIES, syncId, lastError);
        return SyncResult.failure(0, lastError);
    }

    // ─────────────────────────────────────────────────────────────────
    //  Result
    // ─────────────────────────────────────────────────────────────────

    public record SyncResult(boolean success, int statusCode, String message) {

        public static SyncResult success(int code, String body) {
            return new SyncResult(true, code, body);
        }

        public static SyncResult failure(int code, String error) {
            return new SyncResult(false, code, error);
        }

        @Override
        public String toString() {
            return success
                    ? "SUCCESS(HTTP " + statusCode + ")"
                    : "FAILED(HTTP " + statusCode + " — " + message + ")";
        }
    }

    private String truncate(String s, int max) {
        return (s == null || s.length() <= max) ? s : s.substring(0, max) + "...";
    }
}