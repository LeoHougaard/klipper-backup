# Voron Pixel panel

An unrooted Android printer display for Leo's Voron V0.2r1. Development prototype,
not yet a completed portable-printer deployment. Android 12+; the reference phone
is the base Pixel 10 on Android 17. Klipper stays on the existing BTT Pi.

## Current app

- Landscape in either direction, muted dark colors, 48 dp minimum controls, and
  hardware-reported safe margins for both the camera cutout and rounded corners.
- Home: a large plate preview beside a circular XY control, center homing, a
  separate vertical Z control, and a related extrusion/Load/Unload area. Exact
  positions, distances and XY/Z/E speeds are editable in place. Neutral dark
  surfaces use accent only for selection and primary actions. Level in the
  right rail refreshes the compensated heightmap. Tap the plate for its details;
  Heat contains PLA/ABS presets and cooldown. Printing enlarges the model and
  exposes print speed.
  Load/Unload heat to the selected filament temperature, wait for thermal readiness,
  then run the macro once. Cancel heating stops the pending operation and restores
  its previous heater target when that target still belongs to the operation.
  Printing keeps model identity, progress, remaining time and pause/resume together.
  **Tune print** opens live Z, speed, flow and fan controls only when needed.
  Nozzle/bed readings, printer status and E-stop stay in a right-side rail across
  every screen. Tap a temperature to edit it, or drag down/up to raise/lower its
  target in 5-degree steps. Editors open beside the rail and preserve job context.
  Navigation uses one grouped rail; ordinary controls use neutral surfaces,
  with outlines reserved for focus, editable inputs and danger. Files and
  Camera remain the single routes to their screens.
- Paused filament changes start from the current heater target and offer local
  Resume back to the active job. Finished, failed and unavailable states show
  relevant next actions and separate Pixel/Moonraker/Klipper connection status.
- Prepare: direct XYZE movement, exact XYZ positions, selectable jog distances,
  custom extrusion distance, homing/travel checks, motor release, complete
  hot-nozzle load/unload and bounded feed/retract. More → Maintenance → Bed calibration
  holds the front/rear paper setup and mesh density. Calibration results distinguish
  saved/rebuilt, rejected measurements, and unconfirmed completion.
  Manual motion is available idle or paused. Manual E uses Klipper's cold-extrusion
  readiness rather than automatically heating; Load/Unload own the automatic workflow.
  The dedicated Move page uses a larger XY wheel with adjacent Z and E columns.
- Emulator review: `python pixel-panel/tests/review_server.py --serial emulator-5554`
  serves loopback controls on port 18726 and a simulated Moonraker on 18725.
  With the app already connected to that fixture, select printer state and
  native screen separately, then **Open in app**. Screens include the main
  pages, maintenance/calibration, heightmap, temperature and numeric editors.
  **Capture current app** records manual Device interactions; **Annotate preview**
  in T3 marks the resulting immutable screenshot. Fit/full-resolution zoom is
  available. The server refuses physical-device serials and checks the endpoint.
- UI evaluation: [contract and scorecard](ui-evaluation.md). The exact Bambu
  references, baseline hashes, synthetic fixture data, twelve categories and
  fixed score of 8 are locked in `evaluation/`. Captures check those anchors.
  `tests/measure_ui_quality.py` measures geometry; it does not generate design
  scores. Material Symbols Rounded vectors are pinned to a Google source
  revision with their license under `third-party/material-symbols/`.
- Files: searchable stored G-code, newest first, metadata, model thumbnails and
  explicit print start. Refreshed size/timestamp changes invalidate old previews.
- Camera: rear camera, silent 1280×720 maximum JPEG frames at approximately 1 fps,
  authenticated MJPEG and snapshots, continuous torch with adjustable strength.
  The service stays independent of the Linux desktop. A notification stops it.
- More: Moonraker URL and optional API key, Android Wi-Fi/tethering settings,
  offline hotspot, software versions, link to the existing updater, and startup options.
- More → Fast controls skips routine confirmation dialogs, with brief action
  warnings and a visible FAST indicator. It persists until switched off. This
  does not change Klipper's homing, heater, extrusion or travel configuration.
- Authenticated HTTP gateway: the same phone endpoint supports Orca's native
  Moonraker host, large uploads streamed without holding the G-code in memory,
  a small browser device panel, and camera access. No WebSocket proxy is implemented.

No root, shell execution, Termux or Debian is used by the APK. The current test
phone happens to be rooted; operation on a stock second phone still needs verification.
No AI detector or on-phone slicer is included.

## Use

1. Open **Voron**. Allow local-network access on Android 17. In More → Connection, enter
   the Pi's Moonraker URL, including port 7125 if directly accessing Moonraker.
   Home should show `Ready` when the printer is ready and idle.
