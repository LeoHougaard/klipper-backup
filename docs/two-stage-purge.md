# KAMP-volume heat park and two-stage purge

`PRINT_START` uses the repository-owned `PURGE_LINE_HEAT_PARK` and `LINE_PURGE`
macros from `printer_data/config/two_stage_line_purge.cfg`. The Moonraker-managed
KAMP `Line_Purge.cfg` and `Smart_Park.cfg` are deliberately not included.

The sequence is:

1. Home X/Y without touching the bed and wait at the rear limit while the bed
   and nozzle reach the 150 C probing temperature.
2. Home Z, then generate the native adaptive bed mesh.
3. Calculate the same style of adaptive purge placement as KAMP: use an X-axis
   line immediately in front of the model when space exists, otherwise use a
   Y-axis line beside it.
4. Move to that line origin at Z10, lower vertically to the configured 0.20 mm
   first-extrusion height, run the part-cooling output at full speed, and wait
   there for final nozzle temperature. Ooze is therefore deposited and cooled
   at the purge origin on the build plate. The part fan switches off before
   extrusion begins.
5. Restore the previous 4 mm retract and reproduce the original configured KAMP
   purge: move 18 mm while extruding 18 mm of filament, using KAMP's original
   flow-rate speed calculation. Then move another 20 mm at 25 mm/s with
   extrusion calculated for a fixed 0.45 mm line width. There is no transition
   section between them.

The complete 38 mm path is clamped inside the printable limits. The macro also
estimates the physical width of the original-volume KAMP section from filament
diameter and purge height, then keeps that entire footprint plus 2 mm clearance
inside the plate and away from the model. If there is no safe front or side
lane, it stops instead of drawing through the model. G-code without Moonraker
object polygons uses a deterministic X-axis line at the front edge.

All XY positioning occurs at the configured 10 mm travel height. The nozzle
only lowers vertically at the purge origin, and the macro rejects a zero or
negative purge height.

The tuneable values live in `_KAMP_Settings` in `KAMP_Settings.cfg`. Adjust
`tip_distance` if the wide beginning is starved or produces excessive material,
and adjust `purge_standard_width` only if it continues to match the slicer's
intended first-layer line width.
