# HeatCore 4 + ALPS installation and commissioning

This guide is for this printer's BTT EBB36 CAN v1.2 and the Mellow HeatCore 4
UHF Lite ALPS hotend. The configuration is prepared but must not be deployed
until the new hotend and probe are physically installed and the printer is safe
to move and heat.

## What the configuration assumes

- The supplied temperature sensor is the Mellow PT1000, connected directly to
  EBB36 `TH0` (`PA3`) with the board's 4.7 kOhm pull-up.
- The supplied heater is the 60 W HeatCore 4 heater, connected to EBB36 `HE0`
  (`PB13`). Confirm the heater is the 24 V version before connecting it to this
  24 V printer.
- ALPS is connected to the EBB36 BLTouch header: `EN` to `PB9`, `OUT` to
  `PB8`, plus 5 V and GND.
- Because ALPS detects contact through the nozzle, probe X/Y offsets are zero.
- Z homes at the bed centre (`X60 Y60`) and the static mesh covers
  `X10..110, Y10..110`.
- The existing 0.4 mm nozzle and extruder motion settings are unchanged.

The repository identifies the current extruder as Mini Afterburner gearing, but
does not identify a HeatCore 4-compatible printed mount. HeatCore 4 ALPS is not
a drop-in Mini Afterburner hotend. Install a tested V0-compatible toolhead/mount
that supports the ALPS load path and keeps the hotend rigid. Any rocking,
pinched sensor cable, or load bypass around the ALPS element will make nozzle
probing unsafe or inconsistent.

## Wiring with power disconnected

