# Pixel panel 0.3.0 verification

## Stricter quality scale requested after 0.2.0

For the next iteration, Leo explicitly anchors commit `38db008` as **5/10**.
Earlier absolute review ratings do not count toward this new target. Preserve
that baseline APK/screens in ignored `.build/design/quality-baseline/`.

The fixed weighted rubric is task efficiency 25%, hierarchy/density 25%,
machine-state clarity 20%, visual coherence 15%, and touch/context/recovery 15%.
Seven requires conspicuous improvement in at least three categories while core
workflows remain complete. Nine requires major improvement in all five, no
category below eight, at least two materially shortened common workflows, live
context during adjustments, distinct outcome/recovery states, and actual Pixel
workflow evidence. Fit and clipping checks alone remain approximately 5–6.

The first strict critique identified oversized shell/thermal/sidebar regions,
navigation-only Home cards, weak current-job identity, generic component forms,
oversized editors and generic paused/end states. These are the improvement
categories; adding unrelated features does not satisfy the target.

| Strict review | Efficiency | Hierarchy/density | State clarity | Coherence | Touch/context/recovery | Weighted |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Anchored baseline `38db008` | 5.0 | 5.0 | 5.0 | 5.0 | 5.0 | 5.00 |
| Quality round 2 | 8.5 | 8.0 | 8.0 | 8.5 | 8.0 | 8.20 |
| Fresh final review | 9.2 | 9.1 | 9.2 | 8.5 | 9.0 | **9.04** |

The round-2 review required local Resume after paused filament work, initial
filament temperature matching the current job, truthful heater-on completion
language, useful recovery information instead of repeated status/actions, and
secondary screens brought up to the new Home hierarchy. These were implemented
and the fresh reviewer found no material UI/UX blocker under the fixed rubric.
The final rating is a reviewer judgment on this user-defined relative scale,
not a universal product benchmark.

Quality round 3 contains 24 actual-device states, including real camera-on
printing/paused views, heating, long names, missing previews and paused filament.
Its accessibility data contains 313 enabled clickable targets, all at least
48 dp. Workflow screenshots and passing results are in
`.build/design/quality-workflows/`:

- Direct Home homing, preheat and cooldown, with updated thermal values shown.
- Recent plate → Start → Pause → load filament at the matched 240°C setting →
  local Resume → active Home.
- One-tap inline tuning returning 110%, then a contextual target editor returning
  200°C while retaining the job identity/progress.

All command workflows used the isolated fixture, not physical printer hardware.
Baseline preheat required two taps versus one now; homing removes a navigation
step; tuning removes its mandatory sheet; recent plate selection removes the
Files-list step. Existing guarded dispatch, uncertainty handling, emergency
priority and explicit Fast controls remain regression gates.

Date: 2026-11-11. Base Pixel 10, Android 17 / API 37. The reference phone is
rooted for its existing Linux project, but this APK uses no root, shell helper,
Linux service, privileged permission grant or hidden Android API. Runtime grants
were made through Android's normal permission dialogs. A stock second phone
remains a separate acceptance check.

Preserved 0.2.0 baseline APK SHA-256:
`09fba699d8de402cdd352acf71fb7fddfb6efb3f4708a9dccc888eb3f66eb0e9`.

Final 0.3.0 APK SHA-256:
`14b0b924f0625af825845e7cdae9ca014ed3133bd05623d2e6984cc3568749d1`.

## Earlier 0.2.0 redesign evidence

The preserved baseline is commit `dc44d05`, with its APK and eight matched
real-device states in the ignored `.build/design/baseline/` directory. Current
captures use `tests/capture_review.py` against an isolated Moonraker fixture;
the manifest asserts that capture collection issued zero printer commands.
PNG files are paired with actual Android accessibility bounds at density 2.625.

The initial review method followed Theo's independent screenshot critiques and
fresh reviewers after fixes. All agents used GPT-6.1 Sol. Those earlier reviews
were requested without a desired score. The stricter 0.3.0 iteration instead
uses Leo's explicit baseline anchor and fixed rubric above. Actual task
completion, target geometry and recovery checks remain acceptance gates.

| Review | Evidence and findings | Decision |
| --- | --- | --- |
| Baseline | Independent UI/UX ratings around 4/10; generic command panels, frozen camera, web-only calibration, weak idle/printing hierarchy. | Replace the task organization and component system. |
| Round 1 | UI 8.1, UX 6.5–7.8. Clipping, disabled styling, stale fault views, false calibration success, camera/connection coupling, late feedback. | Fix shared layout, state, service and receipt handling. |
| Round 3 | UI 8, UX 7. Modal E-stop overlap, failed discovery cached indefinitely, fan endpoints unreachable, active preset dead end, missing heightmap result. | Preserve the full header, retry discovery, clamp steps, add job-context camera and native mesh view. |
| Round 4 | UI 8.5, UX 8.0–8.3. File Start initially exposed only 41.9 dp; short commands inherited a 15-minute wait; modal focus and shutdown guidance needed refinement. | Pin Start outside scrolling details, bound short-command waits, focus sheets below the header and show fault-relevant guidance. |
| Final | Independent visual/touch review 9/10 for both, with no material visual defects. Independent source review UI 8.8 / UX 8.5 found a partial calibration receipt preceding persistence. | Fix that wording and cancel pre-dispatch work after E-stop; actual-phone recovery-edge checks pass. Promote the redesign. |

