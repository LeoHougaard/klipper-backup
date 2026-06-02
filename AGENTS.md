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
- Toolhead: BTT EBB36 CAN v1.2, STM32G0B1
- Main config: `printer_data/config/printer.cfg`
- Toolhead config: `printer_data/config/toolhead_btt_ebbcan_G0B1_v1.2.cfg`
- Remote config path: `/home/biqu/printer_data/config/printer.cfg`
- Moonraker: reachable at `http://10.1.39.216:7125`
- Moonraker auth: `login_required=false`, local client is trusted
- SSH status from this Windows machine: key login works for `biqu`.
- Klipper-Backup automatic service: not listed in Moonraker `available_services`
  or update-manager status as of 2026-06-02.

## Ground Rules

- Start every session with `git status --short --branch`.
- Pull from GitHub before making changes:
  `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\sync-from-github.ps1`.
- Do not edit generated/runtime files unless the user asks.
- Before deploying to the printer, commit or stash local changes.
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
```

`.env` is ignored by git.
