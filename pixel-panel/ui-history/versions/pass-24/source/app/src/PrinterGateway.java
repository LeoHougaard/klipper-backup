package dev.leo.voronpanel;

import android.content.Context;
import android.os.SystemClock;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Authenticated fixed-destination gateway, independent of the camera lifetime. */
final class PrinterGateway implements AutoCloseable {
  private final Context context;
  private volatile PrinterClient client;
  private volatile boolean open;
  private ServerSocket server;
  private ExecutorService connections;
  private final Object lifecycle = new Object();
  private final Set<Socket> activeClients = new HashSet<>();
  private final Set<HttpURLConnection> upstreams = new HashSet<>();

  PrinterGateway(Context context, PrinterClient client) {
    this.context = context.getApplicationContext();
    this.client = client;
  }

  void updateClient(PrinterClient client) {
    this.client = client;
  }

  void start() throws IOException {
    server = new ServerSocket();
    server.setReuseAddress(true);
    server.bind(new InetSocketAddress(8765));
    connections =
        new ThreadPoolExecutor(
            0,
            8,
            20,
            TimeUnit.SECONDS,
            new SynchronousQueue<Runnable>(),
            new ThreadPoolExecutor.AbortPolicy());
    open = true;
    Thread accept =
        new Thread(
            () -> {
              while (open) {
                try {
                  Socket socket = server.accept();
                  socket.setSoTimeout(12000);
                  synchronized (lifecycle) {
                    if (!open) {
                      socket.close();
                      continue;
                    }
                    activeClients.add(socket);
                  }
                  try {
                    connections.execute(() -> serve(socket));
                  } catch (RejectedExecutionException e) {
                    synchronized (lifecycle) {
                      activeClients.remove(socket);
                    }
                    socket.close();
                  }
                } catch (IOException e) {
                  if (open) ConnectionService.state = "Local connection failed";
                }
              }
            },
            "VoronHTTP");
    accept.setDaemon(true);
    accept.start();
  }

