# Continuous extrusion retry, 2026-09-08

The newest uploaded part was `user-recovery_PLA_1h23m.gcode`, job 00002D.
Moonraker recorded 261 seconds total and about 10 seconds printing. Immediately
after the purge, Klipper rejected `SET_GCODE_VARIABLE
MACRO=CONTINUOUS_EXTRUSION_CHECK VARIABLE=version VALUE=1` because the macro was
absent. Mainsail's virtual_sdcard `on_error_gcode: CANCEL_PRINT` explains the
cancelled history status. The console has no user CANCEL_PRINT at that time.
The preceding unknown T0 warning was not the fatal error.

OrcaSlicer-2.4.0/scripts/klipper contains the required experimental backend.
The repository copy here adds cancellation and pause handling before Mainsail's
retraction, and accepts sub-picosecond negative lookahead phase roundoff while
preserving the total move duration. Ordinary inactive commands still delegate.
A continuous print can pause and park, but cannot resume a broken constant-feed
path. Cancel and restart it. Ordinary prints retain normal pause/resume behavior.

The original file also contains roughly micrometre-long segments. Offline replay
hit a deposition-limit shutdown at Z1.8 even with the module installed. The
separate retry `user-recovery_PLA_continuous_ready.gcode` combines tiny moves with
the following motion until at least 0.005 mm of path has accumulated. Endpoints
and nominal filament amounts are preserved; nominal speed is recalculated for
0.5 mm/s filament feed. This is a bounded repair to this uploaded file, not a
fix to the slicer's native planner. Future exports need the same full-path check.

## Offline verification

- The source file passes Orca's continuous G-code audit: 63,643 moves, 65 layers.
- The repaired file has 63,442 moves, after combining 201 tiny moves.
- Full repaired-path replay uses the printer's exact Move and LookAheadQueue
  classes and live configured speed, acceleration, Z and extrusion limits.
- XYZ bounds: X/Y 45.128539..74.871461, Z0.2..12.999999 mm.
- Planned object duration: 5005.975 seconds, about 83 minutes plus startup.
- Time-based filament: 2502.987 mm. Maximum average move cross section:
  0.494096 mm2, below the existing 5 mm2 limit. No limits were raised.
- Klipper toolhead SHA256:
  `911d675604003f9cffc1356562de17c85c9bead3d744e53922a0d77d5dee1e3b`.
- Retry G-code SHA256:
  `df342394e17cca07e1d8312b7afc665737f73effcba90227e94c76c884f19146`.

Use `scripts/prepare-continuous-retry.py` to produce a new file, then
`scripts/check-continuous-path.py` with the live toolhead.py and Moonraker
configfile.settings JSON. Run `scripts/check-config.ps1` for module unit tests.

These checks do not exercise a complete Klipper instance, MCU step generation,
real-time queue supply, physical filament feed, adhesion or corner deposition.
The first physical print requires Leo present and the bed cleared, as required
by AGENTS.md. Deployment and physical printing are pending that confirmation.

## Installation and recovery

Use the repository deployment script with `-RestartKlipper` to install both the
module and included config and perform a full service restart. A config restart
alone cannot load changed Python. Confirm ready and the module's API version 1
before starting the repaired upload. Observe startup and the first layers.

Cancel stops the constant-feed mode before Mainsail retracts and disables heat.
Emergency stop remains available. A queue gap or excessive deposition triggers
shutdown. Diagnose the error before firmware restart; do not raise limits just
to bypass it. The deployment script retains the previous config under
`~/agent-config-backups/`. To roll back, remove the continuous_extrusion include
from the previous config state and fully restart Klipper.
