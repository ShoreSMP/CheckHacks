package me.branduzzo.checkHacks.utils;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

public class WebhookUtil {

    public static void sendResult(Plugin plugin, String webhookUrl, int color, String messageTemplate,
                                  String playerName, String checkerName, String reason,
                                  String hacksChecked, String resultText) {
        if (!isValid(webhookUrl)) return;
        String description = messageTemplate
                .replace("&name&",    playerName)
                .replace("&checker&", checkerName)
                .replace("&reason&",  reason)
                .replace("&hacks&",   hacksChecked)
                .replace("&results&", resultText);
        sendRaw(plugin, webhookUrl, color, description);
    }

    public static void sendRaw(Plugin plugin, String webhookUrl, int color, String description) {
        if (!isValid(webhookUrl)) return;
        String json = "{\"embeds\":[{"
                + "\"title\":\"CheckHacks Report\","
                + "\"description\":\"" + escapeJson(description) + "\","
                + "\"color\":" + color + ","
                + "\"footer\":{\"text\":\"CheckHacks - Sign Translation Exploit\"},"
                + "\"timestamp\":\"" + Instant.now() + "\""
                + "}]}";
        sendJson(plugin, webhookUrl, json);
    }

    private static boolean isValid(String url) {
        return url != null && !url.isBlank() && !url.contains("CHANGE_ME");
    }

    private static void sendJson(Plugin plugin, String webhookUrl, String json) {
        if (plugin == null || !plugin.isEnabled()) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpURLConnection conn =
                        (HttpURLConnection) URI.create(webhookUrl).toURL().openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("User-Agent", "CheckHacks/1.1");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                conn.disconnect();
                if (code < 200 || code >= 300)
                    System.err.println("[CheckHacks] Webhook HTTP " + code);
            } catch (Exception e) {
                System.err.println("[CheckHacks] Webhook error: " + e.getMessage());
            }
        });
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