1. Turn the printer off, unplug mains power, and wait for all LEDs to go dark.
2. Connect the heater to `HE0`. Heater wires have no polarity.
3. Connect the PT1000 to `TH0`. PT1000 wires have no polarity.
4. On the EBB36 BLTouch header connect:
   - ALPS `EN` (Mellow's yellow wire) to `PB9`, the servo/control signal.
   - ALPS `OUT` to `PB8`, the probe sensor signal.
   - ALPS `VCC` to EBB36 `5V`.
   - ALPS `GND` to EBB36 `GND`.
5. Verify every position from the labels/pinout before power-up. Do not infer
   5 V or GND from cable colour or connector orientation.
6. Provide strain relief without preloading the ALPS element, and verify full
   X/Y travel cannot pull the heater, PT1000, or ALPS wiring.

The PTFE guide, extruder, fan ducts, fan screws, and wire bundles must not form
a rigid path between the moving HeatCore/ALPS section and the fixed toolhead.
Verify pressure response after each of those parts is installed. On this
printer, an over-constrained assembly reduced a strong pressure response to
less than half the configured trigger threshold even though the electronics
and trigger wiring were healthy.

Mellow documents several ALPS wiring modes and specifically says that the
yellow wire goes to the middle servo signal when using a BLTouch port. BTT's
official EBB36 v1.2 sample assigns `PB9` as BLTouch control and `PB8` as its
sensor input.

## Checks before any automatic movement

1. Leave the build plate empty and remove filament/plastic from the nozzle.
2. Put one hand on the power switch for every first test.
3. Power on and confirm the reported extruder temperature is close to room
   temperature. A clearly wrong or unstable value means stop and recheck the
   PT1000 wiring; do not command heat.
4. With ALPS disabled, its trigger LED should remain off. A lit/flashing LED
   while `_probe_ready=0` indicates EN wiring/configuration is wrong.
5. In the console run:

   ```gcode
   SET_PIN PIN=_probe_ready VALUE=1
   QUERY_PROBE
   ```

6. `QUERY_PROBE` should report open. Gently press upward on a cold, clean
   nozzle with a metal tool and run `QUERY_PROBE` again; it must report
   triggered. Then disable it:

   ```gcode
   SET_PIN PIN=_probe_ready VALUE=0
   ```

7. Repeat the open/triggered check several times before enabling Z motors.
   If logic is reversed or inconsistent, stop; do not try `G28`.

## ALPS threshold

Use Mellow's USB configuration tool after the ALPS module is wired to the
toolboard and enabled. A lower threshold number is more sensitive; a higher
number requires more nozzle force. Start with the factory value, then tune for
reliable triggering with very light force and no false triggers from fans or
normal toolhead handling. Confirm the module firmware version and use Mellow's
current update procedure if it is older than v2.0.0.

The nozzle must be clean for every Z home and mesh. Mellow recommends adding a
nozzle wipe; no wipe macro is enabled here because this printer's wipe hardware
and coordinates are not known.

## First movement and heater tests

These steps require the user to confirm the printer is physically safe first.

1. Place a hand under the bed with room to catch/support it and be ready to cut
   power. Test X and Y homing individually.
2. Position the bed well below the nozzle. Run `G28 Z` while watching the bed
   rise toward the nozzle. Cut power immediately if it moves away from the
   nozzle, fails to stop on light contact, or the toolhead deflects excessively.
3. Run `PROBE_ACCURACY SAMPLES=10`. Resolve false triggers or poor repeatability
   before continuing.
4. Run `SCREWS_TILT_CALCULATE`, adjust the three bed screws, then repeat until
   the reported corrections are small.
5. Run `BED_MESH_CALIBRATE` and inspect the mesh for plausible shape and range.
6. With the hotend fan verified, heat only to 50 C while watching the displayed
   temperature and the physical hotend. Stop if temperature does not rise
   smoothly or rises while the heater is commanded off.
7. After the 50 C test succeeds, tune the new heater at a normal printing
   temperature:

   ```gcode
   PID_CALIBRATE HEATER=extruder TARGET=245
   SAVE_CONFIG
   ```

8. Recheck the saved PID values, run a controlled heat test, then calibrate the
   first layer/Z offset. The config uses Mellow's 400 C HeatCore/PT1000 rating
   as its safety ceiling and must not be raised above that rating.
9. Re-run input shaping after the mechanical toolhead change.

## Completed commissioning values (2026-07-11)

- Extruder PID at 250 C: `Kp 38.523`, `Ki 11.673`, `Kd 31.782`
- Bed PID at 60 C: `Kp 56.449`, `Ki 2.595`, `Kd 306.941`
- Accelerometer idle noise with the heatsink fan off: X `14.766`, Y `17.593`,
  Z `27.531`
- Input shaper: X `mzv` at `62.2 Hz`; Y `3hump_ei` at `86.6 Hz`

The much higher Y-axis noise observed while the heatsink fan was running was
fan vibration, not an accelerometer fault. Measure idle noise with fans off,
but leave the printer in its completed mechanical configuration for resonance
calibration.

## Primary references

- [Mellow HeatCore 4 product page](https://www.3dmellow.com/products/heatcore-4-uhf-alps-hotend)
- [Mellow ALPS wiring](https://mellow.klipper.cn/en/docs/ProductDoc/ExtensionBoard/fly-alps/wiring/)
- [Mellow ALPS Klipper configuration](https://mellow.klipper.cn/en/docs/ProductDoc/ExtensionBoard/fly-alps/klipper/)
- [Mellow ALPS threshold adjustment](https://mellow.klipper.cn/en/docs/ProductDoc/ExtensionBoard/fly-alps/alpsfz/)
- [Mellow ALPS FAQ](https://mellow.klipper.cn/en/docs/ProductDoc/ExtensionBoard/fly-alps/faq/)
- [BTT EBB official repository and v1.2 sample](https://github.com/bigtreetech/EBB/blob/master/EBB%20CAN%20V1.1%20and%20V1.2%20%28STM32G0B1%29/sample-bigtreetech-ebb-canbus-v1.2.cfg)
- [Klipper configuration reference](https://www.klipper3d.org/Config_Reference.html)
- [Klipper bed-leveling guide](https://www.klipper3d.org/Bed_Level.html)
