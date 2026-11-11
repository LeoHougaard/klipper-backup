package dev.leo.voronpanel;

import android.app.*;
import android.content.*;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.*;
import android.os.*;
import android.util.Size;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class MonitorService extends Service {
  static volatile byte[] latest;
  static volatile long frameTime;
  static volatile String state = "Monitoring stopped";
  static volatile boolean running = false;
  static volatile int light = 0;
  private static MonitorService instance;
  HandlerThread worker;
  Handler handler;
  CameraDevice camera;
  CameraCaptureSession session;
  ImageReader reader;
  int appliedLight = -1;
  CameraCharacteristics characteristics;
  PowerManager.WakeLock wake;
  ServerSocket server;
  ExecutorService connections;
  final Set<Socket> activeClients = ConcurrentHashMap.newKeySet();
  final Set<HttpURLConnection> upstreams = ConcurrentHashMap.newKeySet();
  PrinterClient client;

  static synchronized String token(Context context) {
    android.content.SharedPreferences prefs = context.getSharedPreferences("panel", 0);
    String value = prefs.getString("camera_token", "");
    if (value.isEmpty()) {
      byte[] random = new byte[24];
      new SecureRandom().nextBytes(random);
      value =
          android.util.Base64.encodeToString(
              random,
              android.util.Base64.URL_SAFE
                  | android.util.Base64.NO_WRAP
                  | android.util.Base64.NO_PADDING);
      prefs.edit().putString("camera_token", value).apply();
    }
    return value;
  }

  static void setLight(int strength) {
    light = Math.max(0, Math.min(100, strength));
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  public void onCreate() {
    super.onCreate();
    instance = this;
    worker = new HandlerThread("VoronCamera");
    worker.start();
    handler = new Handler(worker.getLooper());
    NotificationManager nm = getSystemService(NotificationManager.class);
    nm.createNotificationChannel(
        new NotificationChannel(
            "camera", "Printer monitoring", NotificationManager.IMPORTANCE_LOW));
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && "stop".equals(intent.getAction())) {
      getSharedPreferences("panel", 0).edit().putBoolean("monitor_on_open", false).apply();
      stopSelf();
      return START_NOT_STICKY;
    }
    client =
        intent != null && intent.hasExtra("endpoint")
            ? new PrinterClient(intent.getStringExtra("endpoint"), intent.getStringExtra("api_key"))
            : new PrinterClient(this);
    if (running) return START_NOT_STICKY;
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    PendingIntent stop =
        PendingIntent.getService(
            this,
            1,
            new Intent(this, MonitorService.class).setAction("stop"),
            PendingIntent.FLAG_IMMUTABLE);
    Notification notification =
        new Notification.Builder(this, "camera")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Voron monitoring")
            .setContentText("Camera and local connection active · no audio")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
            .build();
    try {
      startForeground(
          7, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
      running = true;
      state = "Starting rear camera…";
      latest = null;
      frameTime = 0;
      wake =
          ((PowerManager) getSystemService(POWER_SERVICE))
              .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Voron:monitor");
      wake.acquire();
      token(this);
      startServer();
      handler.post(this::openCamera);
    } catch (Exception e) {
      state = "Monitoring failed: " + MainActivity.message(e);
      stopSelf();
    }
    return START_NOT_STICKY;
  }

  void openCamera() {
    try {
      CameraManager manager = getSystemService(CameraManager.class);
      String id = null;
      for (String candidate : manager.getCameraIdList()) {
        CameraCharacteristics c = manager.getCameraCharacteristics(candidate);
        Integer facing = c.get(CameraCharacteristics.LENS_FACING);
        if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
          id = candidate;
          characteristics = c;
          break;
        }
      }
      if (id == null) throw new IOException("No rear camera");
      StreamConfigurationMap map =
          characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
      Size selected = null;
      for (Size size : map.getOutputSizes(ImageFormat.YUV_420_888)) {
        if (size.getWidth() <= 1280
            && size.getHeight() <= 720
            && (selected == null
                || size.getWidth() * size.getHeight() > selected.getWidth() * selected.getHeight()))
          selected = size;
      }
      if (selected == null) throw new IOException("No supported low-resolution camera stream");
      reader =
          ImageReader.newInstance(
              selected.getWidth(), selected.getHeight(), ImageFormat.YUV_420_888, 2);
      reader.setOnImageAvailableListener(
          source -> {
            try (Image image = source.acquireLatestImage()) {
              if (image == null || SystemClock.elapsedRealtime() - frameTime < 950) return;
              int width = image.getWidth(), height = image.getHeight();
              byte[] nv21 = new byte[width * height * 3 / 2];
              Image.Plane[] planes = image.getPlanes();
              for (int plane = 0; plane < 3; plane++) {
                ByteBuffer buffer = planes[plane].getBuffer();
                int base = buffer.position(),
                    rows = plane == 0 ? height : height / 2,
                    cols = plane == 0 ? width : width / 2;
                int rowStride = planes[plane].getRowStride(),
                    pixelStride = planes[plane].getPixelStride();
                for (int y = 0; y < rows; y++)
                  for (int x = 0; x < cols; x++) {
                    int dest =
                        plane == 0
                            ? y * width + x
                            : width * height + y * width + x * 2 + (plane == 2 ? 0 : 1);
                    nv21[dest] = buffer.get(base + y * rowStride + x * pixelStride);
                  }
              }
              ByteArrayOutputStream out = new ByteArrayOutputStream();
              new YuvImage(nv21, ImageFormat.NV21, width, height, null)
                  .compressToJpeg(new Rect(0, 0, width, height), 75, out);
              latest = out.toByteArray();
              frameTime = SystemClock.elapsedRealtime();
              state = "Live · " + width + " × " + height + " · 1 frame/s";
            } catch (Exception e) {
              state = "Frame error: " + MainActivity.message(e);
            }
          },
          handler);
      manager.openCamera(
          id,
          new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice device) {
              if (!running) {
                device.close();
                return;
              }
              camera = device;
              try {
                camera.createCaptureSession(
                    Collections.singletonList(reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                      @Override
                      public void onConfigured(CameraCaptureSession s) {
                        if (!running) {
                          s.close();
                          return;
                        }
                        session = s;
                        handler.post(capture);
                      }

                      @Override
                      public void onConfigureFailed(CameraCaptureSession s) {
                        fail("Camera session unavailable");
                      }
                    },
                    handler);
              } catch (Exception e) {
                fail(MainActivity.message(e));
              }
            }

            @Override
            public void onDisconnected(CameraDevice c) {
              c.close();
              fail("Camera disconnected");
            }

            @Override
            public void onError(CameraDevice c, int error) {
              c.close();
              fail("Camera error " + error);
            }
          },
          handler);
    } catch (Exception e) {
      fail(MainActivity.message(e));
    }
  }

  final Runnable capture =
      new Runnable() {
        public void run() {
          if (!running || session == null || camera == null) return;
          try {
            if (appliedLight != light) {
              CaptureRequest.Builder continuous =
                  camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
              continuous.addTarget(reader.getSurface());
              applyCameraSettings(continuous);
              session.setRepeatingRequest(continuous.build(), null, handler);
              appliedLight = light;
            }
          } catch (Exception e) {
            state = "Capture failed: " + MainActivity.message(e);
          }
          if (running) handler.postDelayed(this, 1000);
        }
      };

  void applyCameraSettings(CaptureRequest.Builder request) {
    request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
    request.set(
        CaptureRequest.FLASH_MODE,
        light == 0 ? CaptureRequest.FLASH_MODE_OFF : CaptureRequest.FLASH_MODE_TORCH);
    if (light > 0 && Build.VERSION.SDK_INT >= 35) {
      Integer max = characteristics.get(CameraCharacteristics.FLASH_TORCH_STRENGTH_MAX_LEVEL);
      if (max != null && max > 1)
        request.set(
            CaptureRequest.FLASH_STRENGTH_LEVEL, Math.max(1, Math.round(max * light / 100f)));
    }
  }

  void fail(String reason) {
    state = "Monitoring failed: " + reason;
    stopSelf();
  }

  void startServer() throws IOException {
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
    Thread accept =
        new Thread(
            () -> {
              while (running) {
                try {
                  Socket socket = server.accept();
                  socket.setSoTimeout(12000);
                  activeClients.add(socket);
                  try {
                    connections.execute(() -> serve(socket));
                  } catch (RejectedExecutionException e) {
                    activeClients.remove(socket);
                    socket.close();
                  }
                } catch (IOException e) {
                  if (running) state = "Local connection failed";
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
            token(this).getBytes(StandardCharsets.UTF_8),
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
                .put("camera", state)
                .put("light_percent", light)
                .put(
                    "frame_age_ms", frameTime == 0 ? -1 : SystemClock.elapsedRealtime() - frameTime)
                .put("root_required", false)
                .toString());
        return;
      }
      if (method.equals("GET") && path.equals("/camera/snapshot")) {
        byte[] frame = latest;
        if (frame == null || SystemClock.elapsedRealtime() - frameTime > 5000) {
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
      upstreams.add(c);
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
        upstreams.remove(c);
        c.disconnect();
      }
    } catch (Exception ignored) {
      /* Closing the client terminates failed or cancelled uploads; never log tokens. */
    } finally {
      activeClients.remove(socket);
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
    head(output, 200, "multipart/x-mixed-replace; boundary=voron", -1);
    long sent = 0;
    while (running) {
      long now = frameTime;
      byte[] frame = latest;
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
    return "<!doctype html><meta name='viewport'"
               + " content='width=device-width'><title>Voron</title><style>body{background:#111416;color:#edf0ef;font:17px"
               + " system-ui;margin:24px}main{max-width:960px;margin:auto}img{width:100%;max-height:65vh;object-fit:contain;border-radius:12px;background:#1c2226}header{display:flex;justify-content:space-between;align-items:center}button{background:#29332e;color:#edf0ef;border:0;border-radius:9px;padding:14px;margin:8px"
               + " 8px 0"
               + " 0;font:inherit}small{color:#9da7ab}</style><main><header><h2>Voron</h2><span"
               + " id='state'>Connecting…</span></header><img id='camera'><p id='job'></p><p"
               + " id='temps'></p><button id='pause'>Pause</button><button"
               + " id='resume'>Resume</button><button id='cancel'>Cancel"
               + " print</button><p><small>Private local printer connection · no"
               + " audio</small></p><p id='error'></p></main><script>const token=new"
               + " URLSearchParams(location.search).get('token');document.querySelector('#camera').src='/camera/stream?token='+encodeURIComponent(token);async"
               + " function api(path,method='GET'){const r=await"
               + " fetch(path,{method,headers:{'X-Api-Key':token}});if(!r.ok)throw Error('Printer"
               + " response '+r.status);return r.json()}async function refresh(){try{const s=(await"
               + " api('/printer/objects/query?webhooks&print_stats&extruder&heater_bed')).result.status;document.querySelector('#state').textContent=s.webhooks.state;document.querySelector('#job').textContent=(s.print_stats.filename||'No"
               + " active print')+' ·"
               + " '+s.print_stats.state;document.querySelector('#temps').textContent='Nozzle"
               + " '+Math.round(s.extruder.temperature)+'° / '+s.extruder.target+'° · Bed"
               + " '+Math.round(s.heater_bed.temperature)+'° /"
               + " '+s.heater_bed.target+'°';document.querySelector('#error').textContent=''}catch(e){document.querySelector('#state').textContent='Offline';document.querySelector('#error').textContent=e.message}}for(const"
               + " action of"
               + " ['pause','resume','cancel'])document.getElementById(action).onclick=async()=>{if(!confirm(action+'"
               + " print?'))return;try{await api('/printer/print/'+action,'POST');await"
               + " refresh()}catch(e){document.querySelector('#error').textContent=e.message}};refresh();setInterval(refresh,2500)</script>";
  }

  @Override
  public void onDestroy() {
    running = false;
    light = 0;
    handler.removeCallbacksAndMessages(null);
    if (session != null) session.close();
    if (camera != null) camera.close();
    if (reader != null) reader.close();
    try {
      if (server != null) server.close();
    } catch (IOException ignored) {
    }
    for (Socket socket : activeClients)
      try {
        socket.close();
      } catch (IOException ignored) {
      }
    for (HttpURLConnection upstream : upstreams) upstream.disconnect();
    if (connections != null) connections.shutdownNow();
    if (wake != null && wake.isHeld()) wake.release();
    worker.quitSafely();
    latest = null;
    frameTime = 0;
    if (!state.startsWith("Monitoring failed")) state = "Monitoring stopped";
    instance = null;
    super.onDestroy();
  }
}
