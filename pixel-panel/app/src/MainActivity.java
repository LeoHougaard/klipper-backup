package dev.leo.voronpanel;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.net.*;
import android.os.*;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.json.*;

/** Native touch console; Klipper on the Pi owns print execution. */
public final class MainActivity extends Activity {
  final Handler ui = new Handler(Looper.getMainLooper());
  final ExecutorService reads = Executors.newSingleThreadExecutor(),
      actions = Executors.newSingleThreadExecutor(),
      urgent = Executors.newSingleThreadExecutor(),
      images = Executors.newSingleThreadExecutor();
  final ExecutorService fileReads = Executors.newSingleThreadExecutor();
  final ArrayList<Runnable> bindings = new ArrayList<>(), dialogBindings = new ArrayList<>();
  final ArrayList<View> headerControls = new ArrayList<>();
  PanelUi p;
  PrinterClient client;
  JSONObject status = new JSONObject();
  long seen, lastPoll, pageGeneration, lastFileRequest, noticeUntil;
  boolean filesFetching;
  String fileError = "";
  volatile long connectionGeneration;
  boolean resumed, polling, commandBusy, emergencyBusy, cameraDecoding, destroyed;
  String page = "Home",
      prepareTab = "Move",
      homeMode = "",
      lastError = "",
      actionLabel = "",
      notice = "";
  int noticeColor = PanelUi.MUTED, filamentTemperature = 200, probeCount = 5;
  TextView connection, feedback;
  TextView dialogFeedback;
  String urgentMessage = "", dialogNotice = "";
  int calibrationResultColor = PanelUi.ACCENT;
  LinearLayout workspace, navigationRail, dialogFooter;
  Dialog activeDialog;
  Button emergencyButton;
  int screenLeftInset, screenRightInset, screenTopInset, screenBottomInset;
  double xyStep = 10, zStep = .1, paperStep = .025;
  boolean calibrationRequested;
  String calibrationResult = "";
  String filamentForJob;
  byte[] displayedFrame;
  ImageView cameraImage;
  TextView cameraOverlay;
  ArrayList<JSONObject> files = new ArrayList<>();
  final Map<String, JSONObject> metadata = new ConcurrentHashMap<>();
  final Map<String, Bitmap> thumbnails = new ConcurrentHashMap<>();
  final Set<String> fileMetadataRequests = new HashSet<>();
  long fileRefreshGeneration;
  LinearLayout fileList;
  String fileFilter = "";
  final Runnable heartbeat =
      new Runnable() {
        public void run() {
          if (!resumed || destroyed) return;
          if (!polling && SystemClock.elapsedRealtime() - lastPoll >= 1800) poll();
          if (page.equals("Home")
              && ready()
              && !filesFetching
              && SystemClock.elapsedRealtime() - lastFileRequest > 15000) fetchFiles();
          updateViews();
          ui.postDelayed(this, 500);
        }
      };

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    p = new PanelUi(this);
    client = new PrinterClient(this);
    if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        && getIntent().hasExtra("test_endpoint")) {
      client =
          new PrinterClient(
              PrinterClient.normalize(getIntent().getStringExtra("test_endpoint")), "");
      if (getIntent().getBooleanExtra("test_reverse_landscape", false))
        setRequestedOrientation(
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
    }
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    getWindow().setStatusBarColor(PanelUi.BG);
    getWindow().setNavigationBarColor(PanelUi.BG);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    getWindow()
        .getDecorView()
        .setOnApplyWindowInsetsListener((v, insets) -> {
          Insets safe =
              insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
          // Reserve the full corner radius horizontally. This protects the
          // entire rectangular touch surface, without spending scarce height
          // on top/bottom padding in landscape. API 31 is our minimum.
          int left = safe.left, right = safe.right;
          for (int position :
              new int[] {RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_BOTTOM_LEFT}) {
            RoundedCorner corner = insets.getRoundedCorner(position);
            if (corner != null) left = Math.max(left, corner.getRadius());
          }
          for (int position :
              new int[] {RoundedCorner.POSITION_TOP_RIGHT, RoundedCorner.POSITION_BOTTOM_RIGHT}) {
            RoundedCorner corner = insets.getRoundedCorner(position);
            if (corner != null) right = Math.max(right, corner.getRadius());
          }
          screenLeftInset = left;
          screenRightInset = right;
          screenTopInset = safe.top;
          screenBottomInset = safe.bottom;
          v.setPadding(left, safe.top, right, safe.bottom);
          return insets;
        });
    show("Home");
    if (Build.VERSION.SDK_INT >= 37
        && checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK)
            != PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {Manifest.permission.ACCESS_LOCAL_NETWORK}, 12);
  }

  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    ui.removeCallbacks(heartbeat);
    ui.post(heartbeat);
    startConnection();
    if (getSharedPreferences("panel", 0).getBoolean("monitor_on_open", false)
        && !MonitorService.running
        && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
      startCamera();
  }

  @Override
  protected void onPause() {
    super.onPause();
    resumed = false;
    ui.removeCallbacks(heartbeat);
  }

  @Override
  protected void onDestroy() {
    destroyed = true;
    ui.removeCallbacksAndMessages(null);
    reads.shutdownNow();
    actions.shutdownNow();
    urgent.shutdownNow();
    images.shutdownNow();
    fileReads.shutdownNow();
    super.onDestroy();
  }

  @Override
  public void onBackPressed() {
    if (!page.equals("Home")) show("Home");
    else super.onBackPressed();
  }

  @Override
  public boolean dispatchTouchEvent(MotionEvent event) {
    if (activeDialog != null && activeDialog.isShowing() && emergencyButton != null) {
      Rect target = new Rect();
      emergencyButton.getGlobalVisibleRect(target);
      boolean allowed = target.contains((int) event.getRawX(), (int) event.getRawY());
      for (View control : headerControls) {
        control.getGlobalVisibleRect(target);
        allowed |= target.contains((int) event.getRawX(), (int) event.getRawY());
      }
      if (!allowed) return true;
    }
    return super.dispatchTouchEvent(event);
  }

  void startConnection() {
    if (!getSharedPreferences("panel", 0).getBoolean("gateway_enabled", true)) return;
    try {
      ConnectionService.start(this, client);
    } catch (Exception e) {
      tell("Local sharing could not start. Open connection settings.", PanelUi.AMBER);
    }
  }

  void poll() {
    polling = true;
    lastPoll = SystemClock.elapsedRealtime();
    final PrinterClient target = client;
    final long generation = connectionGeneration;
    reads.execute(() -> {
      try {
        JSONObject next = target.status();
        ui.post(() -> {
          if (destroyed || target != client || generation != connectionGeneration) return;
          status = next;
          seen = SystemClock.elapsedRealtime();
          lastError = "";
        });
      } catch (Exception e) {
        ui.post(() -> {
          if (destroyed || target != client || generation != connectionGeneration) return;
          lastError = message(e);
          seen = 0;
        });
      } finally {
        ui.post(() -> polling = false);
      }
    });
  }

  JSONObject obj(String name) {
    JSONObject v = status.optJSONObject(name);
    return v == null ? new JSONObject() : v;
  }

  String engine() {
    return obj("webhooks").optString("state", "");
  }

  String jobState() {
    return obj("print_stats").optString("state", "");
  }

  boolean fresh() {
    return seen > 0 && SystemClock.elapsedRealtime() - seen < 6000;
  }

  boolean ready() {
    return fresh()
        && engine().equals("ready")
        && Arrays.asList("standby", "printing", "paused", "complete", "cancelled", "error")
            .contains(jobState());
  }

  boolean idle() {
    return ready() && !jobState().equals("printing") && !jobState().equals("paused");
  }

  boolean activePrint() {
    return ready() && (jobState().equals("printing") || jobState().equals("paused"));
  }

  boolean manual() {
    return ready() && obj("manual_probe").optBoolean("is_active");
  }

  boolean capable(String command) {
    JSONArray names = status.optJSONArray("_commands");
    if (names == null) return false;
    for (int i = 0; i < names.length(); i++) if (command.equals(names.optString(i))) return true;
    return false;
  }

  static double finite(JSONObject o, String key) {
    double v = o.optDouble(key, Double.NaN);
    return Double.isFinite(v) ? v : Double.NaN;
  }

  String value(double n, int decimals) {
    return Double.isFinite(n) ? String.format(Locale.US, "%." + decimals + "f", n) : "—";
  }

  String temperature(String heater) {
    return fresh() ? value(finite(obj(heater), "temperature"), 0) + "°" : "—";
  }

  boolean heating() {
    for (String h : new String[] {"extruder", "heater_bed"})
      if (finite(obj(h), "target") > finite(obj(h), "temperature") + 3) return true;
    return false;
  }

  String stateTitle() {
    if (!fresh()) return seen == 0 && lastError.isEmpty() ? "Connecting" : "Disconnected";
    if (!engine().equals("ready"))
      return engine().equals("shutdown")
          ? "Printer shutdown"
          : engine().equals("error") ? "Printer error" : "Starting Klipper";
    if (!ready()) return "Status unavailable";
    if (manual()) return "Paper calibration";
    if (jobState().equals("paused")) return "Print paused";
    if (jobState().equals("printing")) return "Printing";
    if (jobState().equals("complete")) return "Print complete";
    if (jobState().equals("error")) return "Print failed";
    return heating() ? "Heating" : "Ready";
  }

  String mode() {
    if (!ready())
      return "unavailable:"
          + (fresh() ? engine() : lastError.isEmpty() ? "connecting" : "disconnected");
    if (manual()) return "paper:" + obj("y_axis_z_offset").optInt("calibration_point", 0);
    return (activePrint() ? "active:" + jobState() + ":" : "idle:" + jobState() + ":")
        + obj("print_stats").optString("filename");
  }

  void updateViews() {
    if (destroyed || connection == null) return;
    if (page.equals("Home") && !homeMode.equals(mode())) {
      show("Home");
      return;
    }
    if (page.equals("Prepare")
        && prepareTab.equals("Bed setup")
        && !bedMode.equals(manual() ? "paper" : "setup")) {
      show("Prepare");
      return;
    }
    connection.setText("●  " + stateTitle());
    connection.setTextColor(
        !urgentMessage.isEmpty()
                || fresh()
                    && (engine().equals("shutdown")
                        || engine().equals("error")
                        || jobState().equals("error"))
            ? PanelUi.DANGER
            : ready()
                ? jobState().equals("paused") ? PanelUi.AMBER : PanelUi.ACCENT
                : PanelUi.AMBER);
    if (!commandBusy && noticeUntil > 0 && SystemClock.elapsedRealtime() > noticeUntil) notice = "";
    feedback.setText(
        !urgentMessage.isEmpty() ? urgentMessage : commandBusy ? actionLabel + "…" : notice);
    feedback.setVisibility(feedback.getText().length() == 0 ? View.GONE : View.VISIBLE);
    feedback.setTextColor(
        !urgentMessage.isEmpty() ? PanelUi.DANGER : commandBusy ? PanelUi.ACCENT : noticeColor);
    if (dialogFeedback != null) {
      dialogFeedback.setText(commandBusy ? actionLabel + "…" : dialogNotice);
      dialogFeedback.setTextColor(commandBusy ? PanelUi.ACCENT : noticeColor);
    }
    for (Runnable b : new ArrayList<>(bindings)) b.run();
    for (Runnable b : new ArrayList<>(dialogBindings)) b.run();
    updateCamera();
  }

  void tell(String text, int color) {
    notice = text;
    noticeUntil = SystemClock.elapsedRealtime() + (color == PanelUi.DANGER ? 15000 : 6000);
    noticeColor = color;
    if (activeDialog != null) dialogNotice = text;
    if (feedback != null) {
      feedback.setText(text);
      feedback.setTextColor(color);
    }
  }

  static String message(Exception e) {
    if (e instanceof java.net.SocketTimeoutException)
      return "Response not confirmed. Check printer status before retrying.";
    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
  }

  void bindText(TextView v, Supplier<String> s) {
    bindings.add(() -> v.setText(s.get()));
    v.setText(s.get());
  }

  Button button(String label, String icon, int style, Runnable action) {
    return p.button(label, icon, style, action);
  }

  void gate(Button v, BooleanSupplier s) {
    bindings.add(() -> v.setEnabled(s.getAsBoolean() && !commandBusy));
    v.setEnabled(s.getAsBoolean() && !commandBusy);
  }

  void add(LinearLayout parent, View v, int height) {
    parent.addView(v, new LinearLayout.LayoutParams(-1, height < 0 ? height : p.dp(height)));
  }

  void subtext(LinearLayout parent, String text) {
    TextView t = p.text(text, 13, PanelUi.MUTED);
    t.setLineSpacing(p.dp(2), 1);
    parent.addView(t);
  }

  void rowButtons(LinearLayout parent, Button... buttons) {
    LinearLayout row = p.row();
    for (Button b : buttons) p.weighted(row, b, 48);
    add(parent, row, 48);
  }

  LinearLayout scroll(LinearLayout parent) {
    ScrollView s = new ScrollView(this);
    s.setFillViewport(true);
    LinearLayout body = p.col();
    s.addView(body);
    parent.addView(s, new LinearLayout.LayoutParams(-1, -1));
    return body;
  }

  void show(String next) {
    page = next;
    pageGeneration++;
    bindings.clear();
    headerControls.clear();
    cameraImage = null;
    cameraOverlay = null;
    displayedFrame = null;
    fileList = null;
    LinearLayout root = p.row();
    root.setBackgroundColor(PanelUi.BG);
    p.pad(root, 8, 8);
    LinearLayout rail = p.col();
    navigationRail = rail;
    rail.setGravity(Gravity.CENTER_HORIZONTAL);
    rail.setPadding(0, 0, p.dp(8), 0);
    TextView mark = p.text("", 11, PanelUi.MUTED);
    bindText(mark, () -> fastControls() ? "V / 0.2 · FAST" : "V / 0.2");
    mark.setGravity(Gravity.CENTER);
    add(rail, mark, 28);
    String[] tabs = {"Home", "Prepare", "Files", "Camera"},
        icons = {"home", "move", "files", "camera"};
    for (int i = 0; i < tabs.length; i++) {
      String name = tabs[i];
      View item = p.navigation(name, icons[i], name.equals(page), () -> show(name));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, p.dp(56));
      lp.bottomMargin = p.dp(4);
      rail.addView(item, lp);
    }
    rail.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
    add(
        rail,
        p.navigation(
            "More", "more", page.equals("More") || page.equals("Connection"), () -> show("More")),
        56);
    root.addView(rail, new LinearLayout.LayoutParams(p.dp(68), -1));
    LinearLayout main = p.col(), header = p.row();
    TextView screenTitle = p.text(page.equals("Home") ? "Voron 0.2" : page, 18, PanelUi.TEXT);
    screenTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    screenTitle.setContentDescription("Screen: " + page);
    header.addView(screenTitle, new LinearLayout.LayoutParams(0, -1, 1));
    addHeaderHeater(header, "Nozzle", "extruder");
    addHeaderHeater(header, "Bed", "heater_bed");
    connection = p.text("Connecting", 12, PanelUi.MUTED);
    connection.setContentDescription("Connection details");
    connection.setMinWidth(p.dp(108));
    connection.setPadding(p.dp(10), 0, p.dp(10), 0);
    connection.setOnClickListener(v -> show("Connection"));
    header.addView(connection, new LinearLayout.LayoutParams(-2, -1));
    Button stop = button("E-stop", "stop", 2, this::emergencyStop);
    emergencyButton = stop;
    stop.setContentDescription("Emergency stop");
    header.addView(stop, new LinearLayout.LayoutParams(p.dp(96), p.dp(48)));
    add(main, header, 48);
    p.gap(main, 8);
    workspace = p.col();
    main.addView(workspace, new LinearLayout.LayoutParams(-1, 0, 1));
    feedback = p.text("", 12, PanelUi.MUTED);
    feedback.setMaxLines(1);
    feedback.setEllipsize(TextUtils.TruncateAt.END);
    feedback.setVisibility(View.GONE);
    add(main, feedback, 22);
    root.addView(main, new LinearLayout.LayoutParams(0, -1, 1));
    setContentView(root);
    switch (page) {
      case "Home":
        homeMode = mode();
        home();
        break;
      case "Prepare":
        prepare();
        break;
      case "Files":
        filesPage();
        break;
      case "Camera":
        cameraPage();
        break;
      case "Connection":
        connectionPage();
        break;
      default:
        morePage();
    }
    updateViews();
    if (activeDialog != null && activeDialog.isShowing()) sheetFocus(true);
  }

  void sheetFocus(boolean open) {
    if (workspace != null) workspace.setAlpha(open ? .58f : 1f);
    if (navigationRail != null) navigationRail.setAlpha(open ? .35f : 1f);
  }

  void addHeaderHeater(LinearLayout header, String title, String heater) {
    LinearLayout cell = p.col();
    cell.setGravity(Gravity.CENTER_VERTICAL);
    cell.setPadding(p.dp(10), p.dp(3), p.dp(10), p.dp(3));
    TextView label = p.text(title.toUpperCase(Locale.US), 10, PanelUi.MUTED);
    cell.addView(label);
    LinearLayout reading = p.row();
    TextView actual = p.text("", 20, PanelUi.TEXT), target = p.text("", 12, PanelUi.MUTED);
    bindText(actual, () -> temperature(heater));
    bindText(
        target,
        () -> {
          double n = finite(obj(heater), "target");
          return fresh() && Double.isFinite(n)
              ? n > 0 ? " / " + value(n, 0) + "°" : " / off"
              : " / —";
        });
    reading.addView(actual);
    reading.addView(target);
    cell.addView(reading);
    cell.setContentDescription("Set " + title.toLowerCase(Locale.US) + " temperature");
    cell.setOnClickListener(v -> temperatureSheet(heater));
    cell.setFocusable(true);
    headerControls.add(cell);
    bindings.add(() -> {
      boolean enabled = ready() && !manual() && !commandBusy;
      cell.setEnabled(enabled);
      cell.setAlpha(enabled ? 1f : .45f);
      boolean warm = finite(obj(heater), "target") > finite(obj(heater), "temperature") + 3;
      actual.setTextColor(warm ? PanelUi.AMBER : PanelUi.TEXT);
    });
    LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(p.dp(116), p.dp(48));
    size.rightMargin = p.dp(4);
    header.addView(cell, size);
  }

  void home() {
    LinearLayout columns = p.row();
    columns.setGravity(Gravity.TOP);
    LinearLayout left = p.panel(), right = p.panel();
    LinearLayout.LayoutParams main = new LinearLayout.LayoutParams(0, -1, 1);
    main.rightMargin = p.dp(12);
    columns.addView(left, main);
    columns.addView(right, new LinearLayout.LayoutParams(p.dp(280), -1));
    add(workspace, columns, -1);
    if (!ready()) {
      unavailable(left);
      right.addView(p.text("Connection path", 18, PanelUi.TEXT));
      p.gap(right, 14);
      TextView address = p.text(client.base, 13, PanelUi.MUTED);
      address.setMaxLines(2);
      add(right, address, 40);
      statusLine(right, "Pixel sharing", () -> ConnectionService.running ? "On" : "Off");
      statusLine(right, "Moonraker", () -> fresh() ? "Responding" : "No response");
      statusLine(right, "Klipper", () -> fresh() ? engine() : "Unknown");
      p.gap(right, 14);
      add(right, button("Connection settings", "wifi", 0, () -> show("Connection")), 48);
      return;
    }
    if (manual()) {
      left.addView(p.label("BED CALIBRATION"));
      p.gap(left, 12);
      left.addView(p.heading("Continue the paper test"));
      p.gap(left, 10);
      subtext(left, "The printer is waiting for the next nozzle contact measurement.");
      p.gap(left, 18);
      add(left, button("Continue calibration", "calibrate", 1, () -> openPrepare("Bed setup")), 48);
      right.addView(p.label("IN PROGRESS"));
      p.gap(right, 12);
      subtext(right, "Accept or abort the active paper contact before other printer tasks.");
      return;
    }
    if (activePrint()) {
      activeJob(left);
      liveTuning(right);
    } else if (Arrays.asList("complete", "error", "cancelled").contains(jobState())) {
      finishedJob(left, right);
    } else {
      idleHome(left);
      recentPrint(right);
    }
  }

  void statusLine(LinearLayout parent, String label, Supplier<String> reading) {
    LinearLayout row = p.row();
    row.addView(p.text(label, 13, PanelUi.MUTED), new LinearLayout.LayoutParams(0, -1, 1));
    TextView value = p.text("", 13, PanelUi.TEXT);
    bindText(value, reading);
    row.addView(value);
    add(parent, row, 30);
  }

  ImageView preview(LinearLayout parent, int width, int height, Supplier<String> filename) {
    ImageView image = new ImageView(this);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    image.setBackground(p.shape(PanelUi.BG, PanelUi.CONTROL_RADIUS));
    image.setClipToOutline(true);
    p.pad(image, 6, 6);
    image.setContentDescription("Model preview");
    final Bitmap[] previous = {null};
    final String[] previousName = {null};
    bindings.add(() -> {
      String name = filename.get();
      Bitmap bitmap = thumbnails.get(name);
      if (!Objects.equals(previousName[0], name) || previous[0] != bitmap) {
        previousName[0] = name;
        previous[0] = bitmap;
        if (bitmap != null) image.setImageBitmap(bitmap);
        else image.setImageDrawable(new PanelUi.Icon("files", PanelUi.MUTED));
      }
    });
    parent.addView(
        image, new LinearLayout.LayoutParams(width < 0 ? width : p.dp(width), p.dp(height)));
    return image;
  }

  JSONObject recentFile() {
    return files.isEmpty() ? null : files.get(0);
  }

  String recentName() {
    JSONObject file = recentFile();
    return file == null ? "" : file.optString("path");
  }

  void recentPrint(LinearLayout card) {
    card.addView(p.label("RECENT PLATE"));
    p.gap(card, 8);
    preview(card, -1, 100, this::recentName);
    p.gap(card, 8);
    TextView name = p.text("", 17, PanelUi.TEXT);
    name.setMaxLines(2);
    name.setEllipsize(TextUtils.TruncateAt.END);
    bindText(
        name,
        () ->
            !recentName().isEmpty()
                ? friendly(recentName())
                : filesFetching
                    ? "Loading stored prints…"
                    : !fileError.isEmpty() ? "Library unavailable" : "No stored prints");
    add(card, name, 42);
    TextView detail = p.text("", 12, PanelUi.MUTED);
    detail.setMaxLines(2);
    bindText(
        detail,
        () ->
            recentFile() == null
                ? !fileError.isEmpty()
                    ? "Open Files to retry the connection"
                    : filesFetching ? "Reading the Pi's file library" : "Send a plate from Orca"
                : fileSummary(recentFile(), metadata.get(recentName())));
    add(card, detail, 30);
    p.gap(card, 8);
    Button open =
        button(
            "Print details",
            "play",
            1,
            () -> {
              if (recentFile() != null) fileDetails(recentFile());
              else show("Files");
            });
    bindings.add(() -> open.setText(recentFile() == null ? "Browse prints" : "Print details"));
    add(card, open, 48);
  }

  void heaterCard(LinearLayout parent, String title, String heater, String icon) {
    LinearLayout card = p.panel();
    p.pad(card, 12, 10);
    LinearLayout top = p.row();
    top.addView(p.label(title.toUpperCase(Locale.US)), new LinearLayout.LayoutParams(0, -2, 1));
    ImageView graphic = new ImageView(this);
    graphic.setImageDrawable(new PanelUi.Icon(icon, PanelUi.MUTED));
    top.addView(graphic, new LinearLayout.LayoutParams(p.dp(19), p.dp(19)));
    card.addView(top);
    LinearLayout reading = p.row();
    TextView actual = p.text("—", 29, PanelUi.TEXT);
    bindText(actual, () -> temperature(heater));
    reading.addView(actual, new LinearLayout.LayoutParams(0, p.dp(48), 1));
    Button target = button("Set target", "edit", 3, () -> temperatureSheet(heater));
    target.setTextSize(12);
    target.setPadding(p.dp(8), 0, p.dp(8), 0);
    target.setContentDescription("Set " + title.toLowerCase(Locale.US) + " temperature");
    bindings.add(() -> {
      double n = finite(obj(heater), "target");
      target.setText(
          fresh() && Double.isFinite(n) && n > 0 ? value(n, 0) + "° target" : "Set target");
    });
    gate(target, () -> ready() && !manual());
    reading.addView(target, new LinearLayout.LayoutParams(p.dp(116), p.dp(48)));
    card.addView(reading);
    add(parent, card, 92);
  }

  View task(String label, String detail, String icon, Runnable action) {
    LinearLayout tile = p.panel();
    p.pad(tile, 12, 10);
    tile.setBackground(
        new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(PanelUi.RIPPLE),
            p.shape(PanelUi.SURFACE, PanelUi.CARD_RADIUS),
            null));
    LinearLayout first = p.row();
    ImageView image = new ImageView(this);
    image.setImageDrawable(new PanelUi.Icon(icon, PanelUi.ACCENT));
    first.addView(image, new LinearLayout.LayoutParams(p.dp(22), p.dp(22)));
    TextView title = p.text(label, 16, PanelUi.TEXT);
    title.setPadding(p.dp(10), 0, 0, 0);
    first.addView(title);
    tile.addView(first);
    p.gap(tile, 7);
    tile.addView(p.text(detail, 12, PanelUi.MUTED));
    tile.setContentDescription(label);
    tile.setOnClickListener(v -> action.run());
    return tile;
  }

  void idleHome(LinearLayout left) {
    TextView title = p.text("", 26, PanelUi.TEXT);
    bindText(title, () -> heating() ? "Heating" : "Ready");
    left.addView(title);
    TextView state = p.text("", 13, PanelUi.MUTED);
    bindText(state, () -> homed("xyz") ? "All axes homed" : "Home before moving");
    add(left, state, 24);
    p.gap(left, 10);
    Button home = button("Home axes", "home", 1, this::homeAxes);
    gate(home, () -> idle() && !manual());
    rowButtons(
        left,
        home,
        button("Move", "move", 4, () -> openPrepare("Move")),
        button("Filament", "filament", 4, () -> openPrepare("Filament")));
    p.gap(left, 8);
    rowButtons(
        left,
        button("Bed setup", "calibrate", 0, () -> openPrepare("Bed setup")),
        button("Stored prints", "files", 0, () -> show("Files")));
    p.gap(left, 14);
    left.addView(p.label("PREHEAT"));
    p.gap(left, 6);
    Button pla = button("PLA 200° / 60°", null, 0, () -> preheat(200, 60));
    Button abs = button("ABS 240° / 100°", null, 0, () -> preheat(240, 100));
    Button cool =
        button(
            "Cool down",
            null,
            0,
            () -> gcode("Turning heaters off", "TURN_OFF_HEATERS", Policy.IDLE, null));
    pla.setTextSize(12);
    abs.setTextSize(12);
    cool.setTextSize(12);
    gate(pla, this::idle);
    gate(abs, this::idle);
    gate(cool, this::idle);
    rowButtons(left, pla, abs, cool);
    p.gap(left, 10);
    TextView coordinates = p.text("", 12, PanelUi.MUTED);
    bindText(coordinates, () -> position() + " mm");
    add(left, coordinates, 22);
  }

  void plateIdentity(LinearLayout parent, String filename) {
    LinearLayout identity = p.row();
    preview(identity, 88, 88, () -> filename);
    LinearLayout info = p.col();
    info.setPadding(p.dp(12), 0, 0, 0);
    TextView name = p.text(friendly(filename), 17, PanelUi.TEXT);
    name.setMaxLines(2);
    name.setEllipsize(TextUtils.TruncateAt.END);
    add(info, name, 44);
    TextView material = p.text("", 12, PanelUi.MUTED);
    bindText(
        material,
        () -> {
          JSONObject meta = metadata.get(filename);
          return meta == null ? "Stored G-code" : meta.optString("filament_type", "Stored G-code");
        });
    add(info, material, 20);
    TextView elapsed = p.text("", 12, PanelUi.MUTED);
    bindText(
        elapsed,
        () -> duration(finite(obj("print_stats"), "print_duration")) + " elapsed" + layerText());
    add(info, elapsed, 22);
    identity.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
    add(parent, identity, 88);
  }

  String layerText() {
    JSONObject info = obj("print_stats").optJSONObject("info");
    return info != null && info.optInt("total_layer") > 0
        ? " · Layer " + info.optInt("current_layer") + "/" + info.optInt("total_layer")
        : "";
  }

  String timeLeft() {
    double seconds = finite(obj("print_stats"), "print_duration"), f = progress();
    return Double.isFinite(seconds) && f > .02
        ? "~" + duration(seconds * (1 - f) / f)
        : "Calculating";
  }

  void activeJob(LinearLayout left) {
    plateIdentity(left, obj("print_stats").optString("filename"));
    p.gap(left, 8);
    LinearLayout stats = p.row();
    TextView percent = p.text("", 34, PanelUi.TEXT);
    bindText(percent, () -> value(progress() * 100, 0) + "%");
    stats.addView(percent, new LinearLayout.LayoutParams(0, p.dp(52), 1));
    LinearLayout time = p.col();
    TextView label = p.label(jobState().equals("paused") ? "PRINT PAUSED" : "EST. REMAINING");
    time.addView(label);
    TextView eta = p.text("", 21, jobState().equals("paused") ? PanelUi.AMBER : PanelUi.TEXT);
    bindText(eta, () -> jobState().equals("paused") ? "Ready to resume" : timeLeft());
    time.addView(eta);
    stats.addView(time);
    add(left, stats, 52);
    ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    bar.setProgressTintList(android.content.res.ColorStateList.valueOf(PanelUi.ACCENT));
    bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(PanelUi.LINE));
    bindings.add(() -> bar.setProgress((int) (progress() * 100)));
    add(left, bar, 6);
    p.gap(left, 12);
    Button pause =
        button(
            jobState().equals("paused") ? "Resume" : "Pause",
            jobState().equals("paused") ? "play" : "pause",
            1,
            this::pauseResume);
    gate(pause, this::activePrint);
    Button cancel = button("Cancel print", null, 0, this::cancelPrint);
    gate(cancel, this::activePrint);
    cancel.setTextColor(PanelUi.DANGER);
    rowButtons(left, pause, cancel);
    p.gap(left, 8);
    if (jobState().equals("paused")) {
      rowButtons(
          left,
          button("Change filament", "filament", 0, () -> openPrepare("Filament")),
          button("View camera", "camera", 0, () -> show("Camera")));
    } else {
      rowButtons(
          left,
          button("View camera", "camera", 0, () -> show("Camera")),
          button(
              "Print details",
              "files",
              0,
              () -> {
                JSONObject file = listedFile(obj("print_stats").optString("filename"));
                if (file != null) fileDetails(file);
                else show("Files");
              }));
    }
  }

  void liveTuning(LinearLayout right) {
    LinearLayout title = p.row();
    title.addView(
        p.text("Live tuning", 17, PanelUi.TEXT), new LinearLayout.LayoutParams(0, p.dp(48), 1));
    Button more = button("•••", null, 0, this::tuningSheet);
    more.setContentDescription("Speed, flow & fan");
    title.addView(more, new LinearLayout.LayoutParams(p.dp(48), p.dp(48)));
    right.addView(title);
    LinearLayout z = p.row();
    z.addView(p.label("Z OFFSET"), new LinearLayout.LayoutParams(0, -1, 1));
    TextView offset = p.text("", 16, PanelUi.ACCENT);
    bindText(offset, () -> value(zOffset(), 3) + " mm");
    z.addView(offset);
    add(right, z, 24);
    Button closer = button("Closer −0.025", null, 0, () -> adjustZ(-.025));
    Button further = button("Further +0.025", null, 0, () -> adjustZ(.025));
    closer.setTextSize(12);
    further.setTextSize(12);
    p.pad(closer, 5, 8);
    p.pad(further, 5, 8);
    gate(closer, () -> activePrint() && homed("xyz"));
    gate(further, () -> activePrint() && homed("xyz"));
    rowButtons(right, closer, further);
    p.gap(right, 8);
    inlineTuneRow(right, "Speed", "speed_factor", 10, 50, 150, "M220 S");
    inlineTuneRow(right, "Flow", "extrude_factor", 5, 50, 150, "M221 S");
    inlineTuneRow(right, "Fan", "fan", 10, 0, 100, "M106 S");
  }

  void finishedJob(LinearLayout left, LinearLayout right) {
    boolean failed = jobState().equals("error");
    TextView state =
        p.text(
            failed
                ? "Print failed"
                : jobState().equals("cancelled") ? "Print cancelled" : "Print finished",
            24,
            failed ? PanelUi.DANGER : PanelUi.ACCENT);
    left.addView(state);
    p.gap(left, 10);
    plateIdentity(left, obj("print_stats").optString("filename"));
    p.gap(left, 10);
    TextView outcome =
        p.text(
            failed
                ? obj("print_stats").optString("message", "The print was interrupted.")
                : "Remove the finished plate before starting another print.",
            13,
            PanelUi.MUTED);
    outcome.setMaxLines(2);
    add(left, outcome, 36);
    p.gap(left, 10);
    rowButtons(
        left,
        button(
            failed ? "Print again" : "Reprint",
            "play",
            1,
            () -> {
              JSONObject file = listedFile(obj("print_stats").optString("filename"));
              if (file != null) fileDetails(file);
              else show("Files");
            }),
        button("Choose print", "files", 0, () -> show("Files")));
    right.addView(p.text(failed ? "Inspect and prepare" : "Next plate", 18, PanelUi.TEXT));
    p.gap(right, 10);
    TextView next = p.text("", 13, PanelUi.MUTED);
    next.setMaxLines(3);
    bindText(
        next,
        () ->
            failed
                ? "Check the plate, nozzle and filament before trying again."
                : finite(obj("extruder"), "target") > 0 || finite(obj("heater_bed"), "target") > 0
                    ? "Heaters are still on. Cool down when finished."
                    : "Heaters are off.");
    add(right, next, 42);
    p.gap(right, 14);
    add(
        right,
        button(
            "Cool down",
            "heat",
            0,
            () -> gcode("Turning heaters off", "TURN_OFF_HEATERS", Policy.IDLE, null)),
        48);
    p.gap(right, 6);
    add(right, button("Change filament", "filament", 0, () -> openPrepare("Filament")), 48);
    p.gap(right, 6);
    add(right, button("Bed setup", "calibrate", 0, () -> openPrepare("Bed setup")), 48);
  }

  double progress() {
    double n = finite(obj("virtual_sdcard"), "progress");
    return Double.isFinite(n) ? Math.max(0, Math.min(1, n)) : 0;
  }

  double zOffset() {
    JSONArray a = obj("gcode_move").optJSONArray("homing_origin");
    return a == null ? Double.NaN : a.optDouble(2, Double.NaN);
  }

  String remaining() {
    double seconds = finite(obj("print_stats"), "print_duration");
    if (!Double.isFinite(seconds)) return "Time unavailable";
    JSONObject info = obj("print_stats").optJSONObject("info");
    String layer =
        info != null && info.optInt("total_layer") > 0
            ? " · Layer " + info.optInt("current_layer") + "/" + info.optInt("total_layer")
            : "";
    if (jobState().equals("paused")) return "Paused · " + duration(seconds) + " elapsed";
    double f = progress();
    return f > .02
        ? "~" + duration(seconds * (1 - f) / f) + " left" + layer
        : duration(seconds) + " elapsed" + layer;
  }

  static String duration(double seconds) {
    if (!Double.isFinite(seconds) || seconds < 0) return "—";
    int minutes = (int) Math.ceil(seconds / 60);
    return minutes >= 60 ? minutes / 60 + "h " + minutes % 60 + "m" : minutes + "m";
  }

  static String friendly(String filename) {
    if (filename == null || filename.isEmpty()) return "Untitled plate";
    return filename
        .substring(filename.lastIndexOf('/') + 1)
        .replaceFirst("(?i)\\.(gcode|gco|g)$", "")
        .replace('_', ' ');
  }

  void unavailable(LinearLayout body) {
    body.addView(p.heading(fresh() ? "Printer stopped" : "Printer unavailable"));
    p.gap(body, 10);
    TextView detail =
        p.text(
            fresh()
                ? obj("webhooks").optString("state_message", "Klipper is not ready.")
                : lastError.isEmpty()
                    ? "Connecting to your printer…"
                    : "Check the Pi's power and network connection.",
            14,
            PanelUi.MUTED);
    detail.setMaxLines(3);
    body.addView(detail);
    p.gap(body, 12);
    subtext(
        body,
        fresh() && !engine().equals("ready")
            ? "Resolve the reported printer fault before restarting Klipper."
            : "Local printing only needs the local printer connection.");
    p.gap(body, 16);
    if (fresh() && !engine().equals("ready"))
      rowButtons(
          body,
          button("Restart Klipper", "refresh", 0, () -> recover(false)),
          button("Firmware restart", null, 0, () -> recover(true)));
    else
      rowButtons(
          body,
          button(
              "Retry",
              "refresh",
              1,
              () -> {
                lastPoll = 0;
                if (!polling) poll();
              }),
          button(
              "Error details",
              null,
              0,
              () ->
                  information(
                      "Connection error",
                      lastError.isEmpty() ? "Waiting for a printer response." : lastError)));
  }

  enum Policy {
    READY,
    IDLE,
    FILAMENT,
    MANUAL
  }

  interface Action {
    void run(JSONObject current, PrinterClient destination) throws Exception;
  }

  void runAction(String label, Policy policy, Action work, Runnable success) {
    if (commandBusy) {
      tell(actionLabel + " is still running", PanelUi.AMBER);
      return;
    }
    if (!ready()) {
      tell("Connect to a ready printer first", PanelUi.AMBER);
      return;
    }
    final PrinterClient destination = client;
    final long generation = connectionGeneration;
    commandBusy = true;
    actionLabel = label;
    updateViews();
    actions.execute(() -> {
      try {
        JSONObject current = destination.status();
        validate(current, policy);
        if (generation != connectionGeneration || destroyed) return;
        work.run(current, destination);
        JSONObject observed = null;
        try {
          observed = destination.status();
        } catch (Exception ignored) {
        }
        final JSONObject after = observed;
        ui.post(() -> {
          if (generation != connectionGeneration || destroyed) return;
          if (after != null) {
            status = after;
            seen = SystemClock.elapsedRealtime();
            lastError = "";
          }
          tell(label + " · done", PanelUi.ACCENT);
          if (success != null) success.run();
        });
      } catch (Exception e) {
        ui.post(() -> {
          if (generation == connectionGeneration && !destroyed)
            tell(label + " · " + message(e), PanelUi.DANGER);
          if (label.equals("Starting bed calibration")) {
            calibrationRequested = false;
            calibrationResult =
                "Start response not confirmed. Check printer status before retrying.";
            calibrationResultColor = PanelUi.AMBER;
          }
        });
      } finally {
        ui.post(() -> {
          commandBusy = false;
          actionLabel = "";
          lastPoll = 0;
          updateViews();
        });
      }
    });
  }

  void validate(JSONObject current, Policy policy) throws Exception {
    JSONObject hooks = current.optJSONObject("webhooks");
    if (hooks == null || !"ready".equals(hooks.optString("state")))
      throw new Exception("Printer is not ready");
    JSONObject print = current.optJSONObject("print_stats");
    String state = print == null ? "" : print.optString("state");
    if (!Arrays.asList("standby", "complete", "cancelled", "error", "printing", "paused")
        .contains(state)) throw new Exception("Print state is unavailable");
    if (policy == Policy.IDLE && (state.equals("printing") || state.equals("paused")))
      throw new Exception("Wait until the print has finished");
    if (policy == Policy.FILAMENT && state.equals("printing"))
      throw new Exception("Pause the print before changing filament");
    JSONObject probe = current.optJSONObject("manual_probe");
    if (policy == Policy.MANUAL && (probe == null || !probe.optBoolean("is_active")))
      throw new Exception("The paper test is not active");
    if (policy != Policy.MANUAL && probe != null && probe.optBoolean("is_active"))
      throw new Exception("Finish or abort the paper test first");
  }

  void gcode(String label, String script, Policy policy, Runnable success) {
    runAction(label, policy, (s, d) -> d.gcode(script), success);
  }

  void emergencyStop() {
    if (emergencyBusy) return;
    dismissSheet();
    connectionGeneration++;
    seen = 0;
    emergencyBusy = true;
    final PrinterClient target = client;
    urgentMessage = "Sending emergency stop…";
    updateViews();
    urgent.execute(() -> {
      try {
        target.request("POST", "/printer/emergency_stop", null);
        ui.post(() -> {
          urgentMessage = "Emergency stop sent · printer shutdown";
          updateViews();
        });
      } catch (Exception e) {
        ui.post(() -> {
          urgentMessage = "Stop could not reach printer · use its power switch";
          updateViews();
        });
      } finally {
        ui.post(() -> {
          emergencyBusy = false;
          lastPoll = 0;
        });
      }
    });
  }

  void recover(boolean firmware) {
    confirm(
        firmware ? "Restart printer firmware?" : "Restart Klipper?",
        firmware
            ? "Clears a shutdown latch and restarts the printer controllers. Do not resume an"
                + " interrupted print."
            : "Reloads Klipper. The printer must not be printing.",
        firmware ? "Firmware restart" : "Restart Klipper",
        () -> {
          if (emergencyBusy) return;
          emergencyBusy = true;
          PrinterClient target = client;
          urgent.execute(() -> {
            try {
              JSONObject info = target.request("GET", "/server/info", null).getJSONObject("result");
              if (info.optString("klippy_state").equals("ready")) {
                JSONObject check = target.status().getJSONObject("print_stats");
                if (Arrays.asList("printing", "paused").contains(check.optString("state")))
                  throw new Exception("Finish or cancel the print first");
              }
              target.request(
                  "POST", firmware ? "/printer/firmware_restart" : "/printer/restart", null);
              ui.post(() -> {
                urgentMessage = "";
                calibrationRequested = false;
                tell("Restart requested", PanelUi.ACCENT);
              });
            } catch (Exception e) {
              ui.post(() -> tell(message(e), PanelUi.DANGER));
            } finally {
              ui.post(() -> {
                emergencyBusy = false;
                seen = 0;
                lastPoll = 0;
              });
            }
          });
        });
  }

  void pauseResume() {
    pauseResume(null);
  }

  void pauseResume(Runnable success) {
    boolean resume = jobState().equals("paused");
    String filename = obj("print_stats").optString("filename");
    Runnable send =
        () ->
            runAction(
                resume ? "Resuming print" : "Pausing print",
                Policy.READY,
                (s, d) -> {
                  String now = s.getJSONObject("print_stats").optString("state");
                  if (!now.equals(resume ? "paused" : "printing")
                      || !filename.equals(s.getJSONObject("print_stats").optString("filename")))
                    throw new Exception("Print state changed; refresh before trying again");
                  d.request(
                      "POST", resume ? "/printer/print/resume" : "/printer/print/pause", null);
                },
                success);
    if (resume)
      confirm("Resume this print?", "Check that the toolhead and print are clear.", "Resume", send);
    else send.run();
  }

  void cancelPrint() {
    String filename = obj("print_stats").optString("filename");
    confirm(
        "Cancel this print?",
        friendly(filename) + " will stop printing.",
        "Cancel print",
        () ->
            runAction(
                "Cancelling print",
                Policy.READY,
                (s, d) -> {
                  JSONObject print = s.getJSONObject("print_stats");
                  if (!Arrays.asList("printing", "paused").contains(print.optString("state"))
                      || !filename.equals(print.optString("filename")))
                    throw new Exception(
                        "Print changed. Check the current plate before cancelling.");
                  d.request("POST", "/printer/print/cancel", null);
                },
                () -> show("Home")));
  }

  void openPrepare(String tab) {
    if (tab.equals("Filament")
        && jobState().equals("paused")
        && !Objects.equals(filamentForJob, obj("print_stats").optString("filename"))) {
      filamentForJob = obj("print_stats").optString("filename");
      double target = finite(obj("extruder"), "target");
      if (Double.isFinite(target) && target > 0) filamentTemperature = (int) Math.round(target);
    }
    prepareTab = tab;
    show("Prepare");
  }

  void prepare() {
    LinearLayout tabs = p.row();
    tabs.setBackground(p.shape(PanelUi.SURFACE, PanelUi.CONTROL_RADIUS));
    for (String name : new String[] {"Move", "Filament", "Bed setup"}) {
      Button tab = button(name, null, name.equals(prepareTab) ? 3 : 4, () -> openPrepare(name));
      tab.setSelected(name.equals(prepareTab));
      tabs.addView(tab, new LinearLayout.LayoutParams(0, p.dp(48), 1));
    }
    LinearLayout bar = p.row();
    bar.addView(tabs, new LinearLayout.LayoutParams(p.dp(480), p.dp(48)));
    add(workspace, bar, 48);
    p.gap(workspace, 8);
    LinearLayout body = p.col();
    workspace.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
    if (prepareTab.equals("Move")) movePage(body);
    else if (prepareTab.equals("Filament")) filamentPage(body);
    else bedPage(body);
  }

  LinearLayout prepareColumns(LinearLayout body) {
    LinearLayout columns = p.row();
    columns.setGravity(Gravity.TOP);
    add(body, columns, -1);
    return columns;
  }

  LinearLayout prepareCard(LinearLayout columns, boolean main) {
    LinearLayout card = p.panel();
    p.pad(card, 12, 12);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, main ? 1.75f : 1f);
    if (main) lp.rightMargin = p.dp(10);
    columns.addView(card, lp);
    return card;
  }

  void prepareSpace(LinearLayout card) {
    p.gap(card, 12);
  }

  void prepareHeading(LinearLayout card, String title) {
    add(card, p.heading(title), 26);
    p.gap(card, 6);
  }

  Button prepareButton(String title, String icon, int style, Runnable click) {
    return button(title, icon, style, click);
  }

  void prepareButtons(LinearLayout parent, Button... controls) {
    LinearLayout row = p.row();
    for (int i = 0; i < controls.length; i++) {
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, p.dp(48), 1);
      if (i > 0) lp.leftMargin = p.dp(6);
      row.addView(controls[i], lp);
    }
    add(parent, row, 48);
  }

  void preparePriority(Button control, BooleanSupplier primary) {
    bindings.add(() -> {
      boolean active = primary.getAsBoolean();
      if (Boolean.valueOf(active).equals(control.getTag())) return;
      control.setTag(active);
      int ink = active ? PanelUi.BG : PanelUi.ACCENT;
      control.setTextColor(p.ink(ink));
      control.setBackground(p.buttonBackground(active ? 1 : 3));
      for (android.graphics.drawable.Drawable drawable : control.getCompoundDrawables())
        if (drawable instanceof PanelUi.Icon) ((PanelUi.Icon) drawable).setInk(ink);
    });
  }

  boolean homed(String axes) {
    String h = obj("toolhead").optString("homed_axes");
    for (char axis : axes.toCharArray()) if (h.indexOf(axis) < 0) return false;
    return true;
  }

  String position() {
    JSONArray a = obj("toolhead").optJSONArray("position");
    if (!fresh() || a == null) return "Position unavailable";
    return "X "
        + value(a.optDouble(0, Double.NaN), 1)
        + "   Y "
        + value(a.optDouble(1, Double.NaN), 1)
        + "   Z "
        + value(a.optDouble(2, Double.NaN), 2);
  }

  void movePage(LinearLayout body) {
    LinearLayout cols = prepareColumns(body);
    LinearLayout motion = prepareCard(cols, true);
    LinearLayout axes = p.row();
    axes.setGravity(Gravity.TOP);
    LinearLayout xy = p.col();
    LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1.8f);
    left.rightMargin = p.dp(12);
    axes.addView(xy, left);
    LinearLayout z = p.col();
    axes.addView(z, new LinearLayout.LayoutParams(p.dp(150), -2));
    TextView xyPosition = p.text("", 13, PanelUi.TEXT);
    bindText(
        xyPosition,
        () -> {
          JSONArray pos = obj("toolhead").optJSONArray("position");
          return fresh() && pos != null
              ? "X "
                  + value(pos.optDouble(0, Double.NaN), 1)
                  + "    Y "
                  + value(pos.optDouble(1, Double.NaN), 1)
                  + " mm"
              : "Position unavailable";
        });
    add(xy, xyPosition, 22);
    TextView zPosition = p.text("", 13, PanelUi.TEXT);
    zPosition.setGravity(Gravity.CENTER);
    bindText(
        zPosition,
        () -> {
          JSONArray pos = obj("toolhead").optJSONArray("position");
          return "Z "
              + (fresh() && pos != null ? value(pos.optDouble(2, Double.NaN), 2) : "—")
              + " mm";
        });
    add(z, zPosition, 22);
    p.gap(xy, 4);
    p.gap(z, 4);
    String[][] cells = {{"", "Y +", ""}, {"X −", "XY", "X +"}, {"", "Y −", ""}};
    for (int index = 0; index < cells.length; index++) {
      String[] line = cells[index];
      LinearLayout row = p.row();
      for (int cell = 0; cell < line.length; cell++) {
        String label = line[cell];
        View v;
        if (label.isEmpty() || label.equals("XY")) {
          TextView t = p.text(label, 13, PanelUi.MUTED);
          t.setGravity(Gravity.CENTER);
          v = t;
        } else {
          String axis = label.substring(0, 1);
          Button b =
              prepareButton(
                  label, null, 0, () -> jog(axis, label.contains("+") ? xyStep : -xyStep));
          gate(b, () -> idle() && !manual() && homed(axis.toLowerCase(Locale.US)));
          v = b;
        }
        LinearLayout.LayoutParams cellLayout = new LinearLayout.LayoutParams(0, p.dp(48), 1);
        if (cell > 0) cellLayout.leftMargin = p.dp(4);
        row.addView(v, cellLayout);
      }
      add(xy, row, 48);
      if (index < 2) p.gap(xy, 4);
    }
    p.gap(xy, 6);
    stepSelector(xy, new double[] {.1, 1, 10}, () -> xyStep, v -> xyStep = v);
    Button up = prepareButton("Z +", null, 0, () -> jog("Z", zStep)),
        down = prepareButton("Z −", null, 0, () -> jog("Z", -zStep));
    gate(up, () -> idle() && !manual() && homed("z"));
    gate(down, () -> idle() && !manual() && homed("z"));
    add(z, up, 48);
    p.gap(z, 4);
    TextView zLabel = p.text("Z AXIS", 11, PanelUi.MUTED);
    zLabel.setGravity(Gravity.CENTER);
    add(z, zLabel, 48);
    p.gap(z, 4);
    add(z, down, 48);
    p.gap(z, 6);
    stepSelector(z, new double[] {.025, .1, 1}, () -> zStep, v -> zStep = v);
    motion.addView(axes, new LinearLayout.LayoutParams(-1, -2));
    LinearLayout tools = prepareCard(cols, false);
    prepareHeading(tools, "Machine");
    TextView homing = p.text("", 13, PanelUi.MUTED);
    bindText(
        homing,
        () ->
            !ready()
                ? "Printer unavailable"
                : !idle()
                    ? "Movement locked during a print"
                    : homed("xyz") ? "All axes homed" : "Home before moving");
    homing.setMaxLines(2);
    add(tools, homing, 32);
    prepareSpace(tools);
    Button home = prepareButton("Home all", "home", 1, this::homeAxes);
    preparePriority(home, () -> !homed("xyz"));
    gate(home, () -> idle() && !manual());
    add(tools, home, 48);
    p.gap(tools, 8);
    Button motors =
        prepareButton(
            "Release motors",
            null,
            0,
            () ->
                confirm(
                    "Release the motors?",
                    "The bed may move when its motor is released.",
                    "Release motors",
                    () -> gcode("Releasing motors", "M84", Policy.IDLE, null)));
    gate(motors, () -> idle() && !manual());
    add(tools, motors, 48);
    p.gap(tools, 8);
    Button fan = prepareButton("Part cooling fan", null, 0, () -> fanSheet());
    gate(fan, () -> ready() && !manual());
    add(tools, fan, 48);
  }

  void stepSelector(
      LinearLayout parent, double[] values, DoubleSupplier selected, DoubleConsumer choose) {
    LinearLayout row = p.row();
    row.setBackground(p.shape(PanelUi.BG, PanelUi.CONTROL_RADIUS));
    for (double step : values) {
      String label = trimNumber(step);
      Button b =
          button(
              label,
              null,
              0,
              () -> {
                choose.accept(step);
                updateViews();
              });
      b.setTextSize(12);
      b.setPadding(p.dp(5), 0, p.dp(5), 0);
      SpannableString caption = new SpannableString(label + "\nmm");
      caption.setSpan(
          new android.text.style.AbsoluteSizeSpan(11, true),
          label.length() + 1,
          caption.length(),
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      b.setMaxLines(2);
      b.setText(caption);
      bindings.add(() -> {
        boolean active = Math.abs(selected.getAsDouble() - step) < .00001;
        PanelUi.selection(b, active, p);
      });
      b.setContentDescription("Move step " + label + " mm");
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, p.dp(48), 1);
      row.addView(b, lp);
    }
    add(parent, row, 48);
  }

  static String trimNumber(double n) {
    return java.math.BigDecimal.valueOf(n).stripTrailingZeros().toPlainString();
  }

  void homeAxes() {
    confirm(
        "Home all axes?",
        "Clear the printer. The toolhead and bed will move.",
        "Home axes",
        () -> gcode("Homing axes", "G28", Policy.IDLE, null));
  }

  void jog(String axis, double distance) {
    runAction(
        "Moving " + axis,
        Policy.IDLE,
        (s, d) -> {
          JSONObject t = s.getJSONObject("toolhead");
          if (!t.optString("homed_axes").contains(axis.toLowerCase(Locale.US)))
            throw new Exception("Home " + axis + " first");
          int i = "XYZ".indexOf(axis);
          JSONArray pos = t.getJSONArray("position"),
              min = t.getJSONArray("axis_minimum"),
              max = t.getJSONArray("axis_maximum");
          double target = pos.optDouble(i, Double.NaN) + distance;
          if (!Double.isFinite(target) || target < min.getDouble(i) || target > max.getDouble(i))
            throw new Exception("Move is outside " + axis + " travel");
          d.gcode(
              "SAVE_GCODE_STATE NAME=panel_move\nG91\nG0 "
                  + axis
                  + trimNumber(distance)
                  + " F"
                  + (axis.equals("Z") ? 300 : 1200)
                  + "\nRESTORE_GCODE_STATE NAME=panel_move");
        },
        null);
  }

  void filamentPage(LinearLayout body) {
    LinearLayout cols = prepareColumns(body);
    LinearLayout instructions = prepareCard(cols, true);
    prepareHeading(instructions, "Change filament");
    TextView stage = p.text("", 14, PanelUi.MUTED);
    stage.setMaxLines(2);
    bindText(
        stage,
        () ->
            !ready()
                ? "Connect to the printer first."
                : jobState().equals("printing")
                    ? "Pause the print before changing filament."
                    : filamentReady()
                        ? "Nozzle ready. Feed or Retract for fine adjustments."
                        : filamentHeating()
                            ? "Nozzle heating. Filament controls unlock when ready."
                            : "Heat the nozzle to " + filamentTemperature + "° to begin.");
    add(instructions, stage, 32);
    prepareSpace(instructions);
    Button load = prepareButton("Load filament", "filament", 1, () -> filamentMacro(true)),
        unload = prepareButton("Unload filament", null, 0, () -> filamentMacro(false));
    preparePriority(load, () -> !jobState().equals("paused"));
    gate(load, () -> filamentReady() && capable("LOAD_FILAMENT"));
    gate(unload, () -> filamentReady() && capable("UNLOAD_FILAMENT"));
    prepareButtons(instructions, load, unload);
    p.gap(instructions, 8);
    Button feed = prepareButton("Feed 5 mm", null, 0, () -> feed(5)),
        retract = prepareButton("Retract 5 mm", null, 0, () -> feed(-5));
    gate(feed, () -> filamentReady());
    gate(retract, () -> filamentReady());
    prepareButtons(instructions, feed, retract);
    View resumeGap = new View(this);
    instructions.addView(resumeGap, new LinearLayout.LayoutParams(-1, p.dp(8)));
    Button resume = prepareButton("Resume print", "play", 1, () -> pauseResume(() -> show("Home")));
    bindings.add(() -> {
      boolean paused = jobState().equals("paused");
      resume.setVisibility(paused ? View.VISIBLE : View.GONE);
      resumeGap.setVisibility(paused ? View.VISIBLE : View.GONE);
    });
    gate(resume, () -> ready() && jobState().equals("paused"));
    add(instructions, resume, 48);
    LinearLayout heat = prepareCard(cols, false);
    prepareHeading(heat, "Nozzle setup");
    TextView thermalState = p.text("", 12, PanelUi.MUTED);
    bindText(
        thermalState,
        () -> {
          String state =
              !ready()
                  ? "Unavailable"
                  : jobState().equals("printing")
                      ? "Printing"
                      : filamentReady()
                          ? "Ready"
                          : filamentHeating()
                              ? "Heating"
                              : finite(obj("extruder"), "target") > 0 ? "Target set" : "Off";
          return state
              + " · "
              + temperature("extruder")
              + " / "
              + filamentTemperature
              + "° selected";
        });
    bindings.add(() ->
        thermalState.setTextColor(
            filamentReady() ? PanelUi.ACCENT : filamentHeating() ? PanelUi.AMBER : PanelUi.MUTED));
    add(heat, thermalState, 24);
    p.gap(heat, 6);
    Button targetButton =
        prepareButton("Set target", "edit", 0, () -> temperatureSheet("extruder"));
    targetButton.setContentDescription("Set nozzle temperature");
    bindings.add(() -> {
      double n = finite(obj("extruder"), "target");
      targetButton.setText(
          fresh() && Double.isFinite(n) && n > 0 ? value(n, 0) + "° target" : "Set target");
    });
    gate(targetButton, () -> ready() && !manual());
    add(heat, targetButton, 48);
    p.gap(heat, 8);
    LinearLayout materials = p.row();
    materials.setBackground(p.shape(PanelUi.BG, PanelUi.CONTROL_RADIUS));
    for (int target : new int[] {200, 240}) {
      Button b =
          button(
              target == 200 ? "PLA 200°" : "ABS 240°",
              null,
              0,
              () -> {
                filamentTemperature = target;
                updateViews();
              });
      bindings.add(() -> {
        PanelUi.selection(b, filamentTemperature == target, p);
      });
      materials.addView(b, new LinearLayout.LayoutParams(0, p.dp(48), 1));
    }
    add(heat, materials, 48);
    p.gap(heat, 8);
    Button warm =
        prepareButton(
            "Heat nozzle", "heat", 3, () -> setTemperature("extruder", filamentTemperature, null));
    preparePriority(warm, () -> !filamentReady() && !jobState().equals("paused"));
    bindings.add(() ->
        warm.setText(
            filamentReady()
                ? finite(obj("extruder"), "target") == filamentTemperature
                    ? "At temperature"
                    : "Keep at " + filamentTemperature + "°"
                : filamentHeating() ? "Heating…" : "Heat to " + filamentTemperature + "°"));
    gate(
        warm,
        () ->
            ready()
                && !jobState().equals("printing")
                && !manual()
                && (!filamentReady() || finite(obj("extruder"), "target") != filamentTemperature)
                && !filamentHeating());
    add(heat, warm, 48);
  }

  boolean filamentHeating() {
    return ready()
        && !filamentReady()
        && !jobState().equals("printing")
        && finite(obj("extruder"), "temperature") < filamentTemperature - 5
        && finite(obj("extruder"), "target") >= filamentTemperature;
  }

  boolean filamentReady() {
    return ready()
        && !jobState().equals("printing")
        && !manual()
        && obj("extruder").optBoolean("can_extrude")
        && finite(obj("extruder"), "temperature") >= filamentTemperature - 5;
  }

  void filamentMacro(boolean load) {
    String macro = load ? "LOAD_FILAMENT" : "UNLOAD_FILAMENT";
    runAction(
        load ? "Loading filament" : "Unloading filament",
        Policy.FILAMENT,
        (s, d) -> {
          JSONObject hot = s.getJSONObject("extruder");
          if (!hot.optBoolean("can_extrude")
              || finite(hot, "temperature") < filamentTemperature - 5)
            throw new Exception("Heat the nozzle first");
          JSONArray list = s.optJSONArray("_commands");
          boolean supported = false;
          if (list != null)
            for (int i = 0; i < list.length(); i++)
              if (macro.equals(list.optString(i))) supported = true;
          if (!supported) throw new Exception("This printer does not expose " + macro);
          d.gcode(
              "SAVE_GCODE_STATE NAME=panel_filament\n"
                  + macro
                  + "\nRESTORE_GCODE_STATE NAME=panel_filament");
        },
        null);
  }

  void feed(int distance) {
    runAction(
        distance > 0 ? "Feeding filament" : "Retracting filament",
        Policy.FILAMENT,
        (s, d) -> {
          if (!s.getJSONObject("extruder").optBoolean("can_extrude"))
            throw new Exception("Heat the nozzle first");
          d.gcode(
              "SAVE_GCODE_STATE NAME=panel_extrude\nM83\nG1 E"
                  + distance
                  + " F180\nRESTORE_GCODE_STATE NAME=panel_extrude");
        },
        null);
  }

  String bedMode = "";

  void bedPage(LinearLayout body) {
    bedMode = manual() ? "paper" : "setup";
    LinearLayout cols = prepareColumns(body);
    LinearLayout main = prepareCard(cols, true);
    LinearLayout options = prepareCard(cols, false);
    if (manual()) {
      TextView title = p.heading("");
      bindText(
          title,
          () ->
              (obj("y_axis_z_offset").optInt("calibration_point", 0) == 0 ? "Front" : "Rear")
                  + " paper contact");
      add(main, title, 26);
      p.gap(main, 6);
      subtext(
          main,
          "Slide paper under the nozzle. Move closer until it just grips, then accept this point.");
      prepareSpace(main);
      LinearLayout pos = p.row();
      pos.addView(p.label("NOZZLE HEIGHT"), new LinearLayout.LayoutParams(0, -2, 1));
      TextView z = p.text("", 22, PanelUi.TEXT);
      bindText(z, () -> value(finite(obj("manual_probe"), "z_position"), 3) + " mm");
      pos.addView(z);
      add(main, pos, 32);
      p.gap(main, 6);
      Button closer = prepareButton("Closer", null, 0, () -> paperAdjust(-paperStep)),
          further = prepareButton("Further", null, 0, () -> paperAdjust(paperStep));
      gate(closer, this::manual);
      gate(further, this::manual);
      prepareButtons(main, closer, further);
      p.gap(main, 8);
      stepSelector(main, new double[] {.01, .025, .1}, () -> paperStep, v -> paperStep = v);
      prepareHeading(options, "Paper calibration");
      String[] stages = {"Front contact", "Rear contact", "Corrected heightmap"};
      for (int index = 0; index < 3; index++) {
        final int step = index;
        TextView line = p.text("", 13, PanelUi.MUTED);
        line.setPadding(p.dp(8), 0, p.dp(8), 0);
        bindings.add(() -> {
          int point = obj("y_axis_z_offset").optInt("calibration_point", 0);
          boolean selected = step == point;
          line.setText((step < point ? "✓  " : (step + 1) + "  ") + stages[step]);
          if (!Boolean.valueOf(selected).equals(line.getTag())) {
            line.setTag(selected);
            line.setTextColor(selected ? PanelUi.ACCENT : PanelUi.MUTED);
            line.setBackground(
                p.shape(selected ? PanelUi.ACCENT_BG : PanelUi.SURFACE, PanelUi.CONTROL_RADIUS));
          }
        });
        add(options, line, 28);
      }
      prepareSpace(options);
      Button accept = prepareButton("Accept contact", "check", 1, this::acceptPaper);
      gate(accept, this::manual);
      add(options, accept, 48);
      p.gap(options, 8);
      Button abort =
          prepareButton(
              "Abort calibration",
              null,
              2,
              () ->
                  confirm(
                      "Abort the paper test?",
                      "The new contact measurements will not be saved.",
                      "Abort calibration",
                      () ->
                          gcode(
                              "Aborting calibration",
                              "ABORT",
                              Policy.MANUAL,
                              () -> {
                                calibrationRequested = false;
                                calibrationResult = "Calibration aborted";
                              })));
      gate(abort, this::manual);
      add(options, abort, 48);
    } else {
      prepareHeading(main, "Front / rear bed calibration");
      subtext(
          main,
          "Clean the nozzle and clear the bed. A mesh comes first, then front and rear paper"
              + " contacts.");
      p.gap(main, 8);
      TextView phase = p.text("", 14, PanelUi.ACCENT);
      bindText(
          phase,
          () ->
              calibrationRequested
                  ? commandBusy && actionLabel.contains("rear")
                      ? "Rebuilding the corrected heightmap…"
                      : "Creating the initial heightmap…"
                  : calibrationResult);
      bindings.add(
          () -> phase.setVisibility(phase.getText().length() == 0 ? View.GONE : View.VISIBLE));
      bindings.add(() -> phase.setTextColor(calibrationResultColor));
      add(main, phase, 40);
      prepareSpace(main);
      Button start =
          prepareButton(
              "Start paper calibration",
              "calibrate",
              1,
              () ->
                  confirm(
                      "Start bed calibration?",
                      "The printer will home and probe a "
                          + probeCount
                          + " × "
                          + probeCount
                          + " mesh before the paper tests. Clear the bed and clean the nozzle.",
                      "Home & calibrate",
                      () -> {
                        calibrationRequested = true;
                        calibrationResult = "";
                        calibrationResultColor = PanelUi.ACCENT;
                        gcode(
                            "Starting bed calibration",
                            "CALIBRATE_BED_TILT PROBE_COUNT=" + probeCount,
                            Policy.IDLE,
                            () -> tell("Bed calibration · follow the paper test", PanelUi.ACCENT));
                      }));
      gate(start, () -> idle() && capable("CALIBRATE_BED_TILT"));
      add(main, start, 48);
      p.gap(main, 8);
      TextView result =
          p.text("Both contacts save the correction and refresh the heightmap.", 12, PanelUi.MUTED);
      result.setMaxLines(2);
      add(main, result, 34);
      prepareHeading(options, "Mesh detail");
      LinearLayout choices = p.row();
      choices.setBackground(p.shape(PanelUi.BG, PanelUi.CONTROL_RADIUS));
      for (int n : new int[] {5, 9, 13}) {
        Button b =
            button(
                n + " × " + n,
                null,
                0,
                () -> {
                  probeCount = n;
                  updateViews();
                });
        b.setTextSize(12);
        b.setPadding(0, 0, 0, 0);
        bindings.add(() -> {
          PanelUi.selection(b, probeCount == n, p);
        });
        choices.addView(b, new LinearLayout.LayoutParams(0, p.dp(48), 1));
      }
      add(options, choices, 48);
      p.gap(options, 8);
      TextView hint =
          p.text("After adjusting bed screws, refresh the corrected heightmap.", 12, PanelUi.MUTED);
      hint.setMaxLines(2);
      add(options, hint, 34);
      prepareSpace(options);
      Button mesh =
          prepareButton(
              "Refresh heightmap",
              "refresh",
              0,
              () ->
                  confirm(
                      "Refresh the heightmap?",
                      "The printer will home and probe. Existing paper-calibration values are"
                          + " preserved.",
                      "Home & probe",
                      () ->
                          gcode(
                              "Refreshing heightmap",
                              "CALIBRATE_COMPENSATED_BED_MESH PROBE_COUNT=" + probeCount,
                              Policy.IDLE,
                              null)));
      gate(mesh, () -> idle() && capable("CALIBRATE_COMPENSATED_BED_MESH"));
      add(options, mesh, 48);
      p.gap(options, 8);
      Button viewMesh = prepareButton("View heightmap", "calibrate", 0, this::heightmapSheet);
      gate(viewMesh, this::ready);
      add(options, viewMesh, 48);
    }
  }

  void paperAdjust(double amount) {
    gcode("Adjusting paper contact", "TESTZ Z=" + trimNumber(amount), Policy.MANUAL, null);
  }

  void heightmapSheet() {
    LinearLayout body = sheet("Active heightmap");
    JSONArray matrix = obj("bed_mesh").optJSONArray("probed_matrix");
    BedMeshView map = new BedMeshView(this, matrix);
    LinearLayout summary = p.row();
    TextView offsets =
        p.text(
            "Saved correction\nFront "
                + value(finite(obj("y_axis_z_offset"), "front_offset"), 3)
                + " · Rear "
                + value(finite(obj("y_axis_z_offset"), "rear_offset"), 3)
                + " mm",
            12,
            PanelUi.MUTED);
    summary.addView(offsets, new LinearLayout.LayoutParams(0, -1, 1));
    TextView range = p.text(map.summary(), 12, PanelUi.ACCENT);
    range.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
    summary.addView(range, new LinearLayout.LayoutParams(0, -1, 1));
    add(body, summary, 32);
    add(body, map, 170);
  }

  void acceptPaper() {
    boolean rear = obj("y_axis_z_offset").optInt("calibration_point", 0) == 1;
    if (!rear) {
      gcode("Accepting front contact", "ACCEPT", Policy.MANUAL, null);
      return;
    }
    final String[] result = {"Completion not confirmed. Check printer status before retrying."};
    final int[] color = {PanelUi.AMBER};
    runAction(
        "Accepting rear contact",
        Policy.MANUAL,
        (s, d) -> {
          double boundary = Double.NaN;
          try {
            JSONArray before =
                d.request("GET", "/server/gcode_store?count=1000", null)
                    .getJSONObject("result")
                    .getJSONArray("gcode_store");
            boundary = 0;
            for (int i = 0; i < before.length(); i++)
              boundary = Math.max(boundary, before.getJSONObject(i).optDouble("time", 0));
          } catch (Exception ignored) {
          }
          try {
            d.gcode("ACCEPT");
          } catch (Exception ignored) {
            // A timeout may follow a completed physical operation. Never replay it or
            // claim that nothing happened merely because its response was lost.
          }
          if (Double.isNaN(boundary)) return;
          try {
            JSONArray after =
                d.request("GET", "/server/gcode_store?count=1000", null)
                    .getJSONObject("result")
                    .getJSONArray("gcode_store");
            boolean contactsAccepted = false;
            for (int i = 0; i < after.length(); i++) {
              JSONObject entry = after.getJSONObject(i);
              if (entry.optDouble("time", 0) <= boundary) continue;
              String text = entry.optString("message");
              if (text.contains("offsets were not changed")) {
                result[0] =
                    "Correction exceeded the safety limit. Previous offsets kept; repeat the paper"
                        + " test.";
                color[0] = PanelUi.DANGER;
                return;
              }
              if (text.contains("Paper calibration complete: front")) contactsAccepted = true;
              if (text.contains("Front/rear Z calibration complete. The displayed heightmap")) {
                result[0] = "Calibration saved · corrected heightmap rebuilt";
                color[0] = PanelUi.ACCENT;
                return;
              }
            }
            if (contactsAccepted)
              result[0] =
                  "Contacts accepted. Saving and heightmap completion unconfirmed; check printer"
                      + " status.";
          } catch (Exception ignored) {
          }
        },
        () -> {
          calibrationRequested = false;
          calibrationResult = result[0];
          calibrationResultColor = color[0];
          tell(calibrationResult, calibrationResultColor);
        });
  }

  LinearLayout sheet(String title) {
    return sheet(title, 560);
  }

  LinearLayout sheet(String title, int widthDp) {
    if (activeDialog != null) activeDialog.dismiss();
    dialogBindings.clear();
    Dialog d = new Dialog(this);
    LinearLayout frame = p.panel();
    p.pad(frame, PanelUi.SHEET_INSET, PanelUi.SHEET_INSET);
    LinearLayout header = p.row();
    header.addView(p.heading(title), new LinearLayout.LayoutParams(0, -2, 1));
    Button close = button("Close", null, 0, d::dismiss);
    header.addView(close, new LinearLayout.LayoutParams(p.dp(72), p.dp(48)));
    frame.addView(header);
    p.gap(frame, 16);
    LinearLayout body = p.col();
    final LinearLayout footer = p.col();
    footer.setVisibility(View.GONE);
    footer.setPadding(0, p.dp(8), 0, 0);
    dialogFooter = footer;
    ScrollView scroll =
        new ScrollView(this) {
          @Override
          protected void onMeasure(int width, int height) {
            int cap =
                Math.max(
                    p.dp(80),
                    getResources().getDisplayMetrics().heightPixels
                        - screenTopInset
                        - screenBottomInset
                        - p.dp(208)
                        - (footer.getVisibility() == View.GONE ? 0 : p.dp(56)));
            int size =
                View.MeasureSpec.getMode(height) == View.MeasureSpec.UNSPECIFIED
                    ? cap
                    : Math.min(View.MeasureSpec.getSize(height), cap);
            super.onMeasure(
                width, View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.AT_MOST));
          }
        };
    scroll.setFillViewport(false);
    scroll.setVerticalFadingEdgeEnabled(true);
    scroll.setFadingEdgeLength(p.dp(12));
    scroll.addView(body);
    frame.addView(scroll);
    frame.addView(footer);
    dialogFeedback = p.text("", 13, PanelUi.MUTED);
    dialogFeedback.setMaxLines(2);
    dialogFeedback.setEllipsize(TextUtils.TruncateAt.END);
    dialogNotice = "";
    LinearLayout.LayoutParams feedbackSpace = new LinearLayout.LayoutParams(-1, -2);
    feedbackSpace.topMargin = p.dp(8);
    frame.addView(dialogFeedback, feedbackSpace);
    d.setContentView(frame);
    d.getWindow().setBackgroundDrawable(p.shape(PanelUi.SURFACE, PanelUi.SHEET_RADIUS));
    d.getWindow()
        .setLayout(
            Math.min(
                p.dp(widthDp),
                getResources().getDisplayMetrics().widthPixels
                    - screenLeftInset
                    - screenRightInset
                    - p.dp(84)),
            -2);
    d.getWindow().setGravity(Gravity.TOP | Gravity.RIGHT);
    WindowManager.LayoutParams placement = d.getWindow().getAttributes();
    placement.x = screenRightInset + p.dp(8);
    placement.y = screenTopInset + p.dp(64);
    d.getWindow().setAttributes(placement);
    d.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    d.setCanceledOnTouchOutside(false);
    d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
    d.setOnDismissListener(v -> {
      if (activeDialog == d) {
        activeDialog = null;
        dialogFeedback = null;
        dialogFooter = null;
        dialogBindings.clear();
        sheetFocus(false);
      }
    });
    activeDialog = d;
    d.show();
    sheetFocus(true);
    return body;
  }

  void dismissSheet() {
    if (activeDialog != null) activeDialog.dismiss();
  }

  void information(String title, String message) {
    LinearLayout body = sheet(title);
    TextView text = p.text(message, 14, PanelUi.MUTED);
    body.addView(text);
  }

  void confirm(String title, String detail, String action, Runnable run) {
    if (fastControls()) {
      dismissSheet();
      Toast.makeText(this, detail, Toast.LENGTH_LONG).show();
      run.run();
      return;
    }
    LinearLayout body = sheet(title);
    subtext(body, detail);
    p.gap(body, 18);
    rowButtons(
        body,
        button("Back", null, 0, this::dismissSheet),
        button(
            action,
            null,
            action.startsWith("Cancel") || action.startsWith("Abort") ? 2 : 1,
            () -> {
              dismissSheet();
              run.run();
            }));
  }

  void dialogGate(Button button, BooleanSupplier predicate) {
    dialogBindings.add(() -> button.setEnabled(!commandBusy && predicate.getAsBoolean()));
    button.setEnabled(!commandBusy && predicate.getAsBoolean());
  }

  boolean fastControls() {
    return getSharedPreferences("panel", 0).getBoolean("fast_controls", false);
  }

  double heaterMax(JSONObject current, String heater) {
    JSONObject config = current.optJSONObject("configfile");
    JSONObject settings = config == null ? null : config.optJSONObject("settings");
    JSONObject device = settings == null ? null : settings.optJSONObject(heater);
    return device == null ? Double.NaN : finite(device, "max_temp");
  }

  void setTemperature(String heater, double target, Runnable success) {
    Runnable send =
        () ->
            runAction(
                "Setting "
                    + (heater.equals("extruder") ? "nozzle" : "bed")
                    + " to "
                    + value(target, 0)
                    + "°",
                Policy.READY,
                (s, d) -> {
                  double max = heaterMax(s, heater);
                  if (!Double.isFinite(target)
                      || !Double.isFinite(max)
                      || target < 0
                      || target > max)
                    throw new Exception("Target is outside the printer's temperature limits");
                  d.gcode(
                      "SET_HEATER_TEMPERATURE HEATER=" + heater + " TARGET=" + trimNumber(target));
                },
                () -> {
                  if (heater.equals("extruder")
                      && target > 0
                      && page.equals("Prepare")
                      && prepareTab.equals("Filament"))
                    filamentTemperature = (int) Math.round(target);
                  if (success != null) success.run();
                });
    if (target == 0 && activePrint())
      confirm(
          "Turn this heater off?",
          "This changes the heater target during the current print.",
          "Turn heater off",
          send);
    else send.run();
  }

  void temperatureSheet(String heater) {
    LinearLayout body =
        sheet(heater.equals("extruder") ? "Nozzle temperature" : "Bed temperature", 420);
    double max = heaterMax(status, heater);
    if (!ready() || !Double.isFinite(max)) {
      subtext(body, "Connect to the printer to read its temperature limits.");
      return;
    }
    final double[] target = {finite(obj(heater), "target")};
    if (!Double.isFinite(target[0])) target[0] = 0;
    TextView current = p.text("Current " + temperature(heater), 14, PanelUi.MUTED);
    body.addView(current);
    p.gap(body, 10);
    LinearLayout adjust = p.row();
    adjust.addView(
        p.text("Target", 15, PanelUi.TEXT), new LinearLayout.LayoutParams(0, p.dp(48), 1));
    Button
        minus =
            button(
                "−5°",
                null,
                0,
                () -> {
                  target[0] = Math.max(0, target[0] - 5);
                  updateViews();
                }),
        plus =
            button(
                "+5°",
                null,
                0,
                () -> {
                  target[0] = Math.min(max, target[0] + 5);
                  updateViews();
                });
    p.pad(minus, 4, 8);
    p.pad(plus, 4, 8);
    adjust.addView(minus, new LinearLayout.LayoutParams(p.dp(56), p.dp(48)));
    TextView number = p.text("", 26, PanelUi.TEXT);
    number.setGravity(Gravity.CENTER);
    adjust.addView(number, new LinearLayout.LayoutParams(p.dp(72), p.dp(48)));
    adjust.addView(plus, new LinearLayout.LayoutParams(p.dp(56), p.dp(48)));
    body.addView(adjust);
    p.gap(body, 12);
    LinearLayout presets = p.row();
    int[] choices =
        heater.equals("extruder") ? new int[] {0, 170, 200, 240} : new int[] {0, 60, 100};
    for (int n : choices) {
      if (n > max) continue;
      Button b =
          button(
              n == 0 ? "Off" : n + "°",
              null,
              0,
              () -> {
                target[0] = n;
                updateViews();
              });
      p.weighted(presets, b, 48);
      dialogBindings.add(() -> {
        PanelUi.selection(b, target[0] == n, p);
      });
    }
    add(body, presets, 48);
    p.gap(body, 12);
    Button apply =
        button(
            "Set target", "check", 1, () -> setTemperature(heater, target[0], this::dismissSheet));
    dialogGate(apply, this::ready);
    dialogBindings.add(() -> {
      number.setText(value(target[0], 0) + "°");
      apply.setText("Set " + value(target[0], 0) + "°");
      current.setText("Current " + temperature(heater) + " · Max " + (int) max + "°");
    });
    rowButtons(
        body,
        button(
            "Custom target",
            null,
            0,
            () ->
                numberSheet(
                    "Temperature target",
                    target[0],
                    0,
                    max,
                    v -> setTemperature(heater, v, this::dismissSheet))),
        apply);
    updateViews();
  }

  void numberSheet(String title, double initial, double min, double max, DoubleConsumer accept) {
    LinearLayout body = sheet(title, 420);
    EditText input = new EditText(this);
    input.setTextColor(PanelUi.TEXT);
    input.setTextSize(24);
    input.setSingleLine(true);
    input.setInputType(
        android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
    input.setText(trimNumber(Double.isFinite(initial) ? initial : 0));
    input.selectAll();
    input.setContentDescription(title);
    input.setBackground(p.outline(PanelUi.RAISED));
    p.pad(input, 12, 8);
    add(body, input, 56);
    p.gap(body, 8);
    subtext(body, trimNumber(min) + " – " + trimNumber(max));
    p.gap(body, 12);
    add(
        body,
        button(
            "Apply",
            "check",
            1,
            () -> {
              try {
                double v = Double.parseDouble(input.getText().toString());
                if (!Double.isFinite(v) || v < min || v > max) throw new IllegalArgumentException();
                accept.accept(v);
              } catch (Exception e) {
                input.setError("Use " + trimNumber(min) + " – " + trimNumber(max));
              }
            }),
        48);
  }

  void preheatSheet() {
    LinearLayout body = sheet("Temperature presets");
    subtext(body, "Sets nozzle and bed together. Individual targets are on Home.");
    p.gap(body, 14);
    Button pla = button("Heat PLA · 200° / 60°", "heat", 1, () -> preheat(200, 60)),
        abs = button("Heat ABS · 240° / 100°", "heat", 0, () -> preheat(240, 100));
    dialogGate(pla, () -> idle() && !manual());
    dialogGate(abs, () -> idle() && !manual());
    rowButtons(body, pla, abs);
    p.gap(body, 14);
    Button cool =
        button(
            "Turn heaters off",
            null,
            0,
            () ->
                gcode("Turning heaters off", "TURN_OFF_HEATERS", Policy.IDLE, this::dismissSheet));
    dialogGate(cool, this::idle);
    add(body, cool, 48);
    if (activePrint())
      subtext(
          body,
          "Presets and cooldown are locked during printing. Edit an individual target instead.");
  }

  void preheat(int nozzle, int bed) {
    runAction(
        "Preheating printer",
        Policy.IDLE,
        (s, d) -> {
          if (nozzle > heaterMax(s, "extruder") || bed > heaterMax(s, "heater_bed"))
            throw new Exception("Preset exceeds printer limits");
          d.gcode(
              "SET_HEATER_TEMPERATURE HEATER=extruder TARGET="
                  + nozzle
                  + "\nSET_HEATER_TEMPERATURE HEATER=heater_bed TARGET="
                  + bed);
        },
        this::dismissSheet);
  }

  void adjustZ(double amount) {
    runAction(
        "Adjusting Z",
        Policy.READY,
        (s, d) -> {
          String state = s.getJSONObject("print_stats").optString("state");
          if (!Arrays.asList("printing", "paused").contains(state))
            throw new Exception("Live Z adjustment is only available during a print");
          if (!s.getJSONObject("toolhead").optString("homed_axes").contains("xyz"))
            throw new Exception("Home all axes first");
          d.gcode("SET_GCODE_OFFSET Z_ADJUST=" + trimNumber(amount) + " MOVE=1");
        },
        null);
  }

  void tuningSheet() {
    LinearLayout body = sheet("Tune this print", 420);
    subtext(body, "Adjust the current printer session.");
    p.gap(body, 12);
    tuneRow(body, "Speed", "speed_factor", 10, 50, 150, "M220 S");
    p.gap(body, 10);
    tuneRow(body, "Flow", "extrude_factor", 5, 50, 150, "M221 S");
    p.gap(body, 10);
    tuneRow(body, "Part fan", "fan", 10, 0, 100, "M106 S");
    updateViews();
  }

  double tuneValue(JSONObject s, String field) {
    JSONObject o = s.optJSONObject(field.equals("fan") ? "fan" : "gcode_move");
    return o == null ? Double.NaN : finite(o, field.equals("fan") ? "speed" : field) * 100;
  }

  void tuneRow(
      LinearLayout body, String title, String field, int step, int min, int max, String prefix) {
    buildTuneRow(body, title, field, step, min, max, prefix, true);
  }

  void inlineTuneRow(
      LinearLayout body, String title, String field, int step, int min, int max, String prefix) {
    buildTuneRow(body, title, field, step, min, max, prefix, false);
  }

  void buildTuneRow(
      LinearLayout body,
      String title,
      String field,
      int step,
      int min,
      int max,
      String prefix,
      boolean dialog) {
    LinearLayout row = p.row();
    row.addView(p.text(title, 15, PanelUi.TEXT), new LinearLayout.LayoutParams(0, p.dp(48), 1));
    Button minus = button("−" + step, null, 0, () -> adjustTune(field, -step, min, max, prefix)),
        plus = button("+" + step, null, 0, () -> adjustTune(field, step, min, max, prefix));
    p.pad(minus, 4, 8);
    p.pad(plus, 4, 8);
    int buttonWidth = dialog ? 56 : 48;
    LinearLayout controls = p.row();
    controls.setBackground(p.shape(PanelUi.RAISED, PanelUi.CONTROL_RADIUS));
    minus.setBackground(p.buttonBackground(4));
    plus.setBackground(p.buttonBackground(4));
    controls.addView(minus, new LinearLayout.LayoutParams(p.dp(buttonWidth), p.dp(48)));
    TextView number = p.text("", dialog ? 20 : 18, PanelUi.ACCENT);
    number.setGravity(Gravity.CENTER);
    controls.addView(number, new LinearLayout.LayoutParams(p.dp(dialog ? 72 : 62), p.dp(48)));
    controls.addView(plus, new LinearLayout.LayoutParams(p.dp(buttonWidth), p.dp(48)));
    row.addView(controls);
    Runnable refresh =
        () -> {
          String current = fresh() ? value(tuneValue(status, field), 0) + "%" : "—";
          number.setText(current);
          number.setContentDescription(title + " " + current);
        };
    (dialog ? dialogBindings : bindings).add(refresh);
    refresh.run();
    minus.setContentDescription(
        "Decrease " + title.toLowerCase(Locale.US) + " by " + step + " percent");
    plus.setContentDescription(
        "Increase " + title.toLowerCase(Locale.US) + " by " + step + " percent");
    BooleanSupplier canDecrease =
        () ->
            ready() && Double.isFinite(tuneValue(status, field)) && tuneValue(status, field) > min;
    BooleanSupplier canIncrease =
        () ->
            ready() && Double.isFinite(tuneValue(status, field)) && tuneValue(status, field) < max;
    if (dialog) {
      dialogGate(minus, canDecrease);
      dialogGate(plus, canIncrease);
    } else {
      gate(minus, canDecrease);
      gate(plus, canIncrease);
    }
    body.addView(row);
  }

  void adjustTune(String field, int step, int min, int max, String prefix) {
    runAction(
        "Adjusting "
            + (field.equals("fan") ? "part fan" : field.equals("speed_factor") ? "speed" : "flow"),
        Policy.READY,
        (s, d) -> {
          double current = tuneValue(s, field);
          if (!Double.isFinite(current)) throw new Exception("Use " + min + "–" + max + "%");
          double n = Math.max(min, Math.min(max, current + step));
          d.gcode(prefix + Math.round(field.equals("fan") ? n * 2.55 : n));
        },
        null);
  }

  void fanSheet() {
    LinearLayout body = sheet("Part cooling fan");
    tuneRow(body, "Fan", "fan", 10, 0, 100, "M106 S");
  }

  void filesPage() {
    LinearLayout tools = p.row();
    EditText search = new EditText(this);
    search.setSingleLine(true);
    search.setTextSize(14);
    search.setTextColor(PanelUi.TEXT);
    search.setHintTextColor(PanelUi.MUTED);
    search.setHint("Find a stored print");
    search.setText(fileFilter);
    search.setBackground(p.shape(PanelUi.RAISED, PanelUi.CONTROL_RADIUS));
    p.pad(search, 12, 8);
    tools.addView(search, new LinearLayout.LayoutParams(0, p.dp(48), 1));
    Button refresh = button("Refresh", "refresh", 0, this::fetchFiles);
    LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(p.dp(120), p.dp(48));
    rp.leftMargin = p.dp(10);
    tools.addView(refresh, rp);
    add(workspace, tools, 48);
    p.gap(workspace, 10);
    fileList = scroll(workspace);
    search.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int a, int c, int f) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            fileFilter = s.toString();
            renderFiles();
          }

          public void afterTextChanged(Editable s) {}
        });
    renderFiles();
    fetchFiles();
  }

  void fetchFiles() {
    if (filesFetching) return;
    filesFetching = true;
    lastFileRequest = SystemClock.elapsedRealtime();
    final PrinterClient target = client;
    final long generation = connectionGeneration;
    final long refreshGeneration = ++fileRefreshGeneration;
    fileReads.execute(() -> {
      try {
        JSONArray data =
            target.request("GET", "/server/files/list?root=gcodes", null).getJSONArray("result");
        ArrayList<JSONObject> next = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
          JSONObject f = data.getJSONObject(i);
          if (f.optString("path").matches("(?i).+\\.(gcode|gco|g)$")) next.add(f);
        }
        next.sort((a, b) -> Double.compare(b.optDouble("modified"), a.optDouble("modified")));
        ui.post(() -> {
          if (destroyed
              || target != client
              || generation != connectionGeneration
              || refreshGeneration != fileRefreshGeneration) return;
          Set<String> names = new HashSet<>();
          for (JSONObject file : next) {
            String name = file.optString("path");
            names.add(name);
            JSONObject previous = listedFile(name);
            if (previous == null || !fileIdentity(previous).equals(fileIdentity(file))) {
              metadata.remove(name);
              thumbnails.remove(name);
            }
          }
          metadata.keySet().retainAll(names);
          thumbnails.keySet().retainAll(names);
          filesFetching = false;
          fileError = "";
          files = next;
          JSONObject currentPlate = listedFile(obj("print_stats").optString("filename"));
          if (currentPlate != null) requestFileMetadata(currentPlate, null);
          renderFiles();
          preloadFileMetadata(next, 0, target, generation, refreshGeneration);
        });
      } catch (Exception e) {
        ui.post(() -> {
          if (destroyed
              || target != client
              || generation != connectionGeneration
              || refreshGeneration != fileRefreshGeneration) return;
          filesFetching = false;
          fileError = "Could not read stored prints";
          if (page.equals("Files") && fileList != null) {
            fileList.removeAllViews();
            subtext(fileList, "Could not load stored prints. Check the connection, then refresh.");
          }
          tell("Files · " + message(e), PanelUi.AMBER);
        });
      } finally {
        ui.post(() -> {
          if (refreshGeneration == fileRefreshGeneration) filesFetching = false;
        });
      }
    });
  }

  JSONObject listedFile(String name) {
    for (JSONObject file : files) if (name.equals(file.optString("path"))) return file;
    return null;
  }

  String fileIdentity(JSONObject file) {
    return String.valueOf(file.opt("size")) + ":" + String.valueOf(file.opt("modified"));
  }

  void preloadFileMetadata(
      ArrayList<JSONObject> list,
      int index,
      PrinterClient target,
      long generation,
      long refreshGeneration) {
    if (destroyed
        || target != client
        || generation != connectionGeneration
        || refreshGeneration != fileRefreshGeneration
        || index >= Math.min(12, list.size())) return;
    requestFileMetadata(
        list.get(index),
        () -> preloadFileMetadata(list, index + 1, target, generation, refreshGeneration));
  }

  void requestFileMetadata(JSONObject file, Runnable complete) {
    final String name = file.optString("path"), identity = fileIdentity(file);
    final PrinterClient target = client;
    final long generation = connectionGeneration;
    final String requestKey = generation + "\n" + name + "\n" + identity;
    JSONObject current = listedFile(name);
    if (destroyed
        || current == null
        || !identity.equals(fileIdentity(current))
        || metadata.containsKey(name)
        || !fileMetadataRequests.add(requestKey)) {
      if (complete != null) complete.run();
      return;
    }
    fileReads.execute(() -> {
      JSONObject result = null;
      Bitmap preview = null;
      try {
        result =
            target
                .request(
                    "GET",
                    "/server/files/metadata?filename=" + URLEncoder.encode(name, "UTF-8"),
                    null)
                .getJSONObject("result");
        try {
          preview = thumbnail(target, name, result);
        } catch (Exception ignored) {
          /* File details are still useful without a preview. */
        }
      } catch (Exception ignored) {
        /* Selecting the file again retries unavailable metadata. */
      }
      final JSONObject meta = result;
      final Bitmap bitmap = preview;
      ui.post(() -> {
        fileMetadataRequests.remove(requestKey);
        JSONObject latest = listedFile(name);
        if (!destroyed
            && target == client
            && generation == connectionGeneration
            && latest != null
            && identity.equals(fileIdentity(latest))
            && meta != null) {
          metadata.put(name, meta);
          if (bitmap != null) thumbnails.put(name, bitmap);
          else thumbnails.remove(name);
          renderFiles();
        }
        if (!destroyed && complete != null) complete.run();
      });
    });
  }

  Bitmap thumbnail(PrinterClient target, String filename, JSONObject meta) throws Exception {
    JSONArray variants = meta.optJSONArray("thumbnails");
    if (variants == null || variants.length() == 0) return null;
    JSONObject chosen = null;
    for (int i = 0; i < variants.length(); i++) {
      JSONObject option = variants.getJSONObject(i);
      if (chosen == null || option.optInt("width") > chosen.optInt("width")) chosen = option;
    }
    String parent =
        filename.contains("/") ? filename.substring(0, filename.lastIndexOf('/') + 1) : "";
    String relative = chosen.optString("relative_path");
    if (relative.isEmpty() || relative.contains("..")) return null;
    HttpURLConnection c =
        target.open("GET", "/server/files/gcodes/" + encodedPath(parent + relative));
    try {
      if (c.getResponseCode() != 200) return null;
      try (java.io.InputStream in = c.getInputStream();
          java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
        byte[] b = new byte[8192];
        int count;
        while ((count = in.read(b)) >= 0) {
          if (bytes.size() + count > 1024 * 1024) return null;
          bytes.write(b, 0, count);
        }
        byte[] data = bytes.toByteArray();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = chosen.optInt("width") > 320 ? 2 : 1;
        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
      }
    } finally {
      c.disconnect();
    }
  }

  static String encodedPath(String path) throws Exception {
    StringBuilder result = new StringBuilder();
    for (String part : path.split("/")) {
      if (result.length() > 0) result.append('/');
      result.append(URLEncoder.encode(part, "UTF-8").replace("+", "%20"));
    }
    return result.toString();
  }

  void renderFiles() {
    if (!page.equals("Files") || fileList == null) return;
    fileList.removeAllViews();
    int matches = 0;
    for (JSONObject file : files) {
      String name = file.optString("path");
      if (!name.toLowerCase(Locale.ROOT).contains(fileFilter.toLowerCase(Locale.ROOT))) continue;
      matches++;
      LinearLayout row = p.row();
      p.pad(row, 10, 6);
      row.setBackground(
          new android.graphics.drawable.RippleDrawable(
              android.content.res.ColorStateList.valueOf(PanelUi.RIPPLE),
              p.shape(PanelUi.SURFACE, PanelUi.CARD_RADIUS),
              null));
      ImageView image = new ImageView(this);
      Bitmap thumb = thumbnails.get(name);
      if (thumb != null) {
        image.setImageBitmap(thumb);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
      } else image.setImageDrawable(new PanelUi.Icon("files", PanelUi.MUTED));
      row.addView(image, new LinearLayout.LayoutParams(p.dp(48), p.dp(48)));
      LinearLayout labels = p.col();
      labels.setPadding(p.dp(14), 0, p.dp(10), 0);
      TextView title = p.text(friendly(name), 16, PanelUi.TEXT);
      title.setMaxLines(2);
      title.setEllipsize(TextUtils.TruncateAt.END);
      labels.addView(title);
      p.gap(labels, 5);
      labels.addView(p.text(fileSummary(file, metadata.get(name)), 12, PanelUi.MUTED));
      row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
      ImageView arrow = new ImageView(this);
      arrow.setImageDrawable(new PanelUi.Icon("arrow", PanelUi.MUTED));
      row.addView(arrow, new LinearLayout.LayoutParams(p.dp(20), p.dp(20)));
      row.setContentDescription("Print details: " + name);
      row.setOnClickListener(v -> fileDetails(file));
      add(fileList, row, 68);
      p.gap(fileList, 8);
    }
    if (matches == 0)
      subtext(
          fileList,
          files.isEmpty()
              ? filesFetching
                  ? "Loading stored prints…"
                  : !fileError.isEmpty()
                      ? fileError + ". Refresh to retry."
                      : "No stored prints yet. Send a plate from Orca, then refresh."
              : "No prints match this search.");
  }

  String fileSummary(JSONObject file, JSONObject meta) {
    ArrayList<String> parts = new ArrayList<>();
    if (meta != null) {
      double time = finite(meta, "estimated_time");
      if (Double.isFinite(time) && time > 0) parts.add(duration(time));
      String material = meta.optString("filament_type", "");
      if (!material.isEmpty()) parts.add(material);
    }
    if (parts.isEmpty()) {
      double size = finite(file, "size");
      parts.add(
          Double.isFinite(size) && size >= 0
              ? String.format(Locale.US, "%.1f MB", size / 1048576)
              : "Size unavailable");
    }
    double timestamp = finite(file, "modified");
    if (Double.isFinite(timestamp) && timestamp > 0)
      parts.add(
          android.text.format.DateFormat.format("MMM d", (long) (timestamp * 1000)).toString());
    return String.join(" · ", parts);
  }

  void fileDetails(JSONObject file) {
    String name = file.optString("path");
    LinearLayout body = sheet("Print details", 560);
    LinearLayout overview = p.row();
    ImageView image = new ImageView(this);
    Bitmap bitmap = thumbnails.get(name);
    if (bitmap != null) image.setImageBitmap(bitmap);
    else image.setImageDrawable(new PanelUi.Icon("files", PanelUi.MUTED));
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    overview.addView(image, new LinearLayout.LayoutParams(p.dp(100), p.dp(76)));
    LinearLayout details = p.col();
    details.setPadding(p.dp(16), 0, 0, 0);
    TextView title = p.text(friendly(name), 19, PanelUi.TEXT);
    title.setMaxLines(2);
    title.setEllipsize(TextUtils.TruncateAt.END);
    details.addView(title);
    p.gap(details, 8);
    TextView summary = p.text(fileSummary(file, metadata.get(name)), 13, PanelUi.MUTED);
    summary.setLineSpacing(p.dp(2), 1);
    details.addView(summary);
    final Bitmap[] displayed = {bitmap};
    final String[] requestedIdentity = {fileIdentity(file)};
    dialogBindings.add(() -> {
      JSONObject latest = listedFile(name);
      if (latest != null && !requestedIdentity[0].equals(fileIdentity(latest))) {
        requestedIdentity[0] = fileIdentity(latest);
        requestFileMetadata(latest, null);
      }
      summary.setText(
          latest == null
              ? "File no longer listed. Refresh stored prints."
              : fileSummary(latest, metadata.get(name)));
      Bitmap next = thumbnails.get(name);
      if (displayed[0] != next) {
        displayed[0] = next;
        if (next != null) image.setImageBitmap(next);
        else image.setImageDrawable(new PanelUi.Icon("files", PanelUi.MUTED));
      }
    });
    overview.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
    body.addView(overview);
    p.gap(body, 8);
    TextView path = p.text(name, 12, PanelUi.MUTED);
    path.setMaxLines(2);
    path.setEllipsize(TextUtils.TruncateAt.MIDDLE);
    body.addView(path);
    p.gap(body, 8);
    subtext(body, "Check that the plate is clear and prepared before starting.");
    p.gap(body, 8);
    Button start =
        button(
            "Start print",
            "play",
            1,
            () ->
                runAction(
                    "Starting print",
                    Policy.IDLE,
                    (s, d) ->
                        d.request(
                            "POST", "/printer/print/start", new JSONObject().put("filename", name)),
                    () -> {
                      dismissSheet();
                      show("Home");
                    }));
    dialogGate(start, () -> idle() && listedFile(name) != null);
    dialogBindings.add(() -> start.setText(commandBusy ? "Starting…" : "Start print"));
    dialogFooter.setVisibility(View.VISIBLE);
    add(dialogFooter, start, 48);
    if (!idle()) subtext(body, "Starting is available when the printer is ready and idle.");
    requestFileMetadata(file, null);
  }

  void cameraPage() {
    LinearLayout cols = p.row();
    cols.setGravity(Gravity.TOP);
    FrameLayout preview = new FrameLayout(this);
    preview.setBackground(p.shape(PanelUi.BG, PanelUi.CARD_RADIUS));
    preview.setClipToOutline(true);
    cameraImage = new ImageView(this);
    cameraImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
    cameraImage.setRotation(
        getWindowManager().getDefaultDisplay().getRotation() == Surface.ROTATION_270 ? 180 : 0);
    preview.addView(cameraImage, new FrameLayout.LayoutParams(-1, -1));
    cameraOverlay = p.text("Camera off", 18, PanelUi.MUTED);
    cameraOverlay.setGravity(Gravity.CENTER);
    preview.addView(cameraOverlay, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0, -1, 1);
    pp.rightMargin = p.dp(18);
    cols.addView(preview, pp);
    LinearLayout controls = p.col();
    cols.addView(controls, new LinearLayout.LayoutParams(p.dp(220), -1));
    TextView state = p.text("", 14, PanelUi.ACCENT);
    bindText(
        state,
        () ->
            MonitorService.hasFreshFrame()
                ? "●  Live · 1 frame/s"
                : MonitorService.running
                    ? MonitorService.frameTime > 0 ? "Feed needs a fresh frame" : "Starting camera…"
                    : MonitorService.state.startsWith("Monitoring failed")
                        ? "Camera unavailable"
                        : "Camera off");
    add(controls, state, 24);
    p.gap(controls, 8);
    Button toggle =
        button(
            "Turn camera on",
            "camera",
            1,
            () -> {
              if (MonitorService.running) stopCamera();
              else startCamera();
            });
    bindings.add(
        () -> toggle.setText(MonitorService.running ? "Turn camera off" : "Turn camera on"));
    add(controls, toggle, 48);
    p.gap(controls, 12);
    LinearLayout light = p.row();
    light.addView(p.label("CAMERA LIGHT"), new LinearLayout.LayoutParams(0, -2, 1));
    TextView level = p.text("", 13, PanelUi.MUTED);
    bindText(level, () -> MonitorService.light + "%");
    light.addView(level);
    controls.addView(light);
    p.gap(controls, 4);
    SeekBar brightness = new SeekBar(this);
    brightness.setMax(100);
    brightness.setProgress(MonitorService.light);
    brightness.setContentDescription("Camera light brightness");
    bindings.add(() -> {
      brightness.setEnabled(MonitorService.running);
      if (!brightness.isPressed()) brightness.setProgress(MonitorService.light);
    });
    brightness.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar b, int n, boolean user) {
            if (user) MonitorService.setLight(n);
          }

          public void onStartTrackingTouch(SeekBar b) {}

          public void onStopTrackingTouch(SeekBar b) {}
        });
    add(controls, brightness, 48);
    Button torch =
        button(
            "Toggle light",
            "light",
            0,
            () -> MonitorService.setLight(MonitorService.light > 0 ? 0 : 16));
    bindings.add(() -> torch.setEnabled(MonitorService.running));
    add(controls, torch, 48);
    p.gap(controls, 8);
    LinearLayout job = p.col();
    TextView progress = p.text("", 12, PanelUi.MUTED);
    progress.setMaxLines(2);
    bindText(progress, () -> value(progress() * 100, 0) + "% · " + remaining());
    add(job, progress, 32);
    Button pause = button("Pause print", "pause", 0, this::pauseResume);
    bindings.add(() -> {
      job.setVisibility(activePrint() ? View.VISIBLE : View.GONE);
      boolean resume = jobState().equals("paused");
      pause.setText(resume ? "Resume print" : "Pause print");
      if (!Boolean.valueOf(resume).equals(pause.getTag())) {
        pause.setTag(resume);
        PanelUi.Icon icon = new PanelUi.Icon(resume ? "play" : "pause", PanelUi.TEXT);
        icon.setBounds(0, 0, p.dp(19), p.dp(19));
        pause.setCompoundDrawables(icon, null, null, null);
      }
    });
    gate(pause, this::activePrint);
    add(job, pause, 48);
    controls.addView(job);
    add(workspace, cols, -1);
  }

  void updateCamera() {
    if (cameraImage == null) return;
    boolean live = MonitorService.hasFreshFrame();
    cameraOverlay.setVisibility(live ? View.GONE : View.VISIBLE);
    cameraOverlay.setText(
        MonitorService.state.startsWith("Monitoring failed")
            ? MonitorService.state
            : MonitorService.running
                ? "Waiting for a fresh frame…"
                : "Camera off\nTurn it on to view the print");
    cameraImage.setVisibility(live ? View.VISIBLE : View.INVISIBLE);
    byte[] frame = MonitorService.latest;
    if (!live || frame == displayedFrame || cameraDecoding || frame == null) return;
    cameraDecoding = true;
    final ImageView target = cameraImage;
    images.execute(() -> {
      BitmapFactory.Options o = new BitmapFactory.Options();
      o.inSampleSize = 2;
      Bitmap b = BitmapFactory.decodeByteArray(frame, 0, frame.length, o);
      ui.post(() -> {
        cameraDecoding = false;
        if (!destroyed && cameraImage == target && b != null && MonitorService.hasFreshFrame()) {
          displayedFrame = frame;
          target.setImageBitmap(b);
        }
      });
    });
  }

  void startCamera() {
    ArrayList<String> permissions = new ArrayList<>();
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
      permissions.add(Manifest.permission.CAMERA);
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        && !getSharedPreferences("panel", 0).getBoolean("notifications_asked", false))
      permissions.add(Manifest.permission.POST_NOTIFICATIONS);
    if (!permissions.isEmpty()) {
      if (permissions.contains(Manifest.permission.POST_NOTIFICATIONS))
        getSharedPreferences("panel", 0).edit().putBoolean("notifications_asked", true).apply();
      requestPermissions(permissions.toArray(new String[0]), 10);
      return;
    }
    getSharedPreferences("panel", 0).edit().putBoolean("monitor_on_open", true).apply();
    try {
      startForegroundService(new Intent(this, MonitorService.class));
    } catch (Exception e) {
      tell("Camera could not start · " + message(e), PanelUi.AMBER);
    }
    updateViews();
  }

  void stopCamera() {
    getSharedPreferences("panel", 0).edit().putBoolean("monitor_on_open", false).apply();
    stopService(new Intent(this, MonitorService.class));
    displayedFrame = null;
    ui.postDelayed(this::updateViews, 200);
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(request, permissions, grants);
    if (request == 10
        && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
      startCamera();
    if (request == 11) {
      String required =
          Build.VERSION.SDK_INT >= 33
              ? Manifest.permission.NEARBY_WIFI_DEVICES
              : Manifest.permission.ACCESS_FINE_LOCATION;
      if (checkSelfPermission(required) == PackageManager.PERMISSION_GRANTED)
        ConnectionService.toggleHotspot(this);
      else tell("Allow nearby Wi-Fi access to create an offline network", PanelUi.AMBER);
    }
    if (request == 12) {
      seen = 0;
      lastPoll = 0;
    }
  }

  void menuRow(LinearLayout parent, String title, String subtitle, String icon, Runnable action) {
    LinearLayout row = p.row();
    p.pad(row, 6, 6);
    row.setBackground(p.buttonBackground(4));
    ImageView picture = new ImageView(this);
    picture.setImageDrawable(new PanelUi.Icon(icon, PanelUi.MUTED));
    row.addView(picture, new LinearLayout.LayoutParams(p.dp(22), p.dp(22)));
    LinearLayout text = p.col();
    text.setPadding(p.dp(12), 0, p.dp(8), 0);
    text.addView(p.text(title, 15, PanelUi.TEXT));
    TextView detail = p.text(subtitle, 12, PanelUi.MUTED);
    detail.setMaxLines(1);
    detail.setEllipsize(TextUtils.TruncateAt.END);
    text.addView(detail);
    row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
    ImageView arrow = new ImageView(this);
    arrow.setImageDrawable(new PanelUi.Icon("arrow", PanelUi.MUTED));
    row.addView(arrow, new LinearLayout.LayoutParams(p.dp(18), p.dp(18)));
    row.setContentDescription(title);
    row.setOnClickListener(v -> action.run());
    add(parent, row, 56);
  }

  void morePage() {
    LinearLayout columns = p.row();
    columns.setGravity(Gravity.TOP);
    LinearLayout system = p.panel(), mode = p.panel();
    LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1);
    left.rightMargin = p.dp(12);
    columns.addView(system, left);
    columns.addView(mode, new LinearLayout.LayoutParams(p.dp(280), -2));
    system.addView(p.text("System", 18, PanelUi.TEXT));
    p.gap(system, 8);
    menuRow(system, "Connection", "Wi-Fi, USB and Orca", "wifi", () -> show("Connection"));
    menuRow(
        system, "Maintenance", "Software and printer recovery", "refresh", this::maintenanceSheet);
    menuRow(system, "Startup", "Dedicated display preferences", "home", this::startupSheet);
    menuRow(system, "Mainsail", "Full printer interface", "files", () -> openMainsail(false));
    mode.addView(p.text("Control mode", 18, PanelUi.TEXT));
    p.gap(mode, 10);
    Button fast =
        button(
            "Fast controls: off",
            "play",
            0,
            () -> {
              getSharedPreferences("panel", 0)
                  .edit()
                  .putBoolean("fast_controls", !fastControls())
                  .apply();
              updateViews();
            });
    bindings.add(() -> {
      fast.setText("Fast controls: " + (fastControls() ? "on" : "off"));
      PanelUi.selection(fast, fastControls(), p);
    });
    add(mode, fast, 48);
    p.gap(mode, 8);
    subtext(mode, "Run routine actions with brief warnings instead of confirmation sheets.");
    p.gap(mode, 16);
    mode.addView(p.label("PIXEL SERVICES"));
    p.gap(mode, 4);
    statusLine(mode, "Local sharing", () -> ConnectionService.running ? "On" : "Off");
    statusLine(mode, "Camera", () -> MonitorService.running ? "On" : "Off");
    add(workspace, columns, -1);
  }

  void connectionPage() {
    LinearLayout body = scroll(workspace);
    LinearLayout cols = p.row();
    cols.setGravity(Gravity.TOP);
    LinearLayout printer = p.panel();
    LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1);
    left.rightMargin = p.dp(14);
    cols.addView(printer, left);
    printer.addView(p.label("PRINTER CONNECTION"));
    p.gap(printer, 10);
    EditText endpoint = new EditText(this);
    endpoint.setSingleLine(true);
    endpoint.setTextColor(PanelUi.TEXT);
    endpoint.setTextSize(15);
    endpoint.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
    endpoint.setText(client.base);
    endpoint.setContentDescription("Moonraker address");
    add(printer, endpoint, 48);
    p.gap(printer, 8);
    Button connect =
        button(
            "Connect",
            "check",
            1,
            () -> {
              try {
                String url = PrinterClient.normalize(endpoint.getText().toString());
                getSharedPreferences("panel", 0).edit().putString("endpoint", url).apply();
                changeClient(new PrinterClient(this));
                tell("Connecting to the printer…", PanelUi.ACCENT);
              } catch (Exception e) {
                endpoint.setError(message(e));
              }
            });
    gate(connect, () -> !commandBusy);
    rowButtons(printer, connect, button("API key", null, 0, this::apiKeySheet));
    p.gap(printer, 10);
    TextView details = p.text("", 13, PanelUi.MUTED);
    bindText(
        details,
        () ->
            fresh()
                ? "Printer: " + stateTitle()
                : lastError.isEmpty()
                    ? "Connecting…"
                    : "Printer unavailable. Check its address and power.");
    printer.addView(details);
    LinearLayout network = p.panel();
    cols.addView(network, new LinearLayout.LayoutParams(0, -2, 1));
    network.addView(p.label("NETWORK & SHARING"));
    p.gap(network, 10);
    rowButtons(
        network,
        button("Join Wi-Fi", "wifi", 0, () -> openSettings(Settings.ACTION_WIFI_SETTINGS)),
        button("USB link", null, 0, () -> openSettings("android.settings.TETHER_SETTINGS")));
    p.gap(network, 10);
    Button hotspot = button("Offline network", "wifi", 0, this::toggleHotspot);
    bindings.add(() ->
        hotspot.setText(
            ConnectionService.hotspotName.isEmpty()
                ? "Create offline network"
                : "Stop offline network"));
    add(network, hotspot, 48);
    p.gap(network, 8);
    TextView hotspotState = p.text("", 13, PanelUi.MUTED);
    bindText(
        hotspotState,
        () ->
            ConnectionService.hotspotName.isEmpty()
                ? ConnectionService.hotspotState
                : ConnectionService.hotspotName + " · local network");
    network.addView(hotspotState);
    p.gap(network, 8);
    Button networkDetails = button("Connection details", null, 0, () -> networkDetailsSheet());
    add(network, networkDetails, 48);
    add(body, cols, -2);
    p.gap(body, 14);
    LinearLayout sharing = p.row();
    Button pair = button("Set up Orca connection", "files", 1, this::sharingSheet);
    p.weighted(sharing, pair, 48);
    Button onOff =
        button(
            "Local sharing",
            null,
            0,
            () -> {
              boolean on = getSharedPreferences("panel", 0).getBoolean("gateway_enabled", true);
              getSharedPreferences("panel", 0).edit().putBoolean("gateway_enabled", !on).apply();
              if (on) ConnectionService.stop(this);
              else startConnection();
              updateViews();
            });
    bindings.add(() ->
        onOff.setText(ConnectionService.running ? "Stop local sharing" : "Start local sharing"));
    p.weighted(sharing, onOff, 48);
    add(body, sharing, 48);
    p.gap(body, 10);
    subtext(
        body,
        "An offline network supports nearby use. Remote access from another location still needs"
            + " internet and private remote-access setup.");
  }

  void changeClient(PrinterClient selected) {
    connectionGeneration++;
    client = selected;
    seen = 0;
    status = new JSONObject();
    lastError = "";
    lastPoll = 0;
    metadata.clear();
    thumbnails.clear();
    files.clear();
    lastFileRequest = 0;
    filesFetching = false;
    fileError = "";
    startConnection();
    updateViews();
  }

  void apiKeySheet() {
    LinearLayout body = sheet("Printer API key");
    subtext(body, "Leave empty when Moonraker trusts this local connection.");
    p.gap(body, 12);
    EditText key = new EditText(this);
    key.setTextColor(PanelUi.TEXT);
    key.setSingleLine(true);
    key.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
    key.setText(client.key);
    body.addView(key);
    p.gap(body, 12);
    Button save =
        button(
            "Save key",
            "check",
            1,
            () -> {
              getSharedPreferences("panel", 0)
                  .edit()
                  .putString("api_key", key.getText().toString().trim())
                  .apply();
              changeClient(new PrinterClient(this));
              dismissSheet();
            });
    dialogGate(save, () -> !commandBusy);
    add(body, save, 48);
  }

  void toggleHotspot() {
    String permission =
        Build.VERSION.SDK_INT >= 33
            ? Manifest.permission.NEARBY_WIFI_DEVICES
            : Manifest.permission.ACCESS_FINE_LOCATION;
    if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(
          Build.VERSION.SDK_INT >= 33
              ? new String[] {Manifest.permission.NEARBY_WIFI_DEVICES}
              : new String[] {
                Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION
              },
          11);
      return;
    }
    ConnectionService.toggleHotspot(this);
  }

  void networkDetailsSheet() {
    LinearLayout body = sheet("Network details");
    subtext(
        body, "Phone addresses\n" + networkAddresses() + "\n\nPrinter destination\n" + client.base);
    if (!ConnectionService.hotspotName.isEmpty()) {
      p.gap(body, 14);
      subtext(body, "Offline network: " + ConnectionService.hotspotName);
      p.gap(body, 8);
      add(
          body,
          button(
              "Show Wi-Fi password",
              null,
              0,
              () ->
                  information(
                      "Offline Wi-Fi",
                      ConnectionService.hotspotName + "\n\n" + ConnectionService.hotspotPassword)),
          48);
    }
  }

  String networkAddresses() {
    ArrayList<String> list = new ArrayList<>();
    try {
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      while (interfaces.hasMoreElements()) {
        NetworkInterface n = interfaces.nextElement();
        Enumeration<InetAddress> ips = n.getInetAddresses();
        while (ips.hasMoreElements()) {
          InetAddress a = ips.nextElement();
          if (a instanceof Inet4Address && !a.isLoopbackAddress())
            list.add(n.getName() + ": " + a.getHostAddress());
        }
      }
    } catch (Exception ignored) {
    }
    return list.isEmpty() ? "No local address" : String.join("\n", list);
  }

  String phoneAddress() {
    String fallback = "";
    try {
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      while (interfaces.hasMoreElements()) {
        NetworkInterface n = interfaces.nextElement();
        Enumeration<InetAddress> ips = n.getInetAddresses();
        while (ips.hasMoreElements()) {
          InetAddress a = ips.nextElement();
          if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
            if (n.getName().equals("wlan0")) return a.getHostAddress();
            if (fallback.isEmpty()) fallback = a.getHostAddress();
          }
        }
      }
    } catch (Exception ignored) {
    }
    return fallback;
  }

  void sharingSheet() {
    LinearLayout body = sheet("Connect Orca to this Pixel");
    String ip = phoneAddress();
    if (ip.isEmpty() || !ConnectionService.running) {
      subtext(body, "Start local sharing and connect the phone to Wi-Fi first.");
      return;
    }
    String host = "http://" + ip + ":8765", key = MonitorService.token(this);
    subtext(
        body,
        "In Orca's printer connection, choose Moonraker (Klipper). Copy these fields. The address"
            + " is local and may change on a different network.");
    p.gap(body, 14);
    rowButtons(
        body,
        button("Copy host URL", null, 1, () -> copy("Printer host", host)),
        button("Copy API key", null, 0, () -> copy("Printer key", key)),
        button("Copy Device UI", null, 0, () -> copy("Device UI", host + "/?token=" + key)));
    p.gap(body, 14);
    subtext(
        body,
        "Host: "
            + host
            + "\n"
            + "The camera feed is included in Device UI. Camera can be off while uploads and"
            + " printer controls remain available.");
  }

  void copy(String label, String value) {
    getSystemService(android.content.ClipboardManager.class)
        .setPrimaryClip(ClipData.newPlainText(label, value));
    tell(label + " copied", PanelUi.ACCENT);
  }

  void openSettings(String action) {
    try {
      startActivity(new Intent(action));
    } catch (Exception e) {
      startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
    }
  }

  void openMainsail(boolean updater) {
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
                  updater ? "/machine" : null)
              .toString();
      startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    } catch (Exception e) {
      tell(message(e), PanelUi.AMBER);
    }
  }

  void maintenanceSheet() {
    LinearLayout body = sheet("Printer maintenance");
    rowButtons(
        body,
        button("Software versions", "refresh", 1, this::versions),
        button("Open updater", null, 0, () -> openMainsail(true)));
    p.gap(body, 16);
    subtext(
        body,
        "Updates use Mainsail's existing updater. A Klipper update may also require matching"
            + " controller firmware.");
    p.gap(body, 16);
    rowButtons(
        body,
        button("Restart Klipper", null, 0, () -> recover(false)),
        button("Firmware restart", null, 0, () -> recover(true)));
  }

  void versions() {
    tell("Checking software versions…", PanelUi.MUTED);
    PrinterClient target = client;
    long generation = connectionGeneration;
    reads.execute(() -> {
      try {
        JSONObject result =
            target
                .request("GET", "/machine/update/status?refresh=false", null)
                .getJSONObject("result");
        JSONObject info = result.optJSONObject("version_info");
        StringBuilder text = new StringBuilder();
        for (String component : new String[] {"klipper", "moonraker", "mainsail"}) {
          JSONObject data = info == null ? null : info.optJSONObject(component);
          if (data != null)
            text.append(component)
                .append("\nInstalled ")
                .append(data.optString("version", "unknown"))
                .append("\nAvailable ")
                .append(data.optString("remote_version", "unknown"))
                .append("\n\n");
        }
        if (text.length() == 0) text.append("The printer's update manager is unavailable.");
        String summary = text.toString().trim();
        ui.post(() -> {
          if (generation == connectionGeneration && !destroyed) {
            information("Software versions", summary);
            tell("Software versions checked", PanelUi.ACCENT);
          }
        });
      } catch (Exception e) {
        ui.post(() -> tell("Versions unavailable · " + message(e), PanelUi.AMBER));
      }
    });
  }

  void startupSheet() {
    LinearLayout body = sheet("Dedicated printer display");
    CheckBox restore = new CheckBox(this);
    restore.setTextColor(PanelUi.TEXT);
    restore.setText("Restore camera when Voron opens");
    restore.setChecked(getSharedPreferences("panel", 0).getBoolean("monitor_on_open", false));
    restore.setOnCheckedChangeListener((b, on) ->
        getSharedPreferences("panel", 0).edit().putBoolean("monitor_on_open", on).apply());
    body.addView(restore);
    p.gap(body, 12);
    subtext(
        body,
        "On the dedicated printer phone, Voron can be the Home app. Keep the development Pixel's"
            + " current Home app and PIN unchanged. Automatic USB and cold-boot recovery still need"
            + " hardware verification.");
    p.gap(body, 16);
    add(
        body,
        button("Choose Home app", "home", 0, () -> openSettings(Settings.ACTION_HOME_SETTINGS)),
        48);
  }
}
