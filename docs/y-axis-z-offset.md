# Front/rear Z-offset compensation

This printer has a persistent probe correction that interpolates smoothly along
the Y axis. It compensates the repeatable component of Voron V0 cantilever-bed
flex seen during nozzle probing. The correction is applied before Klipper uses
probe results, so bed meshes, Mainsail heightmaps, and screw-tilt calculations
all see the corrected bed shape. It does not make an unreliable probe safe and
cannot correct random or missed ALPS triggers.

## Mainsail controls

Two direct offset macros are exposed on the Mainsail dashboard:

- `FRONT_Z_OFFSET OFFSET=<mm>` controls the correction at `Y=10`.
- `REAR_Z_OFFSET OFFSET=<mm>` controls the correction at `Y=110`.

`CALIBRATE_BED_TILT` is the primary workflow for setting those two values. It
homes the printer, generates a full bed mesh, then opens
Mainsail's normal manual-probe popup at the front and rear. At each position,
use the popup's Z controls until the paper drag is correct and press `ACCEPT`.
The wizard preserves the average Z offset, changes only the front-to-rear
tilt, and saves both resulting values automatically. After the rear paper test,
it rebuilds the bed mesh with the new correction; the resulting Mainsail
heightmap is therefore compensated and can be used to adjust the bed screws.

Both calibration macros accept an odd `PROBE_COUNT` from 3 through 15:

```gcode
CALIBRATE_BED_TILT PROBE_COUNT=9
CALIBRATE_COMPENSATED_BED_MESH PROBE_COUNT=13
```

The first command performs the guided paper calibration and uses a 9x9 grid
for both its initial and final meshes. The second command only homes and builds
a corrected 13x13 heightmap, which is useful after turning bed screws. Larger
grids provide more detail but take substantially longer because every point is
sampled by the nozzle probe.

After that calibration, use Mainsail's ordinary global Z-offset control only
for the small final whole-bed adjustment normally made while watching a first
layer. The direct `FRONT_Z_OFFSET` and `REAR_Z_OFFSET` macros remain available
for advanced correction, but they are not the normal calibration path.

Positive values increase the measured probe height; negative values decrease
it. Klipper interpolates linearly between the two endpoint values and clamps
the correction outside `Y=10..110`.

The initial values are front `+0.030 mm` and rear `-0.030 mm`, giving a
`0.060 mm` front-to-rear difference while leaving the centre at zero. Tune in
small increments, normally no more than `0.010 mm` at a time. Guided and direct
endpoint values are accepted over `-1.0..+1.0 mm` so the paper calibration can
represent substantial cantilever-bed flex.

Selecting a direct endpoint macro without `OFFSET` reports its current value.
A supplied value is persisted in the runtime-generated `variables.cfg` file
and takes effect the next time a mesh or other probe-based measurement is run.
It does not alter an already-generated mesh or an active print.

## Bed-screw workflow

1. Run `CALIBRATE_BED_TILT PROBE_COUNT=5` to establish the probe correction.
2. Inspect the automatically rebuilt, compensated heightmap.
3. Adjust the physical bed screws based on that map.
4. Run `CALIBRATE_COMPENSATED_BED_MESH PROBE_COUNT=9` or `13`.
5. Repeat screw adjustment and corrected mapping until the bed is as flat as
   practical, then use the ordinary global Z offset only for final first-layer
   squish.

## Implementation

- Active configuration: `printer_data/config/y_axis_z_offset.cfg`
- Klipper extra source: `klipper_extras/y_axis_z_offset.py`
- Runtime persistence: `printer_data/config/variables.cfg` on the printer;
  this generated file is intentionally ignored by Git.

The extra subscribes to Klipper's `probe:update_results` event and adjusts each
reported probe Z value before `bed_mesh` or `screws_tilt_adjust` consumes it.
The normal deployment script backs up and installs both printer configuration
and repository-maintained Klipper extras before optionally restarting Klipper
through Moonraker. The script treats failed SSH, SCP, and restart operations as
deployment failures instead of continuing silently.
Deployments containing Python extras use a full Klipper service restart because
Klipper's normal configuration restart does not reload imported Python code.

`klipper-agent.gitignore` is configured as the Klipper checkout's local
`core.excludesFile`. This keeps the repository-managed extra from making
Klipper appear dirty to Moonraker's update manager. A normal Klipper update
should preserve the ignored file; rerun the deployment script if a recovery or
reinstallation removes it.

Changing either endpoint changes future probe measurements and meshes. Treat
deployment and first testing as a motion/probe change: clear the bed, keep a
hand near power, begin with the documented small values, and verify the
heightmap and first-layer direction before making larger corrections.

Run guided calibration with a clean nozzle and an empty bed. The macro performs
homing and a full automatic mesh before moving to the manual paper-contact
positions. `ABORT` cancels without changing either saved offset.