The Pixel reports a 138-pixel radius at all four corners and a 173-pixel cutout
inset in the tested landscape direction. The app reserves the full corner radius
horizontally and the cutout inset, preserving vertical space for 48 dp controls.
Dialogs use the same safe margins, leave the whole E-stop visible, and block
background actions while still allowing that physical target to receive touches.

Research used primary Prusa, KlipperScreen, Mainsail, Fluidd and Bambu references
linked in README. Proper Printing's June touchscreen/computer build and October
CNC/printing video informed asynchronous control, complete touch workflows,
camera beside relevant controls, and keeping print execution on Klipper/Pi.
The video research used captions, not a claim to have visually inspected every
frame or replicated the F*ck Around XL's multi-tool/segmented-bed interface.

Current fixture workflow checks on the actual phone additionally prove:

- Full front/rear TESTZ and ACCEPT sequences, explicit safety-limit rejection,
  accepted contacts with unconfirmed saving/mesh completion, and abort. The app reads new
  G-code-store receipts rather than treating an HTTP success as calibration success.
- Repeated live Z, speed and flow adjustments use current values; non-round fan
  percentages reach both 0 and 100 without getting stranded near an endpoint.
- Initial object/help discovery failures recover on later polls.
- An accepted short command with a withheld response reports uncertainty,
  releases normal controls, and is never automatically replayed.
- The full 48 dp E-stop target works while a sheet is open and a normal command
  is delayed. Its result survives the later normal-command callback.
- E-stop also cancels work still in its pre-dispatch status check, even when
  the delayed response incorrectly continues to report Ready.

Final capture coverage is 20 normal-landscape states and 16 reverse-landscape
states, with 416 enabled clickable targets and none below 48 dp. Reverse testing
ended at Leo's request to restore upright viewing; normal-landscape failure
states were reviewed. Reverse dialogs sit slightly farther left, a cosmetic
alignment difference; their controls and complete E-stop still fit safely.

The final native camera preview was visually inspected on the actual phone;
it displays a real advancing image. Live snapshot/MJPEG, authentication,
Moonraker status and Device HTML also pass over the phone's actual Wi-Fi address.
Turning the camera off through its native button leaves the independent gateway
available and video endpoints return 503. A unique comment-only G-code uploaded
with camera off was read back byte-for-byte and deleted, with printing disabled.
The camera and torch are off at handoff; Home remains upright and local sharing
is available. Actual printer framing and sustained thermal/power behavior remain
unverified.

Evaluation corrections: Android's `uiautomator dump` can return without refreshing
XML while video is updating. The shared helper now refuses stale XML. The gateway
test records the native toggle's current bounds before starting video, then uses
that unchanged target to stop it and confirms both fresh UI and service state.
This fixes the test rather than counting old accessibility data as live evidence.

`tests/check_device_ui.cjs` checks the standalone Device surface in silent
headless Chrome with all HTTP intercepted. Idle/printing/pause/resume, changed-job
confirmation, dispatch-time homing, 48 px mobile buttons, no horizontal overflow,
and independent sticky E-stop pass. These are browser fixture checks, not proof
that Orca's actual embedded Device tab has been operated.

Fast controls is an explicit persistent app option requested by Leo. It skips
routine confirmation sheets, shows brief warnings and a visible FAST indicator,
and leaves printer configuration/firmware interlocks unchanged. It is separate
from authorization to perform physical tests during development.

## Earlier 0.1.0 hardware baseline

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
- In the old coupled service, Stop released the foreground service and HTTP listener. No active monitoring
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
   Ownership now lives in the connection service; process-death and cold-boot
   behavior still require the dedicated-phone test.
4. Prove Orca's actual Send/Device-tab workflow with a representative sliced
   plate. The HTTP upload path is verified, but Orca itself has not been driven.
5. Configure private remote access and stable naming; test from a genuinely
   different network on Leo's everyday phone. No router ports should be exposed.
6. Test the dedicated unrooted phone after reboot, cable removal/reconnection,
   network loss and power loss, without a development computer. The current
   Pixel's PIN, default launcher, boot setup and Linux work were preserved.
7. The spare USB socket is confirmed accessible. Measure phone power under
   screen, camera and light load. Test full-print duration and battery-depleted
   recovery. A free data port alone does not establish adequate charging.
8. Before testing physical printer controls, Leo must confirm the printer is
   clear and safe to move/heat. Until then, command tests stay on the fixture.

The app checks installed/available versions and opens Mainsail's existing updater.
It does not update Klipper, flash MCUs or modify the printer configuration.