  String line(InputStream input) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int i = 0; i < 8192; i++) {
      int b = input.read();
      if (b < 0) return out.size() == 0 ? null : out.toString("UTF-8");
      if (b == 10) return out.toString("UTF-8").replace("\r", "");
      out.write(b);
    }
    throw new IOException("Header too large");
  }

  boolean authentic(String supplied) {
    return supplied != null
        && MessageDigest.isEqual(
            MonitorService.token(context).getBytes(StandardCharsets.UTF_8),
            supplied.getBytes(StandardCharsets.UTF_8));
  }

  String queryToken(String query) {
    if (query == null) return null;
    for (String pair : query.split("&")) {
      String[] item = pair.split("=", 2);
      if (item.length == 2 && (item[0].equals("token") || item[0].equals("apikey")))
        try {
          return URLDecoder.decode(item[1], "UTF-8");
        } catch (Exception ignored) {
        }
    }
    return null;
  }

  void serve(Socket socket) {
    try (Socket s = socket) {
      InputStream input = new BufferedInputStream(s.getInputStream());
      OutputStream output = s.getOutputStream();
      String first = line(input);
      if (first == null) return;
      String[] request = first.split(" ");
      if (request.length != 3) return;
      String method = request[0];
      URI uri = new URI(request[1]);
      if (!request[1].startsWith("/") || uri.getHost() != null) {
        reply(output, 400, "text/plain", "Invalid path");
        return;
      }
      HashMap<String, String> headers = new HashMap<>();
      String header;
      int headerCount = 0;
      while ((header = line(input)) != null && !header.isEmpty()) {
        if (++headerCount > 64) throw new IOException("Too many headers");
        int colon = header.indexOf(':');
        if (colon > 0)
          headers.put(
              header.substring(0, colon).toLowerCase(Locale.US),
              header.substring(colon + 1).trim());
      }
      if (!authentic(headers.get("x-api-key")) && !authentic(queryToken(uri.getRawQuery()))) {
        reply(output, 401, "text/plain", "Camera/API key required");
        return;
      }
      if (headers.containsKey("transfer-encoding")) {
        reply(output, 411, "text/plain", "Content-Length required");
        return;
      }
      String path = uri.getPath();
      if (method.equals("GET") && path.equals("/health")) {
        reply(
            output,
            200,
            "application/json",
            new JSONObject()
                .put("camera", MonitorService.cameraState())
                .put("light_percent", MonitorService.light)
                .put(
                    "frame_age_ms",
                    MonitorService.frameTime == 0
                        ? -1
                        : SystemClock.elapsedRealtime() - MonitorService.frameTime)
                .put("root_required", false)
                .toString());
        return;
      }
      if (method.equals("GET") && path.equals("/camera/snapshot")) {
        byte[] frame = MonitorService.latest;
        if (frame == null || !MonitorService.hasFreshFrame()) {
          reply(output, 503, "text/plain", "Camera frame unavailable");
          return;
        }
        head(output, 200, "image/jpeg", frame.length);
        output.write(frame);
        return;
      }
      if (method.equals("GET") && path.equals("/camera/stream")) {
        stream(output);
        return;
      }
      if (method.equals("GET") && path.equals("/")) {
        reply(output, 200, "text/html; charset=utf-8", dashboard());
        return;
      }
      // Authenticated HTTP gateway for Orca's native Moonraker host. No open proxy or arbitrary
      // upstream.
      if (!(path.startsWith("/printer/")
          || path.startsWith("/server/")
          || path.startsWith("/machine/")
          || path.startsWith("/api/"))) {
        reply(output, 404, "text/plain", "Not found");
        return;
      }
      if (!(method.equals("GET") || method.equals("POST") || method.equals("DELETE"))) {
        reply(output, 405, "text/plain", "Method not supported");
        return;
      }
      long length = Long.parseLong(headers.getOrDefault("content-length", "0"));
      if (length < 0 || length > 512L * 1024 * 1024) {
        reply(output, 413, "text/plain", "Upload limit: 512 MB");
        return;
      }
      // Strip gateway credentials before forwarding. Printer auth is stored separately.
      String query = uri.getRawQuery();
      ArrayList<String> args = new ArrayList<>();
      if (query != null)
        for (String arg : query.split("&"))
          if (!arg.startsWith("token=") && !arg.startsWith("apikey=")) args.add(arg);
      HttpURLConnection c =
          client.open(
              method, uri.getRawPath() + (args.isEmpty() ? "" : "?" + String.join("&", args)));
      c.setReadTimeout(120000);
      synchronized (lifecycle) {
        if (!open) {
          c.disconnect();
          return;
        }
        upstreams.add(c);
      }
      try {
        if (length > 0) {
          c.setDoOutput(true);
          c.setFixedLengthStreamingMode(length);
          c.setRequestProperty(
              "Content-Type", headers.getOrDefault("content-type", "application/octet-stream"));
          try (OutputStream target = c.getOutputStream()) {
            copy(input, target, length);
          }
        }
        int code = c.getResponseCode();
        String type = c.getContentType();
        head(output, code, type == null ? "application/json" : type, -1);
        InputStream upstream = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (upstream != null)
          try (InputStream source = upstream) {
            copy(source, output, -1);
          }
      } finally {
        synchronized (lifecycle) {
          upstreams.remove(c);
        }
        c.disconnect();
      }
    } catch (Exception ignored) {
      /* Closing the client terminates failed or cancelled uploads; never log tokens. */
    } finally {
      synchronized (lifecycle) {
        activeClients.remove(socket);
      }
    }
  }

  static void copy(InputStream in, OutputStream out, long limit) throws IOException {
    byte[] bytes = new byte[32768];
    long remaining = limit;
    while (limit < 0 || remaining > 0) {
      int n = in.read(bytes, 0, limit < 0 ? bytes.length : (int) Math.min(remaining, bytes.length));
      if (n < 0) {
        if (limit >= 0 && remaining > 0) throw new EOFException("Incomplete upload");
        break;
      }
      out.write(bytes, 0, n);
      if (limit >= 0) remaining -= n;
    }
  }

  static void head(OutputStream out, int code, String type, long length) throws IOException {
    String header =
        "HTTP/1.1 "
            + code
            + " Response\r\nContent-Type: "
            + type.replace("\r", "").replace("\n", "")
            + "\r\n"
            + "Cache-Control: no-store\r\n"
            + "Referrer-Policy: no-referrer\r\n"
            + "X-Content-Type-Options: nosniff\r\n"
            + "Connection: close\r\n"
            + (length >= 0 ? "Content-Length: " + length + "\r\n" : "")
            + "\r\n";
    out.write(header.getBytes(StandardCharsets.UTF_8));
  }

  static void reply(OutputStream out, int code, String type, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    head(out, code, type, bytes.length);
    out.write(bytes);
  }

  void stream(OutputStream output) throws IOException {
    if (!MonitorService.hasFreshFrame()) {
      reply(output, 503, "text/plain", "Camera frame unavailable");
      return;
    }
    head(output, 200, "multipart/x-mixed-replace; boundary=voron", -1);
    long sent = 0;
    while (open) {
      if (!MonitorService.hasFreshFrame()) return;
      long now = MonitorService.frameTime;
      byte[] frame = MonitorService.latest;
      if (frame != null && now != sent && SystemClock.elapsedRealtime() - now < 5000) {
        output.write(
            ("--voron\r\nContent-Type: image/jpeg\r\nContent-Length: " + frame.length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        output.write(frame);
        output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        output.flush();
        sent = now;
      }
      try {
        Thread.sleep(150);
      } catch (InterruptedException e) {
        return;
      }
    }
  }

  String dashboard() {
    try (InputStream input = context.getAssets().open("device.html");
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      copy(input, output, -1);
      return new String(output.toByteArray(), StandardCharsets.UTF_8);
    } catch (IOException ignored) {
      return "<!doctype html><html lang='en'><meta charset='utf-8'>"
          + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
          + "<title>Voron</title><body style='background:#"
          + Integer.toHexString(PanelUi.BG).substring(2)
          + ";color:#"
          + Integer.toHexString(PanelUi.TEXT).substring(2)
          + ";"
          + "font:16px system-ui;padding:24px'><h1>Device interface unavailable</h1>"
          + "<p>Reinstall the Voron app to restore the Device interface.</p>"
          + "<p>The authenticated printer gateway remains available to Orca.</p></body></html>";
    }
  }

  @Override
  public void close() {
    Socket[] clients;
    HttpURLConnection[] targets;
    synchronized (lifecycle) {
      open = false;
      clients = activeClients.toArray(new Socket[0]);
      targets = upstreams.toArray(new HttpURLConnection[0]);
      activeClients.clear();
      upstreams.clear();
    }
    try {
      if (server != null) server.close();
    } catch (IOException ignored) {
    }
    for (Socket socket : clients)
      try {
        socket.close();
      } catch (IOException ignored) {
      }
    for (HttpURLConnection upstream : targets) upstream.disconnect();
    if (connections != null) connections.shutdownNow();
  }
}