2. Camera → Turn camera on. Grant camera access; notification permission is
   optional. The light defaults off. The preview advances and hides stale frames.
3. More → Connection → Orca connection displays the phone's local addresses,
   port and private key. Those values remain in app-private storage; don't commit them.
4. In Orca, select **Moonraker (Klipper)** as the host. Use the phone's address
   and port 8765 as the host URL and the displayed key as the API key.
   Device UI is `http://<phone>:8765/?token=<key>`; it includes the camera.
   A direct camera URL is `http://<phone>:8765/camera/stream?token=<key>`.
5. Turning the camera off releases the camera, torch and camera wake lock.
   Sharing and uploads continue independently. More → Connection → Local sharing
   stops the gateway and offline hotspot. The printer keeps running independently.

The key grants printer control as well as video access. This prototype uses HTTP
on a trusted local network. It must not be exposed with router port forwarding.
Private remote access is planned, not installed. The phone URL above is local,
and its IP can change; stable remote naming is still a deployment task.

Changing the Moonraker address updates the gateway destination immediately.
The screen stays awake while the panel is visible. Monitoring uses a foreground
camera service and a wake lock; Stop releases that lock. Long-print power and
thermal behavior are not yet established.

## Networking and deployment gates

Confirmed design decisions:

- No USB cable to the computer. The Pixel-to-Pi USB cable is internal to the printer setup.
- Online: manually join the location's Wi-Fi on the Pixel; share internet with the Pi over USB.
- Offline: computer joins a Pixel hotspot and uses the app as the printer gateway.
- No SIM, cellular subscription, credential-sharing helper or custom Wi-Fi setup page.
- Dedicated phone can omit a screen PIN, with no personal accounts copied from the current Pixel.
- Restore connectivity and monitoring after startup if possible; never resume an interrupted print.
- AI failure detection and on-phone slicing are bonuses, not acceptance gates.

The Pixel's normal USB tethering must still be enabled in Android Settings and
tested while physically connected to the Pi. Android's local-only hotspot does
not itself route clients to USB devices: the app's authenticated HTTP gateway is
intended to provide that path. This must be tested from an actual hotspot client
with the Pi connected over USB, with home Wi-Fi unavailable.

Android 16+ supports a custom local-only hotspot through public APIs. The app
requests **Voron** with a generated persistent password. The tested Pixel firmware
substituted a system-generated name and password despite that request; the app
displays the actual reservation credentials, and a stable offline network identity
remains unresolved. Older Android versions also use the system-generated hotspot.
The dedicated foreground connection service owns the hotspot and gateway,
independently of the visible Activity and camera. Process death and cold-boot
recovery still need verification on the dedicated phone.

The optional Home intent lets a dedicated phone choose Voron as its launcher.
Startup options can restore camera monitoring when the visible app opens after
permission grants. This does not establish cold-boot recovery, automatic USB
tethering, or boot-on-power after complete battery depletion. Don't change the
development Pixel's launcher, PIN, boot configuration, or Linux environment for
this test. Prove those behaviors on the dedicated phone.

On 2026-11-11, the live Pi reported `BigTreeTech CB1` and exposed four USB
controller pairs. SKR Pico occupied OHCI bus 7; the CAN adapter occupied bus 5;
the other pairs had no devices. This suggests one additional USB connection
fits without moving printer devices. Device-tree identity does not establish
the exact carrier revision or physical access. Verify the spare socket and
phone charging under screen/camera/torch load before selecting a power solution.
A hub is not currently justified just by device count. Leo has confirmed a spare
socket is physically accessible; charging adequacy remains unverified.

## Later ideas

Camera-assisted automatic pressure advance calibration is recorded as future
work at Leo's request. The planned overhead camera view may help inspect a
calibration pattern. No PA automation is included or being implemented in this UI
redesign. AI failure detection and on-phone slicing also remain later work.

## Updates

Moonraker already offers update status on this printer. The panel displays
installed/available Klipper, Moonraker and Mainsail versions and opens the
existing updater. It does not launch updates automatically or add a second
update engine. Klipper upgrades may require matching MCU firmware; preserve the
repository-managed custom extra and its excludes file. No updates or printer
service restarts are performed by the build or verification scripts.

## Build and verification

On this Windows machine, Android SDK API 37, build-tools 36.0.0 and Android
Studio's JBR are already installed:

```powershell
$env:ANDROID_SERIAL = 'emulator-5554' # Use the serial shown by adb devices.
python pixel-panel/build.py
& "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" -s $env:ANDROID_SERIAL install -r pixel-panel/.build/Voron.apk
```

