param(
    [string]$PrinterHost = $env:PRINTER_HOST,
    [string]$PrinterUser = $env:PRINTER_USER,
    [string]$PrinterConfigDir = $env:PRINTER_CONFIG_DIR,
    [string]$PrinterSshKey = $env:PRINTER_SSH_KEY,
    [switch]$RestartKlipper
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "read-env.ps1")

if (-not $PrinterHost) { $PrinterHost = $env:PRINTER_HOST }
if (-not $PrinterUser) { $PrinterUser = $env:PRINTER_USER }
if (-not $PrinterConfigDir) { $PrinterConfigDir = $env:PRINTER_CONFIG_DIR }
if (-not $PrinterSshKey) { $PrinterSshKey = $env:PRINTER_SSH_KEY }

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
$sshArgs = @()
if ($PrinterSshKey) {
    $sshArgs += @("-i", $PrinterSshKey)
}

ssh @sshArgs $target "mkdir -p ~/agent-config-backups && cp -a $PrinterConfigDir ~/agent-config-backups/config-$stamp"
scp @sshArgs -r "$localConfig\*" "$target`:$PrinterConfigDir/"

if ($RestartKlipper) {
    ssh @sshArgs $target "sudo systemctl restart klipper"
} else {
    Write-Host "Deployed config. Restart Klipper from Mainsail/Moonraker when ready."
}
