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
import java.nio.ByteBuffer;
import java.security.*;
import java.util.*;

public final class MonitorService extends Service {
  static volatile byte[] latest;
  static volatile long frameTime;
  static volatile String state = "Monitoring stopped";
  static volatile boolean running = false;
  static volatile int light = 0;
  HandlerThread worker;
  Handler handler;
  CameraDevice camera;
  CameraCaptureSession session;
  ImageReader reader;
  int appliedLight = -1;
  CameraCharacteristics characteristics;
  PowerManager.WakeLock wake;

  public static synchronized String token(Context context) {
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

  static boolean hasFreshFrame() {
    return running
        && latest != null
        && frameTime != 0
        && SystemClock.elapsedRealtime() - frameTime <= 5000;
  }

  static String cameraState() {
    if (running && frameTime != 0 && !hasFreshFrame()) return "Camera frame stale";
    return state;
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
            .setContentText("Camera active, no audio")
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
              if (!running || image == null || SystemClock.elapsedRealtime() - frameTime < 950)
                return;
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
              if (!running) return;
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

  @Override
  public void onDestroy() {
    running = false;
    light = 0;
    handler.removeCallbacksAndMessages(null);
    if (session != null) session.close();
    if (camera != null) camera.close();
    if (reader != null) reader.close();
    if (wake != null && wake.isHeld()) wake.release();
    worker.quitSafely();
    latest = null;
    frameTime = 0;
    if (!state.startsWith("Monitoring failed")) state = "Monitoring stopped";
    stopForeground(STOP_FOREGROUND_REMOVE);
    super.onDestroy();
  }
}