`ANDROID_HOME` and `JAVA_HOME` override tool locations. Compilation and signing
work in `%LOCALAPPDATA%/VoronPanel/`, outside OneDrive's synchronized directory,
which can reject recursive build-directory cleanup. The resulting development
APK is copied to `.build/Voron.apk`. The signing key is outside the repository.
This APK is debuggable for testing; disable that flag for a release build.

```powershell
# Already-open review emulator connected to a loopback fixture on port 18725:
# automatic heat/cancel/load/unload and manual XYZE workflows.
python -X utf8 -u pixel-panel/tests/check_manual_filament.py

# layout, tuning/keypad, pause/resume, paper calibration, Files and E-stop.
python -X utf8 -u pixel-panel/tests/check_layout_workflows.py --port 18725

# Actual Android controls against an isolated Moonraker fixture. No real motion.
python -X utf8 -u pixel-panel/tests/check_controls.py

# Touch keypad, validation, Back, search, precise tuning and E-stop during entry.
python -X utf8 -u pixel-panel/tests/check_touch_flows.py

# Temperature slide steps/limits, single release dispatch, tap and cancellation.
python -X utf8 -u pixel-panel/tests/check_temperature_drag.py

# Full paper-calibration outcomes, repeated tuning and emergency-stop concurrency.
python -X utf8 -u pixel-panel/tests/check_workflows.py

# Normal warning versus explicit one-tap Fast controls; restores the prior mode.
python -X utf8 -u pixel-panel/tests/check_fast_controls.py

# Partial calibration certainty and E-stop cancellation during delayed preflight.
python -X utf8 -u pixel-panel/tests/check_recovery_edges.py

# Current UI workflows on the already-open emulator and loopback review fixture.
# Set AGENT_DEVICE_COMMAND to the bound command from T3 device_open first.
python -X utf8 -u pixel-panel/tests/check_layout_workflows.py --output pixel-panel/.build/ui-workflows
python -X utf8 -u pixel-panel/tests/check_filament_task.py --output pixel-panel/.build/filament-task
python -X utf8 -u pixel-panel/tests/check_motion_speed.py

# Eight matched Prepare states and native touch bounds; sends no commands.
python -X utf8 -u pixel-panel/tests/capture_prepare.py --output pixel-panel/.build/design/prepare-review

# Start with camera off. The test starts/stops it; the gateway stays active.
python -X utf8 pixel-panel/tests/check_gateway.py

# Repeat over the phone's real Wi-Fi address, with no ADB network tunnel.
python -X utf8 pixel-panel/tests/check_gateway.py --lan <phone-ip>

# Optional: unique comment-only G-code upload, exact read-back, and deletion.
# Never starts a print, heats or moves the printer.
python -X utf8 pixel-panel/tests/check_gateway.py --lan <phone-ip> --upload-test
```

Test tools privately read the development app's key via `run-as`; they never
print it. Fixture overrides are accepted only by a debuggable APK, never saved,
and cleared by test cleanup. They are not production connection discovery.

`tests/check_device_ui.cjs` uses Playwright with installed Chrome, silently and
with all HTTP intercepted. Its default Playwright package path is
`%LOCALAPPDATA%/VoronPanel/browser-tools/node_modules/playwright`; override with
`VORON_PLAYWRIGHT` if needed. It verifies the browser Device panel, not Orca itself.

```powershell
npm install --prefix "$env:LOCALAPPDATA/VoronPanel/browser-tools" playwright --no-audit --no-fund
node pixel-panel/tests/check_device_ui.cjs
```

See [verification](verification.md) for actual evidence and pending gates.

## Interface references

The native interface uses neutral surfaces for related controls, mint for a
primary action or selection, and outlines for focus, numeric entry and E-stop.
The rail changes pages; pencils and speed symbols identify editable parameters.
Both idle and active print previews open their details when tapped. Common
homing and cold-filament prerequisites are checked when tapped, with transient
errors and no dispatched command. They are not permanent instructions.

Filament keeps material temperature and automatic Load/Unload together. Selecting
a temperature does not start heating. Load/Unload heats and waits automatically;
Cancel heating prevents the pending extrusion. Manual feed is a separate vertical
control and shares its distance and speed with Move. Manual nozzle heating remains
available independently. Level refreshes the compensated heightmap; paper
calibration stays under Maintenance.

Status heater controls support sliding down/up in 5°C steps, with a live preview
and one command on release. A cancelled gesture sends nothing. A tap opens the
temperature presets. Custom numeric values use a built-in 48 dp keypad, range
validation and explicit Apply; Android's IME stays hidden. Inline tuning values
open the same editor; the duplicate overflow sheet is removed. Text fields retain
Android's text keyboard, disable fullscreen extraction and support Done/Go. Back
returns to the previous page; within custom temperature entry it returns to the
temperature editor. Cancel discards a numeric edit and returns to that context;
Close dismisses ordinary sheets. Numeric palettes offer common axis distances,
feed rates and filament temperatures while retaining exact entry. The selected
filament temperature is visible beside Home Load/Unload and survives app restart.

