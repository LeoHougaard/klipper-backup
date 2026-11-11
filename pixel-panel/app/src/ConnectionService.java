package dev.leo.voronpanel;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.wifi.*;
import android.os.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Owns the printer gateway and optional offline network, independently of camera/UI. */
public final class ConnectionService extends Service {
  public static volatile boolean running;
  public static volatile String state = "Connection stopped";
  public static volatile String hotspotState = "Hotspot off";
  public static volatile String hotspotName = "";
  public static volatile String hotspotPassword = "";
  private PrinterGateway gateway;
  private WifiManager.LocalOnlyHotspotReservation hotspot;
  private boolean hotspotStarting;
  private boolean hotspotDesired;
  private int hotspotGeneration;
  private boolean destroyed;
  private PowerManager.WakeLock wake;

  /** The intent carries the selected destination, including nonpersistent debug fixtures. */
  public static void start(Context context, PrinterClient client) {
    context.startForegroundService(
        new Intent(context, ConnectionService.class)
            .putExtra("endpoint", client.base)
            .putExtra("api_key", client.key));
  }

  public static void stop(Context context) {
    context.stopService(new Intent(context, ConnectionService.class));
  }

  public static void toggleHotspot(Context context) {
    context.startForegroundService(
        new Intent(context, ConnectionService.class).setAction("toggle_hotspot"));
  }

  public static void stopHotspot(Context context) {
    if (running)
      context.startService(new Intent(context, ConnectionService.class).setAction("stop_hotspot"));
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  public void onCreate() {
    super.onCreate();
    getSystemService(NotificationManager.class)
        .createNotificationChannel(
            new NotificationChannel(
                "connection", "Printer connection", NotificationManager.IMPORTANCE_LOW));
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && "stop".equals(intent.getAction())) {
      stopSelf();
      return START_NOT_STICKY;
    }
    try {
      PrinterClient selected =
          intent != null && intent.hasExtra("endpoint")
              ? new PrinterClient(
                  intent.getStringExtra("endpoint"), intent.getStringExtra("api_key"))
              : new PrinterClient(this);
      if (!running) {
        PendingIntent open =
            PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop =
            PendingIntent.getService(
                this,
                2,
                new Intent(this, ConnectionService.class).setAction("stop"),
                PendingIntent.FLAG_IMMUTABLE);
        Notification notification =
            new Notification.Builder(this, "connection")
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("Voron connection")
                .setContentText("Local printer gateway active")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop connection", stop).build())
                .build();
        startForeground(8, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        gateway = new PrinterGateway(this, selected);
        gateway.start();
        wake =
            getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Voron:connection");
        wake.acquire();
        running = true;
        state = "Connection active";
      } else if (intent != null && intent.hasExtra("endpoint")) {
        gateway.updateClient(selected);
      }
      if (intent != null && "toggle_hotspot".equals(intent.getAction())) {
        if (hotspot != null || hotspotDesired) releaseHotspot();
        else if (hotspotStarting) {
          hotspotDesired = true;
          hotspotState = "Starting hotspot";
        } else startHotspot();
      } else if (intent != null && "stop_hotspot".equals(intent.getAction())) releaseHotspot();
    } catch (Exception e) {
      // Do not report exception text: it can contain credentials or an upstream URL.
      state = "Connection unavailable";
      stopSelf();
    }
    return START_NOT_STICKY;
  }

  private void startHotspot() {
    final int generation = ++hotspotGeneration;
    hotspotStarting = true;
    hotspotDesired = true;
    hotspotState = "Starting hotspot";
    WifiManager.LocalOnlyHotspotCallback callback =
        new WifiManager.LocalOnlyHotspotCallback() {
          @Override
          public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
            if (destroyed || generation != hotspotGeneration) {
              reservation.close();
              return;
            }
            hotspotStarting = false;
            if (!hotspotDesired) {
              reservation.close();
              hotspotState = "Hotspot off";
              return;
            }
            hotspot = reservation;
            SoftApConfiguration actual = reservation.getSoftApConfiguration();
            hotspotName = actual.getSsid() == null ? "" : actual.getSsid();
            hotspotPassword = actual.getPassphrase() == null ? "" : actual.getPassphrase();
            hotspotState = "Hotspot active";
          }

          @Override
          public void onStopped() {
            if (generation != hotspotGeneration) return;
            hotspot = null;
            hotspotStarting = false;
            hotspotDesired = false;
            hotspotName = "";
            hotspotPassword = "";
            hotspotState = "Hotspot off";
          }

          @Override
          public void onFailed(int reason) {
            if (generation != hotspotGeneration) return;
            hotspot = null;
            hotspotStarting = false;
            hotspotName = "";
            hotspotPassword = "";
            hotspotState = hotspotDesired ? "Hotspot unavailable (" + reason + ")" : "Hotspot off";
            hotspotDesired = false;
          }
        };
    try {
      WifiManager wifi = getApplicationContext().getSystemService(WifiManager.class);
      if (Build.VERSION.SDK_INT >= 36) {
        SharedPreferences prefs = getSharedPreferences("panel", 0);
        String password = prefs.getString("hotspot_password", "");
        if (password.isEmpty()) {
          password = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
          prefs.edit().putString("hotspot_password", password).apply();
        }
        SoftApConfiguration config =
            new SoftApConfiguration.Builder()
                .setWifiSsid(WifiSsid.fromBytes("Voron".getBytes(StandardCharsets.UTF_8)))
                .setPassphrase(password, SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
                .build();
        wifi.startLocalOnlyHotspotWithConfiguration(config, getMainExecutor(), callback);
      } else wifi.startLocalOnlyHotspot(callback, new Handler(getMainLooper()));
    } catch (Exception e) {
      hotspotStarting = false;
      hotspotDesired = false;
      hotspotState =
          e instanceof SecurityException ? "Hotspot permission required" : "Hotspot unavailable";
    }
  }

  private void releaseHotspot() {
    hotspotDesired = false; // A pending request closes its late reservation in onStarted.
    WifiManager.LocalOnlyHotspotReservation current = hotspot;
    if (current != null) ++hotspotGeneration;
    hotspot = null;
    hotspotName = "";
    hotspotPassword = "";
    hotspotState = "Hotspot off";
    if (current != null) current.close();
  }

  @Override
  public void onDestroy() {
    destroyed = true;
    running = false;
    releaseHotspot();
    if (gateway != null) gateway.close();
    if (wake != null && wake.isHeld()) wake.release();
    if (!state.equals("Connection unavailable")) state = "Connection stopped";
    stopForeground(STOP_FOREGROUND_REMOVE);
    super.onDestroy();
  }
}
