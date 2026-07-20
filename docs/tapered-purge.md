# Rear-heated adaptive tapered purge

`PRINT_START` uses the repository-owned `REAR_PURGE_PARK` and `LINE_PURGE`
macros from `printer_data/config/tapered_line_purge.cfg`. The Moonraker-managed
KAMP `Line_Purge.cfg` is deliberately not included.

The sequence is:

1. Home X/Y without touching the bed and park at the rear Y limit while the bed
   and nozzle reach the 150 C probing temperature.
2. Home Z, then generate the native adaptive bed mesh.
3. Select a purge lane to the left or right of the sliced-object polygons and
   park at that lane's rear edge while the nozzle reaches print temperature.
4. Restore the previous 4 mm retract plus a small free-air purge over the rear
   edge, snap the strand, and move onto the sheet.
5. Draw a 55 mm line toward the front in six segments. Its calculated width
   tapers from 1.60 mm to 0.45 mm at a 0.20 mm layer height.

The macro refuses to draw through a model. If neither side has enough room for
the configured 5 mm centre-line margin, it stops with a message asking for a
reserved side lane. It uses Moonraker's processed object polygons, with a
left-edge fallback for G-code that has no object data.

## Physical verification

Before the first live test, confirm that `Y=120` places the nozzle centre just
behind the spring-steel sheet without contacting the rear frame, bed wiring, or
clips. Keep the bed empty and be ready to stop the printer during the first
rear park, Z home, free-air prime, and purge move.

The tuneable values live in `_KAMP_Settings` in `KAMP_Settings.cfg`. In
particular, adjust `rear_prime_amount` if the purge begins starved or produces
too much hanging filament. Adjust `purge_end_width` only if it continues to
match the slicer's intended first-layer line width.
