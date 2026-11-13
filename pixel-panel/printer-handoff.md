# Printer connection and readiness

## Installed and verified

The development Pixel 10 now has Voron **0.3.9 (code 12)**. Its installed APK
matches the verified build. The previous 0.3.3 APK is backed up outside Git at
`%LOCALAPPDATA%/VoronPanel/deployment-backups/2026-11-12/`.
Existing app data and the saved connection were preserved. The PIN, launcher
and Linux setup were not changed.

The app uses the actual printer at `http://10.1.39.216:7125`, without the emulator's
debug fixture override. The simulator remains isolated on its loopback fixture.

From this Windows PC, the actual Pixel gateway at `http://10.1.39.231:8765` passed:

- Authenticated live printer telemetry, with Klipper ready and no Moonraker warnings.
- All required custom macros and status objects, including Load/Unload,
  compensated Level, and front/rear calibration.
- Heater-limit compatibility for PLA 200/60 and ABS 240/100 presets.
- Listing all 32 stored G-code files.
- Uploading a unique comment-only file with `print=false`, exact read-back,
  and deletion of that temporary file. No print was started.
- Rejection of an unauthenticated request with HTTP 401.
- Gateway availability while Android is locked, after the app update/relaunch.

The printer stayed in standby with nozzle and bed targets at zero. No G-code command,
homing, probing, heating, print start, firmware change or printer restart was sent.
See the sanitized [readiness receipt](evaluation/printer-readiness.json).

Repeat the non-actuating checks from this workspace with:

```powershell
python pixel-panel/tests/check_printer_readiness.py --serial 66270DLCR002RH --upload-check
```

The test keeps the gateway key in memory and never prints or records it. It
requires the development APK's existing `run-as` access and the phone's Wi-Fi.

## Use now on the current LAN

Unlock the Pixel and open **Voron**. The Home screen should show the real nozzle
and bed readings and Ready. Axes are currently unhomed, so home them only after
clearing the machine. Load/Unload automatically heat to the selected material
temperature; manual Feed/Retract require a hot nozzle. Level refreshes the
compensated heightmap. Paper calibration remains under More / Maintenance.

For Orca, choose Moonraker and the phone address above; get the private API key
from Voron's Connection / Orca connection screen. Existing slicer profiles have
not been changed. The phone's DHCP address can change. The direct Pi URL above
also remains available. Stop local sharing in the app to stop its gateway;
the Pi continues executing an existing print independently.

## Remaining acceptance checks

The phone is currently locked. One authorized encrypted-credential unlock attempt
failed with protected output; root and primary-user preflight checks pass. No
second PIN attempt or PIN change was made. Its updated live UI and camera still
need a visible check after unlocking. Leo has explicitly limited current printer
checks to read-only. Physical controls require Leo to be present
and confirm the printer is clear before homing, Level, or heater/filament tests.
The simulator tests do not replace those hardware checks.

Portable operation is not signed off: connect the intended dedicated phone to
the Pi over USB, prove tethering and an actual offline hotspot client, measure
charging under screen/camera load, and verify cold-start/network recovery on
that dedicated setup. Do not reboot or change this development Pixel's PIN,
launcher or Linux session for those checks. Never automatically resume a print.

Until those checks pass, this is verified LAN communication and an installed
app, not a fully validated portable printer deployment.
