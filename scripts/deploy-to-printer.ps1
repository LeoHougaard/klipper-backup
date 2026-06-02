param(
    [string]$PrinterHost = $env:PRINTER_HOST,
    [string]$PrinterUser = $env:PRINTER_USER,
    [string]$PrinterConfigDir = $env:PRINTER_CONFIG_DIR,
    [switch]$RestartKlipper
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "read-env.ps1")

if (-not $PrinterHost) { throw "Set PRINTER_HOST in .env or pass -PrinterHost." }
if (-not $PrinterUser) { $PrinterUser = "biqu" }
if (-not $PrinterConfigDir) { $PrinterConfigDir = "~/printer_data/config" }

$status = git status --porcelain
if ($status) {
    Write-Host "Refusing to deploy with uncommitted changes:"
    git status --short
    exit 1
}

$target = "$PrinterUser@$PrinterHost"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$localConfig = Join-Path $PSScriptRoot "..\printer_data\config"

ssh $target "mkdir -p ~/agent-config-backups && cp -a $PrinterConfigDir ~/agent-config-backups/config-$stamp"
scp -r "$localConfig\*" "$target`:$PrinterConfigDir/"

if ($RestartKlipper) {
    ssh $target "sudo systemctl restart klipper"
} else {
    Write-Host "Deployed config. Restart Klipper from Mainsail/Moonraker when ready."
}
