# Native panel improvement contract

Starting point: commit `9b906b2`, APK 0.3.6. Leo assigns this build **1/10**
and the fixed Bambu reference **8/10**. Required result: **at least 9.8 in
at least ten of the twelve fixed categories below**, not an average. Report
all twelve, including any that fall short. Work directly with sol. Leo later explicitly requested Terra input; its read-only
audit is recorded in `evaluation/terra-input.md`. Do not start automatic review loops. No iteration/time limit was supplied.

## Fixed comparison

The reference is the exact H2D overview, control, movement, speed, nozzle and
filament screenshots from the [official manual](https://csm.bblcdn.com/hub/4668d0ca43994ff3bff4b37f1a65c2e7.pdf),
captured before this loop. Immutable PNG/JPEG copies are in
`.build/ui-hillclimb/reference/`; durable SHA-256 hashes, baseline identity,
category names and anchors are in `evaluation/reference-lock.json`.
Bambu's anchor stays 8 in every
iteration. Do not replace those images, renormalize the scale around a new
candidate, reward novelty, or increase the reference score over time.

Match state, fixture data, orientation, device size (2424 x 1080), Android
density, font scale and image display size. Inspect screenshots at native
resolution and at the same displayed scale as the reference. Preserve the
starting screenshots and the best verified candidate, including APK hashes.

The evaluation set is Home idle/heating/printing/paused, Move, Filament,
Files, More, temperature/keypad, tuning, maintenance, calibration and
heightmap. Keep difficult cases: long filenames, disabled/unhomed/cold
controls, active heating, and a modal with the emergency rail accessible.

## Rubric locked before editing

| Category | Evidence required for the 9.8 gate |
| --- | --- |
| 1. Alignment | Icon/label groups, wheel center, numbers and paired controls share intended axes; no visible unexplained offset. |
| 2. Spacing | Consistent internal and external spacing; equal peers have equal gaps; no accidental empty bands. |
| 3. Icon quality | One professional vector family, correct meaning, consistent optical size/weight, no rough custom substitutes. |
| 4. Borders and surfaces | Borders express interaction or focus; no double edges, arbitrary outlines or mismatched radii. |
| 5. Type hierarchy | Clear primary reading/action, stable type scale and line height, no competing headings. |
| 6. Legibility | Readable numbers/labels at the actual review size, sufficient contrast, no truncation of essential values. |
| 7. Functional grouping | Motion, filament, temperatures and job actions form understandable related controls. |
| 8. Density and restraint | Frequent actions visible, secondary settings tucked away; open space supports hierarchy rather than filler. |
| 9. Touch geometry | Targets at least 48 dp, no overlap/clipping, visual and actual targets agree, drags do not accidentally jog. |
| 10. Interaction clarity | Editable values and buttons recognizable; selection, disabled and pressed states clear. |
| 11. State consistency | Idle, heating, printing, paused and recovery keep the same visual language and truthful state. |
| 12. Cross-screen finish | Main pages and dialogs retain consistent icon, type, alignment and surface treatment. |

Ratings above 8 must name a visible improvement over the unchanged reference,
not merely an improvement over the starting build. Scores are sol's design
judgment, not objective measurements or Leo's acceptance. A functional pass
cannot establish 9.8 visual quality. If evidence cannot distinguish 9.8 from
a lower score, mark the gate **unverified**, not passed. Do not round up or
change the rubric to claim completion.

## Correctness gates

All actions go to the task-owned loopback fixture on emulator-5554. No real
printer, Pixel, printer config, firmware, global settings or external service
changes. Preserve manual XYZE, exact positions/distances/speeds, automatic
Load/Unload and cancellation, Level, maintenance calibration, Files, tuning,
temperatures and persistent E-stop. Preserve the PC's responsiveness.

Build/signature and config checks must pass. Use matched native captures and
accessibility bounds for visual checks; run affected interaction workflows
after a component change. Promote only evidence-supported improvements that
retain correctness. Keep a failure ledger and one record per experiment.

Reproduce the review environment with:
`python pixel-panel/tests/review_server.py --serial emulator-5554`.
For this exact fixture data, add
`--fixture-snapshot pixel-panel/evaluation/fixture-data.json`.
The app must already be opened on its isolated endpoint. Native captures
come from POST `/select` on port 18726 with fixed `state` and `screen` values.
With T3 Device open, set `AGENT_DEVICE_COMMAND` to the bound command returned by
`device_open` before starting the server and tests. The adapter uses that same
session for snapshots and presses; a second UIAutomator inspector conflicts
with the device helper. Healthy snapshots may report scroll-hidden content as
partial; that is different from a truncated or unhealthy capture.

## Initial failure ledger

| Shared cause | Observed examples | Expected / confidence / impact |
| --- | --- | --- |
| Icon and label rendering | Button icon hugs the left edge while text centers; wheel home icon sits above center. | Center the combined mark and label; use consistent vector assets. High confidence, high impact. |
| Excess outlines | Ordinary controls, status tiles and selected actions all use different outlines. | Neutral surfaces carry grouping; reserve strong outlines for focus/danger. High confidence, high impact. |
| Independent layout constants | Button rows use 6 dp gaps while rails use 8; per-screen padding varies. | One spacing system, checked across matched screens. High confidence, medium/high impact. |
| Competing visual weight | Small edits receive large pencil icons; each setting looks like a primary action. | Strong actions, quiet parameter edits, clear numeric readings. High confidence, high impact. |

## Iterations

Baseline: preserve exact source and captures before edits. Starting visual
anchor remains 1; working command workflows are regression requirements.

| Pass | Target / change | Decision and evidence |
| --- | --- | --- |
| 1 | Replace hand-drawn icons with pinned Material Symbols; center icon-label groups. | Promote direction. Home median horizontal ink offset dropped from 5.4 dp to 1.2 dp. SVG padding and multiline controls still caused small offsets. |
| 2 | Remove ordinary outlines and unify 8 dp row gaps. | Reject the navigation background geometry: it consumed the content gutter. Keep the border-removal direction. |
| 2b | Put the navigation background inside its own gutter. | Promote corrected geometry; the outer content spacing is restored. |
| 3 | Use actual SVG artwork bounds and the native multiline text layout when centering. | Promote. Home median offset 0.2 dp, max 0.6 dp. No sampled button targets below 48 dp. |
| 4 | Quieter parameter icons, clearer values and temperature readings; automatic Load is primary when cold. | Promote. Settings no longer compete with actions, and manual heating is secondary. |
| 5 | Make the plate itself open details; put heat presets/cooldown inside Heat. | Promote. Removes three persistent preset controls and a duplicate details button, retaining their functionality. |
| 6 | One connected system menu surface, with meaningful dividers. | Promote. Removes scalloped stacked tile edges. Seventeen native views captured and visually inspected. |
| 7 | Adapt the dedicated Move page to larger XY plus adjacent Z/E columns. | Promote. Home and paused Filament are pixel-identical to pass 6; the affected Move commands and paused motion pass. |
| 8 | Use wrench/grid/external-link symbols for those meanings; remove the unnecessary Filament glyph. | Final semantic icon polish, with the same vector family and control geometry. |
| 9 | Remove Filament's full-width tabs and repeated temperature controls; group selected material with automatic Load/Unload and separate manual feed. | Reject the first arrangement: excessive separation and a wrapped Retract label. Promote the revised vertical manual-feed group. Custom material selection sends no command; exact distance/speed are shared with Move. |
| 10 | Use 18 sp shared headings, align the Filament header to its content, use neutral disabled ink, remove duplicate heating progress and update the Level/Tune icon with state. | Promote. Cold/heating/hot/paused captures retain readable values and expose Cancel instead of overlapping Resume. |
| 11 | Make both idle and active print previews one connected tappable card with a quiet caption. | Promote. Native workflow opens details from the whole active model area; no separate raised details button. |
| 12 | Extend a thumbnail's flat opaque corner color into its preview background, retaining FIT_CENTER. | Matched idle/printing/paused captures verify the removal of contrasting letterbox bands without cropping the model. Nonuniform or transparent corners retain the neutral surface. |
| 13 | Use filled Pause/Play variants from the same pinned Material family. | Promote. Pause no longer resembles two outlined zeros at review size; filled Resume/Start symbols remain centered in their existing targets. |

The raster diagnostic was corrected to exclude dark surface fragments from
foreground measurements, then rerun on both baseline and candidates. Its
numbers are not aesthetic scores. The Home chooser helper was corrected to
select Voron when Android highlights Quickstep after an APK replacement;
it always chooses Just once. Speed-test cleanup now handles a paused original
state rather than assuming idle Home. These harness fixes do not relax gates.

## Current provisional ratings

These are sol's coarse design judgments, with roughly half a point of
uncertainty, not automatic test results. Bambu remains exactly 8. A 9.5
estimate cannot be rounded up to meet 9.8. The required gate is **unverified**.

| Category | Bambu (locked) | Candidate estimate | Evidence / remaining uncertainty |
| --- | ---: | ---: | --- |
| Alignment | 8 | 9.5 | Rendered button ink offsets below 0.8 dp in the broad sample; typographic optical balance still needs taste judgment. |
| Spacing | 8 | 9.5 | Filament now aligns its header and functional groups to the same insets, without the oversized tab band or wrapped feed label. |
| Icon quality | 8 | 9.5 | One pinned professional family and semantic symbols; filled transport variants remove the ambiguous outlined Pause bars while retaining optical centering. |
| Borders and surfaces | 8 | 9.0 | Ordinary outlines removed; focus/input/danger edges retained deliberately. |
| Type hierarchy | 8 | 9.0 | Shared page/dialog headings are 18 sp; Filament has one heading and equal secondary captions, with the selected target readable separately. |
| Legibility | 8 | 9.0 | Larger temperatures, clear units and values; wide-screen screenshots are not a dedicated-phone readability trial. |
| Functional grouping | 8 | 9.5 | Spatial XY, vertical Z/E, related filament actions, tappable plate and grouped presets. |
| Density and restraint | 8 | 9.5 | Removes redundant Filament tabs, repeated target/status controls and the separate active-print details button; manual feed becomes one related controller. |
| Touch geometry | 8 | 9.5 | At least 48 dp sampled buttons; native wheel sectors and drag cancellation exercised. |
| Interaction clarity | 8 | 9.5 | Material selection does not heat; Load/Unload owns automatic heating. Manual feed shares precise parameters, and active/idle plate details behave alike. |
| State consistency | 8 | 9.5 | Level/Tune updates its icon as well as its name; heating progress is shown once and paused Resume stays separate from Cancel heating. |
| Cross-screen finish | 8 | 9.0 | Shared render/surface fixes reach all captured screens; subjective polish above the reference is not established to tenths. |

**0 of 12 estimates reach 9.8. Do not call the requested target passed.**
Further promotion to that threshold needs a reliable design judgment on the
actual candidate. The reproducible geometry and command tests cannot certify
a 9.8 aesthetic result, and changing scores to claim it would defeat this loop.

The promoted review build is 0.3.8 (code 11). `check_layout_workflows.py` passed
the complete representative workflow, including active preview details, leveling,
temperature gestures/keypad, live tuning, pause/filament/resume, both paper
contacts, Files, E-stop during entry and fixture recovery. The focused
`check_filament_task.py` passed custom selection without heating, cancellation,
7.5 mm feed/retract at 4 mm/s and paused heating geometry. All commands went to
the emulator's loopback fixture. The sampled native buttons retain at least
48 dp targets; measured horizontal ink offsets remain below 0.8 dp.

Final native captures are under `.build/ui-hillclimb/iteration-12/captures/`.
Pass 13's affected views and APK are under `iteration-13/`; the five matched
main views and secondary transport-icon views supersede the corresponding
pass 12 images. Other pass 12 views are unaffected by the two asset changes.
The collector now verifies the installed APK against the built APK and freezes
source/APK hashes before capture, rejecting edits during collection. Pass 11's
interim captures were superseded because its source metadata was recorded after
the preview-backdrop edit; they are visual working notes, not final provenance.
The frozen reference images, synthetic state and Bambu anchor have not changed.


## 0.3.9: stronger evidence and motion

The Bambu images and all twelve scores remain fixed at 8. The new
`evaluation/rubric-v2.json` keeps the original 9.8-in-ten gate, with explicit
failure evidence and separate actuation and animation criteria. Earlier design
estimates are approximate judgments, not ground truth or tenth-point precision.
The visual gate remains unverified.

| Pass | Shared change | Evidence and decision |
| --- | --- | --- |
| 14 | Contextual numeric titles/units, returning Cancel, common values, selection/cursor fix | Ten frozen actuation tasks take 40 inputs rather than 45. Multi-digit replacement previously overwrote digits. Promote. |
| 15 | Visible, remembered filament temperature beside Home Load/Unload | Choosing a target emits no heater command; restart preserves it; Load uses it and cancellation restores the owned target. Promote. |
| 16 | Compact print title, related speed/progress and completed manual controls | Pause and Resume retain the same bottom coordinates. Promote. |
| 17 | Observed shutdown/recovery feedback and faithful mock lifecycle | External fresh Ready clears an observed shutdown warning; Ready is not claimed while stop remains unconfirmed. Promote. |
| 18 | Restrained page/sheet opacity and reduced motion | Actual normal/reduced MP4s and rapid interruption checks retained. Smoothness remained unresolved. |
| 19 | Related progress group, explicit Feed and redundant-navigation suppression | All ten actuation tasks pass; primary action stays stable. Promote. |
| 20 | Explicit Retract, corrected status glyph and conditional cooldown | 25 native views retain 48 dp targets. Promote. |
| 21 | Center visible glyph bounds with icon artwork | Both axes below 0.8 dp in affected native views. Promote. |
| 22 | Temporary rendering layer | No measured improvement; reject that layer. Keep truthful initial job progress separately. |
| 23 | Remove experimental layer; retain truthful initial progress | Full representative workflow and all ten actuations pass. Preserve layout-stall baseline. |
| 24 | Disable unused baseline measurement in centered weighted rows | Median slowest Home UI frame: 325 to 14 ms across six entries; overall frame-latency p95: 385 to 105 ms. Same software emulator, no recording or inspection during the comparison. Promote. |
| 25 | Cancelled-state wording and final edge-state review | Clear the plate replaces finished-plate wording. Other handlers and geometry are unchanged. |

Android's weighted rows were doing an unused speculative measurement pass.
The [framework implementation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/LinearLayout.java)
and [layout guidance](https://developer.android.com/develop/ui/views/layout/improving-layouts/optimizing-layouts)
explain the extra work. The improvement is measured on the review emulator;
frame latency is not FPS or a physical-phone benchmark. An earlier hardware
renderer experiment is also preserved and was rejected after worse timings.

`tests/profile_navigation_motion.py` registers navigation coordinates once,
then keeps accessibility inspection outside each transition. The full workflow
recorder separately retains actual page/dialog clips, action telemetry and raw
frame data. Reduced motion is tested through the emulator's native setting and
normal motion is restored afterwards. No actuator waits for an animation.

Twelve additional primary-source pictures from Blackmagic, Home Assistant and
Prusa are cached with pinned hashes. They inform parameter editing, grouping and
readings; they do not replace or rescore Bambu. Terra's requested read-only audit
is recorded in `evaluation/terra-input.md`, including findings that were checked
against actual captures rather than adopted blindly.

Every available candidate is retained under `ui-history/`, including rejected
versions. New records preserve APKs, exact source, screenshots, evaluation,
traces and available videos with hashes. Legacy scorecard snapshots are not new
ratings of each archived candidate. Installing a preserved APK restores code,
not its old runtime state; the saved images preserve exact historical appearance.

The review chooser separates printer state from native screen, and now waits
for Disconnected rather than capturing the still-fresh previous state. New
capture runs reset transient context and pin filament temperature, distances and
feed rates through native controls. The complete workflow passed on pass23;
all ten actuations and 25-view geometry were rerun on pass24. Pass25 changes only
the cancelled-state sentence and adds the remaining edge/editor captures.
No physical printer command or Pixel installation was performed.
