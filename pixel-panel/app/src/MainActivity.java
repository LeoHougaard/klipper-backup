package dev.leo.voronpanel;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.*;
import android.net.wifi.*;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class MainActivity extends Activity {
  static final int BG = 0xff111416,
      CARD = 0xff1c2226,
      TEXT = 0xffedf0ef,
      MUTED = 0xff9da7ab,
      ACCENT = 0xffa4bfac;
  final Handler ui = new Handler(Looper.getMainLooper());
  final ExecutorService io = Executors.newSingleThreadExecutor();
  LinearLayout content, nav;
  TextView connection, nozzle, bed, job, progress, elapsed, axes, cameraState;
  ProgressBar progressBar;
  String page = "Print", failure = "Connecting…";
  JSONObject status = new JSONObject();
  long statusTime = 0;
  boolean live = false, resumed = false, polling = false, commandBusy = false;
  PrinterClient client;
  WifiManager.LocalOnlyHotspotReservation hotspot;
  String hotspotInfo = "";
  final Runnable poll =
      new Runnable() {
        public void run() {
          if (!resumed) return;
          if (!polling) {
            polling = true;
            io.execute(() -> {
              try {
                JSONObject next = client.status();
                ui.post(() -> {
                  status = next;
                  statusTime = SystemClock.elapsedRealtime();
                  live = true;
                  failure = "";
                  updateValues();
                });
              } catch (Exception e) {
                ui.post(() -> {
                  live = false;
                  failure = message(e);
                  updateValues();
                });
              } finally {
                ui.post(() -> polling = false);
              }
            });
          }
          ui.postDelayed(this, 2000);
        }
      };

  @Override
  public void onCreate(Bundle bundle) {
    super.onCreate(bundle);
    client = new PrinterClient(this);
    // Test-only override is never persisted; development APK only.
    if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        && getIntent().hasExtra("test_endpoint"))
      client =
          new PrinterClient(
              PrinterClient.normalize(getIntent().getStringExtra("test_endpoint")), "");
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    getWindow().setNavigationBarColor(BG);
    getWindow().setStatusBarColor(BG);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
    getWindow()
        .getDecorView()
        .setOnApplyWindowInsetsListener((v, insets) -> {
          android.graphics.Insets safe =
              insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
          v.setPadding(safe.left, safe.top, safe.right, safe.bottom);
          return insets;
        });
    show("Print");
    if (Build.VERSION.SDK_INT >= 37
        && checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK)
            != PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {Manifest.permission.ACCESS_LOCAL_NETWORK}, 12);
  }

  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    ui.removeCallbacks(poll);
    ui.post(poll);
    if (getSharedPreferences("panel", 0).getBoolean("monitor_on_open", false)
        && !MonitorService.running
        && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
      startMonitor();
  }

  @Override
  protected void onPause() {
    super.onPause();
    resumed = false;
    ui.removeCallbacks(poll);
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    ui.removeCallbacksAndMessages(null);
    io.shutdownNow();
    if (hotspot != null) hotspot.close();
  }

  int dp(float value) {
    return (int) (value * getResources().getDisplayMetrics().density + .5f);
  }

  GradientDrawable background(int color) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(14));
    return d;
  }

  LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(1);
    return l;
  }

  LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(0);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  TextView text(String value, int size, int color) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setGravity(Gravity.CENTER_VERTICAL);
    return t;
  }

  TextView title(String value) {
    TextView t = text(value, 25, TEXT);
    t.setTypeface(null, Typeface.BOLD);
    return t;
  }

  Button button(String name, Runnable action) {
    Button b = new Button(this);
    b.setText(name);
    b.setAllCaps(false);
    b.setTextSize(15);
    b.setTextColor(TEXT);
    b.setBackground(background(CARD));
    b.setMinHeight(dp(48));
    b.setPadding(dp(14), dp(6), dp(14), dp(6));
    b.setOnClickListener(v -> action.run());
    return b;
  }

  void addButton(LinearLayout parent, String name, Runnable action) {
    Button b = button(name, action);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(50));
    p.setMargins(0, dp(6), 0, 0);
    parent.addView(b, p);
  }

  void equalButton(LinearLayout parent, String name, Runnable action) {
    Button b = button(name, action);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1);
    p.setMargins(dp(3), dp(4), dp(3), 0);
    parent.addView(b, p);
  }

  LinearLayout card(LinearLayout parent) {
    LinearLayout l = column();
    l.setPadding(dp(16), dp(12), dp(16), dp(12));
    l.setBackground(background(CARD));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.setMargins(0, dp(8), 0, 0);
    parent.addView(l, p);
    return l;
  }

  void hint(LinearLayout parent, String value) {
    TextView t = text(value, 14, MUTED);
    t.setPadding(0, dp(8), 0, dp(6));
    parent.addView(t);
  }

  void show(String selected) {
    page = selected;
    nozzle = bed = job = progress = elapsed = axes = cameraState = null;
    progressBar = null;
    LinearLayout root = row();
    root.setBackgroundColor(BG);
    root.setPadding(dp(10), dp(8), dp(14), dp(8));
    nav = column();
    nav.setPadding(0, 0, dp(12), 0);
    TextView brand = text("VORON", 16, ACCENT);
    brand.setTypeface(null, Typeface.BOLD);
    brand.setPadding(dp(8), dp(8), 0, dp(12));
    nav.addView(brand);
    for (String name : new String[] {"Print", "Control", "Files", "Camera", "Settings"}) {
      Button b = button(name, () -> show(name));
      if (name.equals(page)) {
        b.setTextColor(BG);
        b.setBackground(background(ACCENT));
      }
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, 0, 1);
      p.setMargins(0, dp(4), 0, 0);
      nav.addView(b, p);
    }
    root.addView(nav, new LinearLayout.LayoutParams(dp(110), -1));
    LinearLayout main = column();
    LinearLayout header = row();
    header.addView(title(page), new LinearLayout.LayoutParams(0, dp(38), 1));
    connection = text("Connecting…", 13, MUTED);
    connection.setMaxLines(1);
    connection.setMaxWidth(dp(300));
    connection.setOnClickListener(v ->
        new AlertDialog.Builder(this)
            .setTitle("Printer connection")
            .setMessage(client.base + "\n\n" + (fresh() ? "Connected" : failure))
            .setNegativeButton("Done", null)
            .setPositiveButton("Settings", (d, w) -> show("Settings"))
            .show());
    header.addView(connection);
    main.addView(header);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    content = column();
    scroll.addView(content);
    main.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    root.addView(main, new LinearLayout.LayoutParams(0, -1, 1));
    setContentView(root);
    switch (page) {
      case "Print":
        printPage();
        break;
      case "Control":
        controlPage();
        break;
      case "Files":
        filesPage();
        break;
      case "Camera":
        cameraPage();
        break;
      default:
        settingsPage();
    }
    updateValues();
  }

  void printPage() {
    LinearLayout cols = row(), left = column(), right = column();
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1.45f);
    lp.setMargins(0, 0, dp(12), 0);
    cols.addView(left, lp);
    cols.addView(right, new LinearLayout.LayoutParams(0, -1, 1));
    content.addView(cols);
    LinearLayout current = card(left);
    hint(current, "CURRENT PRINT");
    job = text("No active print", 23, TEXT);
    job.setMaxLines(2);
    current.addView(job);
    progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
    current.addView(progressBar, new LinearLayout.LayoutParams(-1, dp(20)));
    progress = text("Ready for your next plate", 15, ACCENT);
    current.addView(progress);
    elapsed = text("", 14, MUTED);
    current.addView(elapsed);
    LinearLayout actions = row();
    equalButton(actions, "Pause / Resume", () -> pauseResume());
    equalButton(
        actions,
        "Cancel",
        () -> {
          if (!guard(false)) return;
          if (idle()) {
            toast("No active print");
            return;
          }
          confirm(
              "Cancel print?",
              "The current print will stop.",
              () -> postAction("/printer/print/cancel", null, false));
        });
    left.addView(actions);
    addButton(left, "Tune print", this::tunePrint);
    nozzle = temperatureCard(right, "NOZZLE", () -> temperature("extruder"));
    bed = temperatureCard(right, "BED", () -> temperature("heater_bed"));
    LinearLayout quick = row();
    equalButton(quick, "Preheat", this::preheat);
    equalButton(quick, "Cool down", () -> command("TURN_OFF_HEATERS", false));
    right.addView(quick);
  }

  TextView temperatureCard(LinearLayout parent, String label, Runnable click) {
    LinearLayout c = card(parent);
    hint(c, label);
    TextView value = text("—", 27, TEXT);
    c.addView(value);
    c.setOnClickListener(v -> click.run());
    return value;
  }

  JSONObject object(String name) {
    return status.optJSONObject(name) == null ? new JSONObject() : status.optJSONObject(name);
  }

  String state() {
    return object("print_stats").optString("state", "unknown");
  }

  boolean fresh() {
    return live && SystemClock.elapsedRealtime() - statusTime < 6000;
  }

  boolean ready() {
    return fresh() && object("webhooks").optString("state").equals("ready");
  }

  boolean idle() {
    return !state().equals("printing") && !state().equals("paused");
  }

  boolean guard(boolean requireIdle) {
    if (!ready()) {
      toast("Connect to a ready printer first");
      return false;
    }
    if (requireIdle && !idle()) {
      toast("Available when the print has finished");
      return false;
    }
    return true;
  }

  void updateValues() {
    if (connection == null) return;
    String printerState = object("webhooks").optString("state", "connecting");
    connection.setText(
        fresh() ? "●  " + printerState + "  ·  " + state() : "○  Offline · connection details");
    connection.setTextColor(fresh() ? ACCENT : MUTED);
    if (nozzle != null) nozzle.setText(temperatureText("extruder"));
    if (bed != null) bed.setText(temperatureText("heater_bed"));
    if (job != null) {
      String file = object("print_stats").optString("filename", "");
      job.setText(
          !fresh()
              ? "Printer unavailable"
              : file.isEmpty()
                  ? "Ready for your next plate"
                  : file.substring(file.lastIndexOf('/') + 1));
      double fraction = object("virtual_sdcard").optDouble("progress", 0);
      progressBar.setProgress((int) (fraction * 100));
      progress.setText(
          fresh()
              ? state() + "  ·  " + Math.round(fraction * 100) + "%"
              : "Waiting for connection");
      long seconds = (long) object("print_stats").optDouble("print_duration", 0);
      elapsed.setText(
          fresh()
              ? String.format(Locale.US, "Elapsed %dh %02dm", seconds / 3600, (seconds / 60) % 60)
              : "Set the Moonraker address in Settings");
    }
    if (axes != null) {
      JSONArray p = object("toolhead").optJSONArray("position");
      axes.setText(
          fresh() && p != null
              ? String.format(
                  Locale.US,
                  "X %.1f    Y %.1f    Z %.2f    ·    Homed: %s",
                  p.optDouble(0),
                  p.optDouble(1),
                  p.optDouble(2),
                  object("toolhead").optString("homed_axes", "none"))
              : "Position unavailable");
    }
    if (cameraState != null) cameraState.setText(MonitorService.state);
  }

  String temperatureText(String name) {
    JSONObject o = object(name);
    return fresh()
        ? String.format(
            Locale.US, "%.0f°  /  %.0f°", o.optDouble("temperature"), o.optDouble("target"))
        : "—  /  —";
  }

  void confirm(String title, String detail, Runnable run) {
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(detail)
        .setNegativeButton("Back", null)
        .setPositiveButton("Continue", (d, w) -> run.run())
        .show();
  }

  void toast(String message) {
    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
  }

  static String message(Exception e) {
    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
  }

  interface Work {
    void run() throws Exception;
  }

  void execute(Work work, String success) {
    if (commandBusy) {
      toast("Please wait for the previous action");
      return;
    }
    commandBusy = true;
    io.execute(() -> {
      try {
        work.run();
        ui.post(() -> toast(success));
      } catch (Exception e) {
        ui.post(() -> toast(message(e)));
      } finally {
        ui.post(() -> {
          commandBusy = false;
          ui.removeCallbacks(poll);
          if (resumed) ui.post(poll);
        });
      }
    });
  }

  void command(String gcode, boolean requireIdle) {
    if (!guard(requireIdle)) return;
    PrinterClient destination = client;
    execute(
        () -> {
          verifyCurrentState(destination, requireIdle);
          destination.gcode(gcode);
        },
        "Sent");
  }

  void postAction(String path, JSONObject body, boolean requireIdle) {
    if (!guard(requireIdle)) return;
    PrinterClient destination = client;
    execute(
        () -> {
          verifyCurrentState(destination, requireIdle);
          destination.request("POST", path, body);
        },
        "Sent");
  }

  void verifyCurrentState(PrinterClient destination, boolean requireIdle) throws Exception {
    JSONObject current = destination.status();
    if (!"ready".equals(current.getJSONObject("webhooks").optString("state")))
      throw new java.io.IOException("Printer is no longer ready");
    String printState = current.getJSONObject("print_stats").optString("state");
    if (requireIdle && (printState.equals("printing") || printState.equals("paused")))
      throw new java.io.IOException("Printer is busy; action was not sent");
  }

  void pauseResume() {
    if (!guard(false)) return;
    if (state().equals("printing")) postAction("/printer/print/pause", null, false);
    else if (state().equals("paused"))
      confirm(
          "Resume print?",
          "Check that the toolhead and print are clear.",
          () -> postAction("/printer/print/resume", null, false));
    else toast("No active print");
  }

  void tunePrint() {
    if (!guard(false)) return;
    if (idle()) {
      toast("Start a print before adjusting it");
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle("Tune print")
        .setItems(
            new String[] {"Speed", "Flow", "Whole-bed Z offset"},
            (d, which) -> {
              if (which < 2) {
                String field = which == 0 ? "speed_factor" : "extrude_factor";
                String code = which == 0 ? "M220" : "M221";
                number(
                    (which == 0 ? "Speed" : "Flow") + " · 50–150%",
                    String.valueOf((int) (object("gcode_move").optDouble(field, 1) * 100)),
                    value -> {
                      if (value < 50 || value > 150) {
                        toast("Use 50–150%");
                        return;
                      }
                      command(code + " S" + value, false);
                    });
              } else {
                if (!object("toolhead").optString("homed_axes").contains("xyz")) {
                  toast("Home all axes first");
                  return;
                }
                new AlertDialog.Builder(this)
                    .setTitle("Whole-bed Z offset")
                    .setMessage(
                        "A small live adjustment only. Use the guided bed-tilt calibration in"
                            + " Mainsail for front/rear setup.")
                    .setNegativeButton("Back", null)
                    .setNeutralButton(
                        "Closer −0.025",
                        (x, w) -> command("SET_GCODE_OFFSET Z_ADJUST=-0.025 MOVE=1", false))
                    .setPositiveButton(
                        "Further +0.025",
                        (x, w) -> command("SET_GCODE_OFFSET Z_ADJUST=0.025 MOVE=1", false))
                    .show();
              }
            })
        .show();
  }

  void number(String title, String initial, java.util.function.Consumer<Double> action) {
    EditText input = new EditText(this);
    input.setText(initial);
    input.setInputType(
        InputType.TYPE_CLASS_NUMBER
            | InputType.TYPE_NUMBER_FLAG_DECIMAL
            | InputType.TYPE_NUMBER_FLAG_SIGNED);
    input.selectAll();
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setNegativeButton("Back", null)
            .setPositiveButton("Set", null)
            .create();
    dialog.setOnShowListener(d ->
        dialog
            .getButton(-1)
            .setOnClickListener(v -> {
              try {
                double value = Double.parseDouble(input.getText().toString());
                if (!Double.isFinite(value)) throw new IllegalArgumentException();
                action.accept(value);
                dialog.dismiss();
              } catch (Exception e) {
                input.setError("Enter a valid value");
              }
            }));
    dialog.show();
  }

  double heaterMax(String heater) {
    JSONObject config = object("configfile").optJSONObject("settings");
    if (config == null || config.optJSONObject(heater) == null) return Double.NaN;
    return config.optJSONObject(heater).optDouble("max_temp", Double.NaN) - 5;
  }

  void temperature(String heater) {
    if (!guard(false)) return;
    double max = heaterMax(heater);
    if (!Double.isFinite(max)) {
      toast("Printer temperature limits unavailable");
      return;
    }
    number(
        (heater.equals("extruder") ? "Nozzle" : "Bed") + " target · max " + (int) max + "°",
        String.valueOf((int) object(heater).optDouble("target")),
        v -> {
          if (v < 0 || v > max) {
            toast("Outside printer temperature limits");
            return;
          }
          confirm(
              "Set temperature to " + v.intValue() + "°?",
              "Keep the printer clear while heating.",
              () -> command("SET_HEATER_TEMPERATURE HEATER=" + heater + " TARGET=" + v, false));
        });
  }

  void preheat() {
    if (!guard(true)) return;
    new AlertDialog.Builder(this)
        .setTitle("Preheat")
        .setItems(
            new String[] {"PLA · 200° / 60°", "ABS · 240° / 100°"},
            (d, w) -> {
              int n = w == 0 ? 200 : 240, b = w == 0 ? 60 : 100;
              if (n > heaterMax("extruder") || b > heaterMax("heater_bed")) {
                toast("Preset exceeds printer limits");
                return;
              }
              confirm(
                  "Preheat printer?",
                  "Nozzle " + n + "° · Bed " + b + "°",
                  () ->
                      command(
                          "SET_HEATER_TEMPERATURE HEATER=extruder TARGET="
                              + n
                              + "\nSET_HEATER_TEMPERATURE HEATER=heater_bed TARGET="
                              + b,
                          true));
            })
        .show();
  }

  int step = 10;

  void controlPage() {
    axes = text("Position unavailable", 15, MUTED);
    content.addView(axes);
    LinearLayout steps = row();
    for (int s : new int[] {1, 10, 25})
      equalButton(
          steps,
          s + " mm",
          () -> {
            step = s;
            toast("Move distance: " + s + " mm");
          });
    content.addView(steps);
    LinearLayout moves = row();
    for (String axis : new String[] {"X", "Y", "Z"}) {
      LinearLayout c = column();
      c.setPadding(dp(3), 0, dp(3), 0);
      addButton(c, axis + " +", () -> jog(axis, step));
      addButton(c, axis + " −", () -> jog(axis, -step));
      moves.addView(c, new LinearLayout.LayoutParams(0, -2, 1));
    }
    content.addView(moves);
    LinearLayout actions = row();
    equalButton(
        actions,
        "Home all",
        () -> {
          if (guard(true))
            confirm(
                "Home all axes?",
                "The toolhead and bed will move. Clear the printer first.",
                () -> command("G28", true));
        });
    equalButton(
        actions,
        "Motors off",
        () -> {
          if (guard(true))
            confirm(
                "Release motors?",
                "The bed may move when its motor is released.",
                () -> command("M84", true));
        });
    equalButton(
        actions,
        "Part fan",
        () -> {
          if (guard(false))
            number(
                "Part fan · 0–100%",
                "0",
                v -> {
                  if (v < 0 || v > 100) {
                    toast("Use 0–100%");
                    return;
                  }
                  command("M106 S" + Math.round(v * 2.55), false);
                });
        });
    content.addView(actions);
    LinearLayout filament = row();
    equalButton(filament, "Extrude 5 mm", () -> extrude(5));
    equalButton(filament, "Retract 5 mm", () -> extrude(-5));
    equalButton(filament, "Calibration", () -> openMainsail());
    content.addView(filament);
    hint(
        content,
        "Moves require homing. Heat the nozzle before feeding filament. Calibration opens"
            + " Mainsail’s guided tools.");
  }

  void jog(String axis, int distance) {
    if (!guard(true)) return;
    String a = axis.toLowerCase(Locale.US);
    JSONObject t = object("toolhead");
    if (!t.optString("homed_axes").contains(a)) {
      toast("Home " + axis + " first");
      return;
    }
    int index = "XYZ".indexOf(axis);
    JSONArray pos = t.optJSONArray("position"),
        min = t.optJSONArray("axis_minimum"),
        max = t.optJSONArray("axis_maximum");
    if (pos == null || min == null || max == null) {
      toast("Travel limits unavailable");
      return;
    }
    double target = pos.optDouble(index) + distance;
    if (target < min.optDouble(index) || target > max.optDouble(index)) {
      toast("Move exceeds " + axis + " travel");
      return;
    }
    command(
        "SAVE_GCODE_STATE NAME=panel_move\nG91\nG0 "
            + axis
            + distance
            + " F"
            + (axis.equals("Z") ? 300 : 1200)
            + "\nRESTORE_GCODE_STATE NAME=panel_move",
        true);
  }

  void extrude(int distance) {
    if (!guard(true)) return;
    if (!object("extruder").optBoolean("can_extrude")) {
      toast("Heat the nozzle before feeding filament");
      return;
    }
    command(
        "SAVE_GCODE_STATE NAME=panel_extrude\nM83\nG1 E"
            + distance
            + " F180\nRESTORE_GCODE_STATE NAME=panel_extrude",
        true);
  }

  void filesPage() {
    hint(content, "G-code stored on the printer");
    addButton(content, "Refresh files", () -> show("Files"));
    io.execute(() -> {
      try {
        JSONArray list =
            client.request("GET", "/server/files/list?root=gcodes", null).getJSONArray("result");
        ArrayList<JSONObject> files = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) files.add(list.getJSONObject(i));
        files.sort((a, b) -> Double.compare(b.optDouble("modified"), a.optDouble("modified")));
        ui.post(() -> {
          if (!page.equals("Files")) return;
          if (files.isEmpty()) hint(content, "No files yet. Send a plate from Orca.");
          for (JSONObject file : files) {
            String name = file.optString("path");
            if (!(name.endsWith(".gcode") || name.endsWith(".gco") || name.endsWith(".g")))
              continue;
            addButton(
                content,
                name
                    + "   ·   "
                    + String.format(Locale.US, "%.1f MB", file.optDouble("size") / 1048576),
                () -> {
                  if (guard(true))
                    confirm(
                        "Print " + name + "?",
                        "Check that the plate is clear and prepared.",
                        () -> {
                          try {
                            postAction(
                                "/printer/print/start",
                                new JSONObject().put("filename", name),
                                true);
                          } catch (JSONException e) {
                            toast(message(e));
                          }
                        });
                });
          }
        });
      } catch (Exception e) {
        ui.post(() -> {
          if (page.equals("Files")) hint(content, message(e));
        });
      }
    });
  }

  void cameraPage() {
    LinearLayout c = card(content);
    cameraState = text(MonitorService.state, 19, TEXT);
    c.addView(cameraState);
    hint(c, "Rear camera · silent · local MJPEG feed");
    LinearLayout actions = row();
    equalButton(actions, "Start monitoring", this::startMonitor);
    equalButton(
        actions,
        "Stop",
        () -> {
          getSharedPreferences("panel", 0).edit().putBoolean("monitor_on_open", false).apply();
          stopService(new Intent(this, MonitorService.class));
          ui.postDelayed(this::updateValues, 400);
        });
    equalButton(
        actions,
        "Preview",
        () -> {
          byte[] frame = MonitorService.latest;
          if (frame == null) {
            toast("Start monitoring first");
            return;
          }
          ImageView image = new ImageView(this);
          image.setAdjustViewBounds(true);
          image.setImageBitmap(
              android.graphics.BitmapFactory.decodeByteArray(frame, 0, frame.length));
          new AlertDialog.Builder(this).setView(image).setPositiveButton("Done", null).show();
        });
    content.addView(actions);
    hint(content, "Light brightness");
    SeekBar brightness = new SeekBar(this);
    brightness.setMax(100);
    brightness.setProgress(MonitorService.light);
    brightness.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar b, int v, boolean user) {
            if (user) MonitorService.setLight(v);
          }

          public void onStartTrackingTouch(SeekBar b) {}

          public void onStopTrackingTouch(SeekBar b) {}
        });
    content.addView(brightness);
    addButton(
        content,
        "Share camera / Orca connection",
        () -> {
          if (!MonitorService.running) {
            toast("Start monitoring first");
            return;
          }
          String address = networkAddresses();
          String token = MonitorService.token(this);
          new AlertDialog.Builder(this)
              .setTitle("Local connection")
              .setMessage(
                  address
                      + "\n\nPort: 8765\nAPI key: "
                      + token
                      + "\n\n"
                      + "Camera: /camera/stream?token=<API key>\n"
                      + "Device UI: /?token=<API key>\n\n"
                      + "Use Moonraker (Klipper) in Orca. This is a local connection, not an"
                      + " internet link.")
              .setPositiveButton("Done", null)
              .show();
        });
    hint(
        content,
        "AI detection is not enabled. The camera runs independently of the Linux desktop. Stop"
            + " releases the camera, light and monitoring service.");
  }

  void startMonitor() {
    ArrayList<String> permissions = new ArrayList<>();
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
      permissions.add(Manifest.permission.CAMERA);
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED)
      permissions.add(Manifest.permission.POST_NOTIFICATIONS);
    if (!permissions.isEmpty()) {
      requestPermissions(permissions.toArray(new String[0]), 10);
      return;
    }
    getSharedPreferences("panel", 0).edit().putBoolean("monitor_on_open", true).apply();
    Intent intent = new Intent(this, MonitorService.class);
    intent.putExtra("endpoint", client.base);
    intent.putExtra("api_key", client.key);
    startForegroundService(intent);
    ui.postDelayed(this::updateValues, 1200);
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(request, permissions, results);
    if (request == 10
        && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
      startMonitor();
    if (request == 11
        && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
            == PackageManager.PERMISSION_GRANTED) startHotspot();
  }

  void settingsPage() {
    hint(content, "MOONRAKER CONNECTION");
    LinearLayout fields = row();
    EditText endpoint = new EditText(this);
    endpoint.setSingleLine(true);
    endpoint.setTextColor(TEXT);
    endpoint.setTextSize(16);
    endpoint.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    endpoint.setText(client.base);
    fields.addView(endpoint, new LinearLayout.LayoutParams(0, dp(52), 1));
    Button save =
        button(
            "Connect",
            () -> {
              try {
                String value = PrinterClient.normalize(endpoint.getText().toString());
                getSharedPreferences("panel", 0).edit().putString("endpoint", value).apply();
                client = new PrinterClient(this);
                statusTime = 0;
                live = false;
                show("Settings");
                ui.removeCallbacks(poll);
                ui.post(poll);
              } catch (Exception e) {
                toast(message(e));
              }
            });
    fields.addView(save);
    content.addView(fields);
    LinearLayout links = row();
    equalButton(links, "Wi-Fi", () -> settings(Settings.ACTION_WIFI_SETTINGS));
    equalButton(links, "USB / tethering", () -> settings("android.settings.TETHER_SETTINGS"));
    equalButton(links, "Offline hotspot", this::startHotspot);
    content.addView(links);
    if (!hotspotInfo.isEmpty()) hint(content, hotspotInfo);
    hint(content, "Phone addresses: " + networkAddresses());
    LinearLayout more = row();
    equalButton(more, "API key", () -> apiKey());
    equalButton(more, "Mainsail", this::openMainsail);
    equalButton(more, "Software versions", this::versions);
    content.addView(more);
    LinearLayout maintenance = row();
    equalButton(
        maintenance,
        "Restart Klipper",
        () -> {
          if (guard(true))
            confirm(
                "Restart Klipper?",
                "This reloads printer configuration. The printer must be idle.",
                () -> postAction("/printer/restart", null, true));
        });
    equalButton(
        maintenance,
        "Emergency stop",
        () ->
            confirm(
                "Emergency stop?",
                "Klipper will enter shutdown and require a restart.",
                () ->
                    execute(
                        () -> client.request("POST", "/printer/emergency_stop", null),
                        "Emergency stop sent")));
    content.addView(maintenance);
    addButton(
        content,
        "Startup options",
        () -> {
          CheckBox restore = new CheckBox(this);
          restore.setText("Restore monitoring when Voron opens");
          restore.setTextColor(TEXT);
          restore.setChecked(getSharedPreferences("panel", 0).getBoolean("monitor_on_open", false));
          restore.setOnCheckedChangeListener((b, value) ->
              getSharedPreferences("panel", 0).edit().putBoolean("monitor_on_open", value).apply());
          new AlertDialog.Builder(this)
              .setTitle("Dedicated printer display")
              .setMessage(
                  "To open Voron at startup, choose it as the default Home app. This changes"
                      + " Android’s Home screen. Keep the current development Pixel’s launcher"
                      + " unchanged. Cold-boot and USB recovery still need testing.")
              .setView(restore)
              .setNegativeButton("Done", null)
              .setPositiveButton(
                  "Home app settings", (d, w) -> settings(Settings.ACTION_HOME_SETTINGS))
              .show();
        });
    hint(
        content,
        "Offline hotspot currently connects clients to this app. USB routing to the Pi must be"
            + " verified. Automatic boot recovery and remote access are not configured by this"
            + " prototype.");
  }

  void settings(String action) {
    try {
      startActivity(new Intent(action));
    } catch (Exception e) {
      startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
    }
  }

  void apiKey() {
    EditText input = new EditText(this);
    input.setSingleLine(true);
    input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    input.setText(client.key);
    new AlertDialog.Builder(this)
        .setTitle("Moonraker API key (optional)")
        .setView(input)
        .setNegativeButton("Back", null)
        .setPositiveButton(
            "Save",
            (d, w) -> {
              getSharedPreferences("panel", 0)
                  .edit()
                  .putString("api_key", input.getText().toString().trim())
                  .apply();
              client = new PrinterClient(this);
              toast("Saved on this phone");
            })
        .show();
  }

  void openMainsail() {
    try {
      URI uri = new URI(client.base);
      String url =
          new URI(
                  uri.getScheme(),
                  null,
                  uri.getHost(),
                  uri.getPort() == 7125 ? -1 : uri.getPort(),
                  "/",
                  null,
                  null)
              .toString();
      startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    } catch (Exception e) {
      toast(message(e));
    }
  }

  void versions() {
    execute(
        () -> {
          JSONObject data =
              client
                  .request("GET", "/machine/update/status?refresh=false", null)
                  .getJSONObject("result");
          JSONObject versions = data.optJSONObject("version_info");
          StringBuilder summary = new StringBuilder();
          if (versions != null)
            for (String component : new String[] {"klipper", "moonraker", "mainsail"}) {
              JSONObject v = versions.optJSONObject(component);
              if (v != null)
                summary
                    .append(component)
                    .append("\n")
                    .append(v.optString("version", "unknown"))
                    .append(" → ")
                    .append(v.optString("remote_version", "unknown"))
                    .append("\n\n");
            }
          if (summary.length() == 0) summary.append("Update manager is not configured.");
          summary.append(
              "Updates stay in Mainsail’s updater. Klipper updates can require MCU firmware updates"
                  + " and must preserve the custom extras.");
          ui.post(() ->
              new AlertDialog.Builder(this)
                  .setTitle("Software")
                  .setMessage(summary.toString())
                  .setNegativeButton("Done", null)
                  .setPositiveButton("Open updater", (d, w) -> openMainsail())
                  .show());
        },
        "Versions checked");
  }

  void startHotspot() {
    if (hotspot != null) {
      new AlertDialog.Builder(this)
          .setTitle("Offline hotspot")
          .setMessage(hotspotInfo)
          .setNegativeButton("Keep on", null)
          .setPositiveButton(
              "Stop hotspot",
              (d, w) -> {
                hotspot.close();
                hotspot = null;
                hotspotInfo = "";
                show("Settings");
              })
          .show();
      return;
    }
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
            != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {Manifest.permission.NEARBY_WIFI_DEVICES}, 11);
      return;
    }
    try {
      WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
      WifiManager.LocalOnlyHotspotCallback callback =
          new WifiManager.LocalOnlyHotspotCallback() {
            @Override
            public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
              hotspot = reservation;
              SoftApConfiguration config = reservation.getSoftApConfiguration();
              hotspotInfo =
                  "Network: " + config.getSsid() + "\nPassword: " + config.getPassphrase();
              show("Settings");
              new AlertDialog.Builder(MainActivity.this)
                  .setTitle("Offline hotspot ready")
                  .setMessage(hotspotInfo + "\n\nPrinter routing is not yet verified.")
                  .setPositiveButton("Done", null)
                  .show();
            }

            @Override
            public void onFailed(int reason) {
              toast("Hotspot unavailable (" + reason + "). Try Android hotspot settings.");
            }

            @Override
            public void onStopped() {
              hotspot = null;
              hotspotInfo = "";
            }
          };
      if (Build.VERSION.SDK_INT >= 36) {
        android.content.SharedPreferences prefs = getSharedPreferences("panel", 0);
        String password = prefs.getString("hotspot_password", "");
        if (password.isEmpty()) {
          password = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
          prefs.edit().putString("hotspot_password", password).apply();
        }
        SoftApConfiguration config =
            new SoftApConfiguration.Builder()
                .setWifiSsid(
                    WifiSsid.fromBytes("Voron".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .setPassphrase(password, SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
                .build();
        wifi.startLocalOnlyHotspotWithConfiguration(config, getMainExecutor(), callback);
      } else wifi.startLocalOnlyHotspot(callback, ui);
    } catch (Exception e) {
      toast(message(e));
    }
  }

  String networkAddresses() {
    ArrayList<String> addresses = new ArrayList<>();
    try {
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      while (interfaces.hasMoreElements()) {
        NetworkInterface n = interfaces.nextElement();
        Enumeration<InetAddress> ips = n.getInetAddresses();
        while (ips.hasMoreElements()) {
          InetAddress a = ips.nextElement();
          if (a instanceof Inet4Address && !a.isLoopbackAddress())
            addresses.add(n.getName() + ": " + a.getHostAddress());
        }
      }
    } catch (Exception ignored) {
    }
    return addresses.isEmpty() ? "No local network" : String.join(" · ", addresses);
  }
}
