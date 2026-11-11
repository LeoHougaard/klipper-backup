# Pixel panel 0.1.0 verification

Date: 2026-11-11. Base Pixel 10, Android 17 / API 37. The reference phone is
rooted for its existing Linux project, but this APK uses no root, shell helper,
Linux service, privileged permission grant or hidden Android API. Runtime grants
were made through Android's normal permission dialogs. A stock second phone
remains a separate acceptance check.

## Passed on the actual phone

- APK builds with installed Android SDK tools, signs, installs as a separate
  `dev.leo.voronpanel` package, and runs in landscape.
- Inspected the actual screen. Print state, nozzle and bed temperatures match
  the live Voron. Camera cutout has a safe margin; navigation remains available.
- Android 17 LAN access initially failed until the new `ACCESS_LOCAL_NETWORK`
  runtime permission was implemented and allowed. The root cause is fixed in
  the app rather than bypassed with a lower target SDK or root.
- Camera delivers 1280×720 JPEGs at approximately one frame per second.
  Snapshot and MJPEG requests pass both through an ADB port forward and over
  the Pixel's actual Wi-Fi address from Windows. Frames advance over time.
- Camera, health and printer endpoints reject unauthenticated access with 401.
- Live Moonraker status passes through the authenticated phone gateway.
- Uploaded unique comment-only G-code over the actual Wi-Fi gateway to the real
  printer; read back byte-identical contents; verified `print_started=false`;
  deleted the temporary file. The test handles Moonraker's upload response,
  which is an unwrapped object on this installed server.
- Camera remains live after returning to Android Home; its foreground service
  does not depend on the Pixel Linux desktop.
- Stop releases the foreground service and HTTP listener. No active monitoring
  service remained at handoff; the torch was off and the temporary hotspot ended.
- Torch slider set to 16%. Live camera metadata reported `FLASH_MODE=TORCH`
  and strength level 4, matching the requested fraction of 23 capture levels.
  Camera streaming continued. The light was returned to off afterward.
- A local-only hotspot started while the existing upstream Wi-Fi connection
  stayed connected. Two Wi-Fi interfaces were active simultaneously. This
  proves hotspot creation, not printer access from an offline hotspot client.
- Short-run battery temperature was about 23–24°C and Android's overall thermal
  status was 0. This does not establish continuous charging adequacy or long-print
  temperatures, especially with sustained torch use.
- Real printer remained ready/standby, unhomed, with both heater targets zero.
  No real motion, homing, extrusion, calibration, print start, Klipper restart
  or software update was performed.

## Passed against the fixture, using the actual Android UI

`tests/check_controls.py` runs an isolated Moonraker fixture on Windows, maps
only its temporary port over ADB, and launches a nonpersistent debug endpoint
override. Cleanup removes that mapping and returns the app to its saved real
printer endpoint.

- Unhomed axis movement and cold extrusion send no command.
- Homed relative movement and hot extrusion send the expected bounded commands,
  wrapped in save/restore of G-code state.
- Movement outside configured travel limits is blocked.
- Printer readiness and idle state are queried again at command dispatch, so
  queued actions do not rely only on the screen's previous status sample. Commands
  retain the destination that was selected when the action was requested.
- The file list displays fixture G-code; selecting it and confirming sends the
  expected filename to the fixture's print-start API.

No real printer was connected to those command tests.

## Still required before calling the appliance complete

1. Physically connect the dedicated Pixel to a spare Pi USB port. Prove Android
   USB tethering enumeration, Pi DHCP/routing and Wi-Fi internet sharing.
   The running Pi already has `usbnet`, `rndis_host`, `cdc_ether` and `cdc_ncm`
   kernel modules installed; no missing-driver rebuild has been identified.
2. Join the Pixel hotspot from a separate computer with no home-network path
   to the Pi; repeat uploads, camera and controls through the USB gateway.
3. Resolve the hotspot's stable name/password: the current firmware returned
   system-generated credentials despite the public custom-configuration request.
   Persist hotspot ownership outside the Activity before unattended offline use.
4. Prove Orca's actual Send/Device-tab workflow with a representative sliced
   plate. The HTTP upload path is verified, but Orca itself has not been driven.
5. Configure private remote access and stable naming; test from a genuinely
   different network on Leo's everyday phone. No router ports should be exposed.
6. Test the dedicated unrooted phone after reboot, cable removal/reconnection,
   network loss and power loss, without a development computer. The current
   Pixel's PIN, default launcher, boot setup and Linux work were preserved.
7. Confirm the spare USB socket is accessible and measure phone power under
   screen, camera and light load. Test full-print duration and battery-depleted
   recovery. A free data port alone does not establish adequate charging.
8. Before testing physical printer controls, Leo must confirm the printer is
   clear and safe to move/heat. Until then, command tests stay on the fixture.

The app checks installed/available versions and opens Mainsail's existing updater.
It does not update Klipper, flash MCUs or modify the printer configuration.
