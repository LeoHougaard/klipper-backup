# Agent Notes for Voron 0.2

This workspace is the local checkout of `LeoHougaard/klipper-backup`.
Treat `printer_data/config/` as the source of truth for the printer
configuration that should be backed up to GitHub.

## Printer

- Model: Voron V0.2r1, heavily customized over time
- Controller: BTT SKR Pico V1.0 / RP2040
- Host: BTT Pi / CB1 class host
- Toolhead: BTT EBB36 CAN v1.2, STM32G0B1
- Main config: `printer_data/config/printer.cfg`
- Toolhead config: `printer_data/config/toolhead_btt_ebbcan_G0B1_v1.2.cfg`

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
PRINTER_HOST=voron.local
PRINTER_USER=biqu
PRINTER_CONFIG_DIR=~/printer_data/config
```

`.env` is ignored by git.
