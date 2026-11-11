package dev.leo.voronpanel;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

final class PrinterClient {
  final String base, key;
  private JSONObject settings;

  PrinterClient(Context context) {
    base =
        context.getSharedPreferences("panel", 0).getString("endpoint", "http://10.1.39.216:7125");
    key = context.getSharedPreferences("panel", 0).getString("api_key", "");
  }

  PrinterClient(String endpoint, String apiKey) {
    base = endpoint;
    key = apiKey;
  }

  static String normalize(String text) {
    String value = text.trim();
    if (!value.contains("://")) value = "http://" + value;
    try {
      URI uri = new URI(value);
      if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null
          || (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/")))
        throw new IllegalArgumentException();
      return value.replaceAll("/+$", "");
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "Enter a Moonraker address, for example http://voron.local:7125");
    }
  }

  HttpURLConnection open(String method, String path) throws IOException {
    HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
    connection.setConnectTimeout(3500);
    connection.setReadTimeout(7000);
    connection.setInstanceFollowRedirects(false);
    connection.setRequestMethod(method);
    if (!key.isEmpty()) connection.setRequestProperty("X-Api-Key", key);
    return connection;
  }

  JSONObject request(String method, String path, JSONObject body) throws Exception {
    HttpURLConnection c = open(method, path);
    try {
      if (body != null) {
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream out = c.getOutputStream()) {
          out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
      }
      int code = c.getResponseCode();
      InputStream stream = code >= 400 ? c.getErrorStream() : c.getInputStream();
      String response = read(stream, 2 * 1024 * 1024);
      if (code < 200 || code >= 300)
        throw new IOException(
            "Moonraker " + code + ": " + response.substring(0, Math.min(220, response.length())));
      return new JSONObject(response);
    } finally {
      c.disconnect();
    }
  }

  JSONObject status() throws Exception {
    JSONObject status =
        request(
                "GET",
                "/printer/objects/query?webhooks&print_stats&virtual_sdcard&extruder&heater_bed&toolhead&fan&gcode_move&display_status",
                null)
            .getJSONObject("result")
            .getJSONObject("status");
    JSONObject webhooks = status.optJSONObject("webhooks");
    if (webhooks != null && "ready".equals(webhooks.optString("state"))) {
      if (settings == null)
        settings =
            request("GET", "/printer/objects/query?configfile=settings", null)
                .getJSONObject("result")
                .getJSONObject("status")
                .getJSONObject("configfile");
      status.put("configfile", settings);
    } else settings = null;
    return status;
  }

  void gcode(String script) throws Exception {
    request("POST", "/printer/gcode/script", new JSONObject().put("script", script));
  }

  static String read(InputStream input, int limit) throws IOException {
    if (input == null) return "";
    try (InputStream in = input;
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = in.read(buffer)) >= 0) {
        if (out.size() + count > limit) throw new IOException("Response too large");
        out.write(buffer, 0, count);
      }
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
  }
}
