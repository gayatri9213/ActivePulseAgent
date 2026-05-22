package com.activepulse.agent.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;

public class MachineInfo {

    private static final Logger log =
            LoggerFactory.getLogger(MachineInfo.class);

    public static String getLocalIp() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            log.warn("Failed to get local IP: {}", e.getMessage());
            return "Unknown";
        }
    }

    public static String getPublicIp() {
        try {

            URL url = new URL("https://api.ipify.org");

            HttpURLConnection conn =
                    (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    conn.getInputStream()
                            )
                    );

            return reader.readLine();

        } catch (Exception e) {
            log.warn("Failed to get public IP: {}", e.getMessage());
            return "Unknown";
        }
    }

    public static String getLocation() {
        try {

            URL url =
                    new URL("http://ip-api.com/json");

            HttpURLConnection conn =
                    (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    conn.getInputStream()
                            )
                    );

            StringBuilder response =
                    new StringBuilder();

            String line;

            while ((line = reader.readLine()) != null) {
                response.append(line);
            }

            return response.toString();

        } catch (Exception e) {
            log.warn("Failed to get location: {}", e.getMessage());
            return "Unknown";
        }
    }
}