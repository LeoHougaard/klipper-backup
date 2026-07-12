# Front/rear Z-offset compensation

This printer has a runtime-adjustable Z correction that interpolates smoothly
along the Y axis. It is intended to compensate for the repeatable component of
the Voron V0 cantilever-bed flex seen with nozzle probing. It does not make an
unreliable probe safe and cannot correct random or missed ALPS triggers.

## Mainsail controls

Two direct offset macros are exposed on the Mainsail dashboard:

- `FRONT_Z_OFFSET OFFSET=<mm>` controls the correction at `Y=10`.
- `REAR_Z_OFFSET OFFSET=<mm>` controls the correction at `Y=110`.

`CALIBRATE_BED_TILT` is the primary workflow for setting those two values. It
homes the printer, generates a full bed mesh, then opens
Mainsail's normal manual-probe popup at the front and rear. At each position,
use the popup's Z controls until the paper drag is correct and press `ACCEPT`.
The wizard preserves the average Z offset, changes only the front-to-rear
tilt, and saves both resulting values automatically.

After that calibration, use Mainsail's ordinary global Z-offset control only
for the small final whole-bed adjustment normally made while watching a first
layer. The direct `FRONT_Z_OFFSET` and `REAR_Z_OFFSET` macros remain available
for advanced correction, but they are not the normal calibration path.

Positive values raise the nozzle and increase the gap. Negative values lower
the nozzle and reduce the gap. Klipper interpolates linearly between the two
values and clamps the correction to the endpoint value outside `Y=10..110`.

The initial values are front `+0.030 mm` and rear `-0.030 mm`, giving a
`0.060 mm` front-to-rear difference while leaving the centre at zero. Tune in
small increments, normally no more than `0.010 mm` at a time. Guided and direct
endpoint values are accepted over `-1.0..+1.0 mm` so the paper calibration can
represent substantial cantilever-bed flex.

Selecting a macro without supplying `OFFSET` reports its current value. A
supplied value takes effect on subsequent G-code moves immediately, including
during a first layer, and is persisted in the runtime-generated
`variables.cfg` file for future restarts.

## Implementation

- Active configuration: `printer_data/config/y_axis_z_offset.cfg`
- Klipper extra source: `klipper_extras/y_axis_z_offset.py`
- Runtime persistence: `printer_data/config/variables.cfg` on the printer;
  this generated file is intentionally ignored by Git.

The extra wraps Klipper's existing `bed_mesh` move transform. For that reason,
the include for `y_axis_z_offset.cfg` must remain after `[bed_mesh]` in
`printer.cfg`. The normal deployment script backs up and installs both printer
configuration and repository-maintained Klipper extras before optionally
restarting Klipper through Moonraker. The script treats failed SSH, SCP, and
restart operations as deployment failures instead of continuing silently.
Deployments containing Python extras use a full Klipper service restart because
Klipper's normal configuration restart does not reload imported Python code.

`klipper-agent.gitignore` is configured as the Klipper checkout's local
`core.excludesFile`. This keeps the repository-managed extra from making
Klipper appear dirty to Moonraker's update manager. A normal Klipper update
should preserve the ignored file; rerun the deployment script if a recovery or
reinstallation removes it.

Changing either offset changes commanded Z position as a function of Y. Treat
deployment and first testing as a motion/probe change: clear the bed, keep a
hand near power, begin with the documented small values, and verify motion and
first-layer direction before making larger corrections.

Run guided calibration with a clean nozzle and an empty bed. The macro performs
homing and a full automatic mesh before moving to the manual paper-contact
positions. `ABORT` cancels without changing either saved offset.
