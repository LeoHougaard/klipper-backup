# Native panel improvement contract

Starting point: commit `9b906b2`, APK 0.3.6. Leo assigns this build **1/10**
and the fixed Bambu reference **8/10**. Required result: **at least 9.8 in
at least ten of the twelve fixed categories below**, not an average. Report
all twelve, including any that fall short. Work directly with sol; no other
models or review agents. No iteration/time limit was supplied.

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
| Spacing | 8 | 9.0 | Shared gutters and adjacent motion groups; some dense secondary screens retain older layouts. |
| Icon quality | 8 | 9.0 | One pinned professional family, semantic symbols, no rough custom strokes; outline style preference remains subjective. |
| Borders and surfaces | 8 | 9.0 | Ordinary outlines removed; focus/input/danger edges retained deliberately. |
| Type hierarchy | 8 | 8.5 | Primary actions separated from parameter editing; secondary pages have not been redesigned wholesale. |
| Legibility | 8 | 9.0 | Larger temperatures, clear units and values; wide-screen screenshots are not a dedicated-phone readability trial. |
| Functional grouping | 8 | 9.5 | Spatial XY, vertical Z/E, related filament actions, tappable plate and grouped presets. |
| Density and restraint | 8 | 9.0 | Removes duplicated actions and persistent preset clutter; open space is retained intentionally. |
| Touch geometry | 8 | 9.5 | At least 48 dp sampled buttons; native wheel sectors and drag cancellation exercised. |
| Interaction clarity | 8 | 9.0 | Automatic Load primary, manual heating secondary, precision editors and meaningful menu glyphs. |
| State consistency | 8 | 9.0 | Matched cold/heating/printing/paused/calibration views keep common chrome and truthful controls. |
| Cross-screen finish | 8 | 9.0 | Shared render/surface fixes reach all captured screens; subjective polish above the reference is not established to tenths. |

**0 of 12 estimates reach 9.8. Do not call the requested target passed.**
Further promotion to that threshold needs a reliable design judgment on the
actual candidate. The reproducible geometry and command tests cannot certify
a 9.8 aesthetic result, and changing scores to claim it would defeat this loop.
