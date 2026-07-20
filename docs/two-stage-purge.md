# KAMP-style heat park and two-stage purge

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
   first-extrusion height, and wait there for final nozzle temperature. Ooze is
   therefore deposited at the purge origin on the build plate.
5. Restore the previous 4 mm retract, draw exactly 10 mm at a fixed 1.60 mm
   width, then switch directly to exactly 20 mm at a fixed 0.45 mm width. There
   is no tapered transition between the two sections.

The line is clamped inside the printable limits. If there is no safe front or
side lane, the macro stops instead of drawing through the model. G-code without
Moonraker object polygons uses a deterministic X-axis line at the front edge.

All XY positioning occurs at the configured 10 mm travel height. The nozzle
only lowers vertically at the purge origin, and the macro rejects a zero or
negative purge height.

The tuneable values live in `_KAMP_Settings` in `KAMP_Settings.cfg`. Adjust
`tip_distance` if the wide beginning is starved or produces excessive material,
and adjust `purge_standard_width` only if it continues to match the slicer's
intended first-layer line width.