Native color roles and geometry live in `PanelUi.java`: neutral backgrounds,
muted mint accent, red E-stop and amber status. Cards have 12 dp insets, controls
use a 6 dp radius and dialogs use 16 dp insets with an 8 dp radius. Page/dialog
headings are 18 sp, ordinary actions 14 sp, and secondary captions 12 sp. Material
Symbols Rounded are pinned native vector resources with their license under
`third-party/material-symbols`. The browser Device interface has its own CSS.

For a T3 Device panel session, use `device_open` and open the app with its returned
bound `agent-device` command. Set `AGENT_DEVICE_COMMAND` to that command's path
before running the review server or already-open emulator tests, with
`ANDROID_SERIAL=emulator-5554`. The test adapter then uses the existing session's
snapshots and presses instead of starting a competing UIAutomator inspector.
`check_filament_task.py` covers custom selection without heating, cancellable
automatic heating, exact manual feed and paused geometry. The review chooser
includes the filament temperature editor as well as the motion editors.

The interface uses the printer tasks common to Bambu's touchscreen workflow
and KlipperScreen: status, temperatures, axes, filament, stored prints and
maintenance. It doesn't copy branding or add unrelated multi-material controls.

- [Bambu X1 quick-start guide](https://cdn1.bambulab.com/documentation/quick-start-2024-01-17/Quick-Start-X1%26X1C_en.pdf)
- [KlipperScreen panels](https://klipperscreen.readthedocs.io/en/latest/Panels/)
- [Prusa idle and printing menus](https://help.prusa3d.com/article/lcd-menu-mk4-s-mk3-9-s_413171)
- [Mainsail dashboard organisation](https://docs.mainsail.xyz/features/dashboard-organisation/)
- [Fluidd file manager](https://docs.fluidd.xyz/features/file-manager/)
- [Theo: Agents Sucked At UI (Until Today)](https://www.youtube.com/watch?v=xq0eB8vxeqk)
- [Proper Printing: F*ck Around XL](https://properprinting.pro/pages/project/f-ck-around-xl)
- [Proper Printing's touchscreen/computer build](https://www.youtube.com/watch?v=DQoEssmHciE)
- [Proper Printing's latest CNC/printing workflow](https://www.youtube.com/watch?v=eDQwFdMI1rE)
- [Moonraker printer API](https://moonraker.readthedocs.io/en/latest/external_api/printer/)
- [Moonraker update manager](https://moonraker.readthedocs.io/en/latest/external_api/update_manager/)
- [Android local-network permission](https://developer.android.com/privacy-and-security/local-network-permission)
- [Android local-only hotspot API](https://developer.android.com/reference/android/net/wifi/WifiManager#startLocalOnlyHotspotWithConfiguration(android.net.wifi.SoftApConfiguration,%20java.util.concurrent.Executor,%20android.net.wifi.WifiManager.LocalOnlyHotspotCallback))
- [Dedicated-device launcher startup](https://developer.android.com/work/dpc/dedicated-devices/cookbook)


## UI improvement evidence

The fixed comparison remains Bambu = 8 and the original starting UI = 1.
`evaluation/rubric-v2.json` separates twelve visual categories from measured
actuation tasks and animation quality. A functional pass never raises a visual
score automatically. The ten frozen task budgets count all taps and digits;
automation time does not measure first-use discovery or human task time.

`ui-history/index.json` records every available candidate, including rejected
versions. New candidates preserve the installed APK, exact source, captures,
evaluation and available motion recordings with hashes. Use
`python pixel-panel/scripts/record_ui.py verify` to check the archive.
Historical versions explicitly say when their original APK is unavailable.

Page entry uses a 140 ms opacity transition. Sheets enter in 140 ms and leave in
100 ms. Controls stay fixed, commands execute immediately, live temperatures are
never interpolated, and polling does not replay page entry. Android's reduced
motion disables these transitions. Record actual normal/reduced-motion runs with
`tests/record_ui_motion.py`; retain frame timing limitations rather than claiming
physical-phone smoothness from emulator results.

Additional primary-source examples are pinned in
`evaluation/supplemental-references.json`: Blackmagic Camera and camera OS,
Home Assistant and Prusa. They inform grouping and parameter editing without
replacing or rescoring the frozen Bambu reference. Terra's requested audit and
verified findings are recorded in `evaluation/terra-input.md`.
