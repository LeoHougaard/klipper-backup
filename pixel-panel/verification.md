# Pixel panel verification

## 0.3.6: related controls and broader native review

Home uses a circular XY control with center homing, a separate vertical Z
control, and a shared manual-extrusion area beside Load/Unload. Position,
distance and speed editors stay with their axis. The model preview is enlarged;
printing exposes a direct speed editor. Neutral dark surfaces replace the blue
fills; navigation icons are larger. Layout grouping was compared with the
[official Bambu H2D manual](https://csm.bblcdn.com/hub/4668d0ca43994ff3bff4b37f1a65c2e7.pdf),
especially the movement and nozzle/extruder screens. Secondary tuning remains
inside Tune print rather than crowding Home.

`check_manual_filament.py` passed on emulator-5554: automatic heat/wait,
cancellation and target restoration, hot Load/Unload, leaving the workflow,
cold manual E refusal, exact X, Y/Z jogs, paused movement and unhomed refusal.
`check_motion_speed.py` passed edited XY/Z/E feed rates, the whole wheel sector
outside its label, drag cancellation, and direct M220 print-speed editing.
G-code state is preserved. No physical printer commands were issued.

`review_server.py` keeps printer state and native screen selection independent,
with 12 states and 21 screen/editor routes. It captures the actual emulator,
preserves immutable annotation images and supports fit/full-resolution viewing.
It requires an emulator serial and checks the app's loopback endpoint before
opening review controls. Fresh accessibility dumps retry briefly after launch
instead of using an old tree when Android temporarily reports no root.
`check_review_routes.py` passed nine native selections: printing, paused, tuning,
temperature keypad, heightmap, both paper contacts, Files and restored Home.
These review selections issued zero printer commands.

The APK builds and verifies its signature; Python syntax and printer config
checks pass. Native captures and command results are in `.build/space-review/`.
Physical printer and dedicated-phone behavior remain untested for this revision.

## 0.3.5: compact controls and automatic filament handling

The review emulator keeps both rails evenly spaced, with visible button borders
for navigation and step selectors. Home actions use fixed 48 dp targets rather
than stretching to fill cards. Decorative card backgrounds/rounding are removed,
model previews have square bounds with no padded letterbox container, and the
redundant paused “Ready to resume” message is gone.

Home offers Load, Unload and Level. Level reuses the compensated mesh refresh;
paper calibration is under More → Maintenance → Bed calibration. Prepare has
Move and Filament tabs. Move includes exact XYZ position entry, bounded jogs,
and E feed/retract with a custom distance, available idle or paused. The E entry
uses the printer's max_extrude_only_distance, with no extra app-only 50 mm cap.

Load/Unload heat to the selected filament temperature and wait before sending
the macro once. Cancel or leaving the workflow prevents the pending extrusion
and restores a heater target owned by that operation. Target changes, a changed
print, shutdown or connection changes cancel the pending operation; E-stop never
queues a subsequent heater restore or extrusion. Manual E remains independently
subject to Klipper's cold-extrusion readiness. Operations preserve G-code state.

`check_manual_filament.py` passed automatic heating, cancellation, hot Load/Unload,
leaving the workflow, cold manual E refusal, a 7.5 mm E distance in both directions,
exact X positioning, Y/Z jogging, paused movement and unhomed refusal. A separate
fixture run verified E-stop during heating and no later extrusion after recovery.
Native screenshots/results are in `.build/compact-review/`. These checks used
only emulator-5554 and the loopback fixture, with zero physical printer commands.

The updated layout regression also passed equal spacing on both rails, 48 dp
Home actions, square previews, Level's mesh-only command, heating cancellation,
tuning/keypad, pause/resume, Maintenance calibration, Files and E-stop through
the keypad. Build/signature, Python syntax and printer config checks pass.
A final smoke test confirmed that ABS preheat selects 240°C for Load and that
manual E accepts a 100 mm move under the configured 1400 mm limit. Review state
and the normal 5 mm E step were restored afterward.

## 0.3.4: annotated layout corrections

Verified on the Android 15 review emulator at 2424 × 1080, with every command
directed to the loopback Moonraker fixture. No physical Pixel installation or
printer motion/heating/restart was performed for this revision.

- Removed the horizontal title/back/status header. Heaters, connection status,
  E-stop and the contextual Tune print button share a fixed right-side rail.
  Navigation fills the left rail with equal target heights and equal gaps.
- Home and Prepare cards align with the available workspace. Idle Home omits
  the redundant Files shortcut, PREHEAT label and position readout. Printing
  uses separate preview/details columns and no duplicate Camera shortcut.
- Live Z, speed, flow and fan are inside Tune print. Precise numeric changes
  return to their originating tuning/fan editor, with confirmed values. Closing
  the editor hides all those controls again.
- Side-rail temperature gestures use down/up rather than horizontal travel.
  Editors and keypads open to the left of the rail, preserving heater and
  emergency-stop access without the old header-height allowance.

Build/signature verification, Python test syntax and check-config.ps1 pass.
`check_layout_workflows.py` passes equal navigation/gutter geometry, fixture
destination verification, one-release temperature dispatch, keypad bounds,
live Z, exact 115% speed, pause/filament/resume, front/rear paper calibration,
Files details, and E-stop through an open keypad. Native screenshots and XML
are saved in `.build/layout-review/`. Test ADB calls honor ANDROID_SERIAL,
including direct screenshot and swipe subprocesses.

The Camera-off, More and connection layouts were also inspected. The network
information button is labelled Network details so it no longer duplicates the
status rail's Connection details accessibility target.

The records below describe earlier revisions and their separate physical-phone
and gateway checks; those checks were not repeated on physical hardware here.

## 0.3.3

## Touch interaction and purpose

Leo requested a consistent interaction language, fewer permanent instructions,
content-sized layouts, deliberate keyboard/Back behavior, and slide-to-select
temperatures in 5°C steps. This pass implements those workflows rather than
adding controls to occupy empty space.

- Action/edit controls have the same raised outlined treatment. Selection uses
  the existing segmented accent; navigation remains on the rail. Header heater
  controls and connection details now visibly afford interaction.
- Idle Home and Move no longer reserve space for homing instructions. Unhomed
  movement and cold filament handling respond to a tap with a transient error;
  dispatch still validates prerequisites and sends no invalid command. Cold
  feeding also checks the selected temperature at dispatch.
- Home cards fit their content. Redundant idle-state copy and the repeated Tune
  overflow are removed. Tuning values now accept precise entry in the shared
  numeric editor, with separated tuning rows.
- Temperature buttons slide horizontally in 5°C steps with a preview and haptic
  ticks, bounded by configured heater limits. Targets send once on release,
  never continuously while dragging. Cancelling/leaving the app sends nothing.
  A tap retains presets; custom entry retains an explicit Apply action.
- Numeric entry has its own large keypad, validation and delete/clear controls.
  Android's IME is suppressed only for that dialog. Text search/address/key
  fields retain the system keyboard with Done/Go and no fullscreen extraction.
  Back works through page history and returns numeric temperature entry to its
  parent editor. The heater readings remain legible during manual calibration.

`check_touch_flows.py` passes on the actual Pixel: transient homing/cold errors
with no commands; 48 dp keypad fit; invalid input rejected; 230°C dispatched
exactly once; numeric Back/Files Back; search/Enter; precision speed 115%; E-stop
while the keypad is open with no replay. Its result records three isolated
fixture requests and zero physical-printer commands. `check_controls.py` also
passes unhomed/cold rejection, state-preserving moves/extrusion, discovery
recovery, travel bounds and confirmed file start.

`check_temperature_drag.py` passes real touchscreen event injection for nozzle
and bed, 5°C steps in both directions, configured maximum, one command on
release, tap-to-presets and cancellation on Android Home. All commands go to its
isolated fixture. These are UI/command checks, not physical heating tests.

Numeric IME overlap was found visually and fixed with a dialog-scoped IME policy;
no global keyboard setting changes. The UI inspector was once killed by Android
(137); the helper retries that failure once and still requires a fresh successful
dump. An open modal hides background nodes from accessibility, so the E-stop
test uses its freshly measured header bounds from immediately before opening
the keypad, as the existing emergency concurrency test does.

Evidence: `.build/design/touch-flows/`. The app is still not independently
established as 9.5 overall. A dedicated unrooted phone, physical print workflow
and ergonomic use during real prints remain outside this fixture verification.

Installed 0.3.3 APK SHA-256:
`fa23745a60fd4997f5f79d9dd661e31dfa5f5da821845aecfbd61959f000157a`.

## Palette and corner consistency

The broader independent reviews did not uphold the earlier Prepare-only 9.5
rating as a whole-app result. Terra and Astra identified small secondary text,
crowded tuning rows and Home action emphasis. Some free-model reviews could not
view images and supplied unsupported visual scores; those are not visual
acceptance evidence. No overall 9.5 or superiority over commercial printers is
established. This pass follows Leo's request for a focused palette/radius change.

Native controls, Android theme/icon and the browser Device surface now use
Radix Colors 3.0.0: Sage neutrals, one Mint accent, Red and Amber for semantic
status. Neutral backgrounds are Sage 1/2/3, borders Sage 7, secondary/primary
text Sage 11/12. The shared accent is Mint 12, selected fill/border Mint 3/7,
danger Red 11/3/7 and warning Amber 12. There are no per-screen native UI color
literals. The heightmap reuses gray/mint/amber instead of its own three hues.

Control corners are 8 dp; card corners are 20 dp with 12 dp insets; sheets are
24 dp with 16 dp insets. Thus enclosing corners equal inset plus inner radius.
Prepare tabs no longer use horizontal margins that leave mismatched inset
curves. Input, preview and selection shapes share the same role tokens.

Reference: [Radix scale use cases](https://www.radix-ui.com/colors/docs/palette-composition/understanding-the-scale).
Exact sRGB values come from the published `@radix-ui/colors@3.0.0` dark CSS.
This is a visual refinement, with printer command policies unchanged.

Built and installed 0.3.2 on the actual Pixel. Captured eight Prepare states plus
Home and the temperature sheet in `.build/design/palette-radius/`; checked corner
alignment and fit directly. All 171 captured clickable controls remain at least
48 × 48 dp. The capture manifest records zero fixture or hardware commands.
Device-panel browser checks pass: idle/printing/pause/resume, changed-job and
homing checks, 48 px mobile controls, no overflow and independent sticky E-stop.
The saved real endpoint is restored and Prepare is open upright.

Installed APK SHA-256:
`273786c7e8301a0bd3927d26481cd4353bfacc29bfbf2bf4ecfb4917695e78b6`.

## Prepare refinement: individual category gate

Leo subsequently rated the current appearance around 7/10, particularly the
incohesive Prepare controls, and requested 9.5 in **every** category. This is a
new acceptance contract, not the earlier weighted 9.04 result. Categories are
visual cohesion, layout/hierarchy, readability, touch usability, workflow
efficiency, and state/feedback clarity. No average can compensate for a category
below 9.5. Evidence must include paired actual-Pixel Move, cold/paused Filament,
bed setup and front/rear contact states, plus preserved functional checks.

First candidate category scores: cohesion 8.6, hierarchy 8.4, readability 9.1,
touch 9.5, workflow 8.9 and state/feedback 8.8. It was rejected. The reviewer
identified elastic blank regions separating status from actions, homing emphasis
that did not follow state, and insufficient off/heating/ready nozzle feedback.
The next candidate uses composed content-height cards and fixed spacing, state
dependent action emphasis, explicit actual/selected thermal information and
larger unit captions.

The second review passed the first four categories at 9.5, but rejected workflow
and feedback at 9.2: a custom nozzle target did not update the filament selection.
The successful target callback now synchronizes that selection; turning the
heater off preserves it. Actual-Pixel fixture checks cover custom 230°C heating,
readiness and feeding, heater-off selection retention, ABS preset restoration,
loading and local Resume. No physical printer commands are used.

The eight matched Prepare captures contain 171 clickable targets, including
disabled controls, all at least 48 × 48 dp. The independent review counted 151
enabled targets. The capture manifest records zero fixture commands. Test
launches now wait for Android's activity start; the shortened workflow also
verifies the fixture address before issuing any commands.

Fresh final GPT-6.1 Sol review: visual cohesion **9.5**, layout/hierarchy **9.5**,
readability **9.5**, touch usability **9.5**, workflow efficiency **9.5**, and
state/feedback clarity **9.5**. Each category passes independently. The reviewer
inspected the matched baseline, all eight candidate captures, custom-temperature
sequence and resumed Home. These are judgments on Leo's anchored scale, not a
universal product benchmark or physical-hardware acceptance.

`prepare-workflows-final/results.json` reports `passed: true`, 13 fixture
requests and zero physical-printer commands. The installed 0.3.1 build also
passes `check_recovery_edges.py`: an intermediate calibration receipt never
claims saved offsets/completed mesh, and E-stop cancels pending preflight work
despite a stale Ready response. Earlier full calibration, guarded control and
uncertain-command checks remain passing evidence for the unchanged dispatch.

Installed 0.3.1 APK SHA-256:
`240d2f60d2772e50e5cf5ca78dab64c87539059740d97c4a549908f62d8ce156`.
No real motion, heating or print start was performed. Physical filament and
paper-contact usability, dedicated-phone power/networking and stock-phone
acceptance remain separate checks below.

Matched capture command:
`python -X utf8 -u pixel-panel/tests/capture_prepare.py --output pixel-panel/.build/design/prepare-round-2`.
The eight states include unhomed/homed motion, cold/heating/paused filament,
bed setup, and front/rear paper contacts. Capturing performs no printer commands.

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
