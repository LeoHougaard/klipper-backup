package dev.leo.voronpanel;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

final class PrinterClient {
  final String base, key;
  private JSONObject settings;
  private Set<String> availableObjects;
  private JSONArray commands;

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
    if (path.startsWith("/printer/gcode/script") || path.startsWith("/printer/print/start"))
      connection.setReadTimeout(20000);
    if (path.startsWith("/printer/emergency_stop")) {
      connection.setConnectTimeout(1500);
      connection.setReadTimeout(2500);
    }
    connection.setInstanceFollowRedirects(false);
    connection.setRequestMethod(method);
    if (!key.isEmpty()) connection.setRequestProperty("X-Api-Key", key);
    return connection;
  }

  JSONObject request(String method, String path, JSONObject body) throws Exception {
    HttpURLConnection c = open(method, path);
    try {
      if (path.startsWith("/printer/gcode/script") && body != null) {
        String script = body.optString("script").trim();
        // Full meshes can take minutes. Short moves, heater targets and TESTZ
        // must release their uncertain-response gate promptly if a reply is lost.
        if (script.startsWith("CALIBRATE_BED_TILT")
            || script.startsWith("CALIBRATE_COMPENSATED_BED_MESH")
            || script.equals("ACCEPT")) c.setReadTimeout(900000);
        else if (script.matches("(?s).*\\bG28\\b.*")) c.setReadTimeout(120000);
      }
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

  synchronized JSONObject status() throws Exception {
    JSONObject status;
    try {
      status =
          request(
                  "GET",
                  "/printer/objects/query?webhooks&print_stats&virtual_sdcard&extruder&heater_bed&toolhead&fan&gcode_move&display_status",
                  null)
              .getJSONObject("result")
              .getJSONObject("status");
      if (status.optJSONObject("webhooks") == null
          || status.getJSONObject("webhooks").optString("state").isEmpty())
        throw new IOException("Printer telemetry is unavailable");
    } catch (Exception original) {
      JSONObject server = request("GET", "/server/info", null).getJSONObject("result");
      String engine = server.optString("klippy_state", "");
      if (engine.isEmpty() || engine.equals("ready")) throw original;
      settings = null;
      availableObjects = null;
      commands = null;
      return new JSONObject()
          .put(
              "webhooks",
              new JSONObject().put("state", engine).put("state_message", "Klipper is " + engine));
    }
    JSONObject webhooks = status.optJSONObject("webhooks");
    if (webhooks != null && "ready".equals(webhooks.optString("state"))) {
      if (settings == null)
        settings =
            request("GET", "/printer/objects/query?configfile=settings", null)
                .getJSONObject("result")
                .getJSONObject("status")
                .getJSONObject("configfile");
      status.put("configfile", settings);
      if (availableObjects == null) {
        try {
          Set<String> discovered = new HashSet<>();
          JSONArray objects =
              request("GET", "/printer/objects/list", null)
                  .getJSONObject("result")
                  .getJSONArray("objects");
          for (int i = 0; i < objects.length(); i++) discovered.add(objects.getString(i));
          availableObjects = discovered;
        } catch (Exception ignored) {
        }
      }
      StringBuilder optional = new StringBuilder();
      for (String name : new String[] {"manual_probe", "y_axis_z_offset", "bed_mesh"})
        if (availableObjects != null && availableObjects.contains(name)) {
          if (optional.length() > 0) optional.append('&');
          optional.append(name);
        }
      if (optional.length() > 0) {
        JSONObject extra =
            request("GET", "/printer/objects/query?" + optional, null)
                .getJSONObject("result")
                .getJSONObject("status");
        for (Iterator<String> it = extra.keys(); it.hasNext(); ) {
          String name = it.next();
          status.put(name, extra.get(name));
        }
      }
      if (commands == null) {
        try {
          JSONArray discovered = new JSONArray();
          JSONObject help = request("GET", "/printer/gcode/help", null).getJSONObject("result");
          for (Iterator<String> it = help.keys(); it.hasNext(); ) discovered.put(it.next());
          commands = discovered;
        } catch (Exception ignored) {
        }
      }
      status.put("_commands", commands);
    } else {
      settings = null;
      availableObjects = null;
      commands = null;
    }
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
