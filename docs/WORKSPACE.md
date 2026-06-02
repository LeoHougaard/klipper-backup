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
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\diff-printer.ps1
```

Deploy tracked config files:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy-to-printer.ps1
```

The deploy script creates a timestamped backup on the printer before copying.

## Information Still Needed

Fill these in when convenient:

- Whether Klipper-Backup exists as a cron job or standalone script outside
  Moonraker/systemd

Known details:

- Printer IP: `10.1.39.216`
- Hostname reported by Klipper: `voron`
- SSH user: `biqu`
- SSH key: `C:\Users\Leo\.ssh\voron_biqu_ed25519`
- SSH host fingerprint: `SHA256:YCuxxgKFH6kMWKsyg33cyjeuXornwIheRLbnHsZDKVY`
- Verified SSH command:
  `ssh -i C:\Users\Leo\.ssh\voron_biqu_ed25519 biqu@10.1.39.216 hostname`
- Moonraker auth: `login_required=false`
- Moonraker trusted client: `true`
- Klipper config path: `/home/biqu/printer_data/config/printer.cfg`
- Service list includes `klipper-mcu`, `crowsnest`, `KlipperScreen`,
  `klipper`, `sonar`, and `moonraker`; `crowsnest` is currently failed.
