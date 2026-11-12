# Voron Pixel panel

An unrooted Android printer display for Leo's Voron V0.2r1. Development prototype,
not yet a completed portable-printer deployment. Android 12+; the reference phone
is the base Pixel 10 on Android 17. Klipper stays on the existing BTT Pi.

## Current app

- Landscape in either direction, muted dark colors, 48 dp minimum controls, and
  hardware-reported safe margins for both the camera cutout and rounded corners.
- Home: direct homing, PLA/ABS preheat, cooldown and the recent plate while idle.
  Printing keeps model identity, progress, remaining time, pause/resume, live Z
  and inline speed/flow/fan together. Nozzle/bed current and target values remain
  editable in the header across the app; compact editors preserve job context.
- Paused filament changes start from the current heater target and offer local
  Resume back to the active job. Finished, failed and unavailable states show
  relevant next actions and separate Pixel/Moonraker/Klipper connection status.
- Prepare: spatial XY/Z movement, homing/travel checks, motor release, complete
  hot-nozzle load/unload and bounded feed/retract, plus native front/rear paper
  calibration and corrected-heightmap refresh. Calibration results distinguish
  saved/rebuilt, rejected measurements, and unconfirmed completion.
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
python pixel-panel/build.py
& "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" install -r pixel-panel/.build/Voron.apk
```

`ANDROID_HOME` and `JAVA_HOME` override tool locations. Compilation and signing
work in `%LOCALAPPDATA%/VoronPanel/`, outside OneDrive's synchronized directory,
which can reject recursive build-directory cleanup. The resulting development
APK is copied to `.build/Voron.apk`. The signing key is outside the repository.
This APK is debuggable for testing; disable that flag for a release build.

```powershell
# Actual Android controls against an isolated Moonraker fixture. No real motion.
python -X utf8 -u pixel-panel/tests/check_controls.py

# Full paper-calibration outcomes, repeated tuning and emergency-stop concurrency.
python -X utf8 -u pixel-panel/tests/check_workflows.py

# Normal warning versus explicit one-tap Fast controls; restores the prior mode.
python -X utf8 -u pixel-panel/tests/check_fast_controls.py

# Partial calibration certainty and E-stop cancellation during delayed preflight.
python -X utf8 -u pixel-panel/tests/check_recovery_edges.py

# Home, custom filament temperature/off/preset, paused Resume and inline read-back.
python -X utf8 -u pixel-panel/tests/check_quality_workflows.py --output pixel-panel/.build/design/quality-workflows

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

Native and Device interfaces share a restricted palette from
[`@radix-ui/colors` 3.0.0](https://www.radix-ui.com/colors): Sage neutrals, Mint
accent, and Red/Amber status. Color roles and corner sizes are defined in
`PanelUi.java` and mirrored as CSS variables in `device.html`; Android theme
and launcher icon use the same colors. Cards use a 20 dp radius around 12 dp
insets and 8 dp controls; sheets use a 24 dp radius around 16 dp insets.

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
