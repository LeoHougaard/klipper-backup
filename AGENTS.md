# Agent Notes for Voron 0.2

This workspace is the local checkout of `LeoHougaard/klipper-backup`.
Treat `printer_data/config/` as the source of truth for the printer
configuration that should be backed up to GitHub.

## Printer

- Model: Voron V0.2r1, heavily customized over time
- Controller: BTT SKR Pico V1.0 / RP2040
- Host: BTT Pi / CB1 class host
- Hostname/IP: `voron` / `10.1.39.216`
- SSH user: `biqu`
- SSH key: `C:\Users\Leo\.ssh\voron_biqu_ed25519`
- SSH host fingerprint: `SHA256:YCuxxgKFH6kMWKsyg33cyjeuXornwIheRLbnHsZDKVY`
- Toolhead: BTT EBB36 CAN v1.2, STM32G0B1, with installed Mellow HeatCore 4
  UHF Lite ALPS, 60 W heater, direct PT1000 on EBB36 TH0, ALPS EN on PB9
  and OUT on PB8
- ALPS firmware/threshold: v2.0.0 / 20000
- ALPS load-path warning: PTFE, extruder, shroud, fan screws, and wire bundles
  can brace the moving HeatCore section and suppress pressure detection even
  when the electronics are healthy. Recheck manual trigger and probe accuracy
  after disturbing any of those parts.
- Main config: `printer_data/config/printer.cfg`
- Toolhead config: `printer_data/config/toolhead_btt_ebbcan_G0B1_v1.2.cfg`
- Remote config path: `/home/biqu/printer_data/config/printer.cfg`
- Moonraker: reachable at `http://10.1.39.216:7125`
- Moonraker auth: `login_required=false`, local client is trusted
- SSH status from this Windows machine: key login works for `biqu`.
- Klipper-Backup automatic service: not listed in Moonraker `available_services`
  or update-manager status as of 2026-06-02.
- The live `KAMP/`, `mainsail.cfg`, `print_area_bed_mesh.cfg`, and
  `timelapse.cfg` paths are symlinks into Moonraker-managed vendor checkouts,
  not user-owned config files. `diff-printer.ps1` reports them as live-only on
  Windows; this is expected and does not mean the active user config is newer.
- Front/rear first-layer compensation is implemented by the repository-owned
  `klipper_extras/y_axis_z_offset.py` and
  `printer_data/config/y_axis_z_offset.cfg`. Mainsail exposes exactly
  `FRONT_Z_OFFSET` and `REAR_Z_OFFSET`; values are live, persistent, and
  linearly interpolated over Y10..110. See `docs/y-axis-z-offset.md`.
- `CALIBRATE_BED_TILT` is the guided paper-test workflow. It homes, creates a
  full mesh, then uses Mainsail's native manual-probe UI at front and rear;
  accepted contacts update both offsets while preserving their average.
- `printer_data/config/klipper-agent.gitignore` is installed as Klipper's local
  Git `core.excludesFile`, preventing the repository-managed extra from making
  the Klipper checkout appear dirty to Moonraker's update manager.

## Ground Rules

- Start every session with `git status --short --branch`.
- On this Windows workspace, the first sandboxed shell command may fail with
  `windows sandbox: spawn setup refresh`. This is a local tool-runner issue, not
  a Git or repo problem. If it happens on a required read-only command such as
  `git status --short --branch`, rerun the same command with escalation and a
  short justification instead of treating it as printer/config state.
- Pull from GitHub before making changes:
  `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\sync-from-github.ps1`.
- Do not edit generated/runtime files unless the user asks.
- Before deploying to the printer, commit or stash local changes.
- Use `scripts/deploy-to-printer.ps1` for deployments that include custom
  Klipper extras. It backs up and installs `klipper_extras/*.py` as well as the
  printer config; copying only `printer_data/config/` will leave the printer
  unable to load `[y_axis_z_offset]` after a fresh Klipper installation.
- `deploy-to-printer.ps1 -RestartKlipper` restarts through Moonraker rather
  than `sudo`; SSH key login has no passwordless sudo. Native SSH/SCP failures
  must terminate the deployment rather than being reported as success.
- Deploying `klipper_extras/*.py` requires Moonraker's full Klipper service
  restart. A normal `/printer/restart` reloads config only and leaves updated
  Python modules cached in the running process.
- For motion, heater, homing, probe, CAN, or MCU changes, explain the risk and
  ask the user to confirm the printer is physically safe to test.
- Preserve recovered notes in `docs/recovered-local-notes/`; they are reference
  material, not necessarily active printer config.
- Do not store passwords, API keys, Moonraker auth tokens, Wi-Fi credentials, or
  SSH private keys in this repo.
- Continuous improvement rule: if a thread hits an issue, missing fact, awkward
  workflow, broken script, or repeated workaround that slows down printer work,
  either fix the underlying problem before finishing or add a clear note to this
  file so future threads inherit the lesson. If that issue has been resolved,
  remove or update the stale note instead of letting old workaround guidance
  linger.
- No technical prompt debt: If there is any changes to my ways ask to confirm 
  that the change is one that I want and then update AGENTS.md

## Normal Workflow

1. `.\scripts\sync-from-github.ps1`
2. Inspect relevant files under `printer_data/config/`.
3. Edit config.
4. `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\check-config.ps1`
5. Commit changes.
6. `.\scripts\sync-to-github.ps1`
7. Deploy only after user confirmation:
   `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy-to-printer.ps1 -PrinterHost <host>`

## Printer Access

Create a local `.env` file if you want agents to use a default host:

```powershell
PRINTER_HOST=10.1.39.216
PRINTER_USER=biqu
PRINTER_CONFIG_DIR=~/printer_data/config
PRINTER_SSH_KEY=C:\Users\Leo\.ssh\voron_biqu_ed25519
PRINTER_KLIPPER_DIR=~/klipper
PRINTER_KLIPPER_EXTRAS_DIR=~/klipper/klippy/extras
MOONRAKER_PORT=7125
```

`.env` is ignored by git.
