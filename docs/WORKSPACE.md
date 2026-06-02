# Workspace Guide

This directory is now a Git checkout of:

```text
https://github.com/LeoHougaard/klipper-backup.git
```

The important tracked printer files live in `printer_data/config/`.

## What Changed

The previous loose files in this folder were moved to
`docs/recovered-local-notes/` so they remain available without being confused
with active Klipper config.

## Daily Backup Flow

Use this before editing:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\sync-from-github.ps1
```

Use this after committing:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\sync-to-github.ps1
```

## Deploy Flow

Preview what differs between this checkout and the printer:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\diff-printer.ps1 -PrinterHost voron.local
```

Deploy tracked config files:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy-to-printer.ps1 -PrinterHost voron.local
```

The deploy script creates a timestamped backup on the printer before copying.

## Information Still Needed

Fill these in when convenient:

- Printer hostname or IP
- SSH username
- Whether SSH key login is already configured
- Whether Moonraker authorization is enabled
- Whether Klipper-Backup is still running automatically on the printer
