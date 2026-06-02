param(
    [string]$PrinterHost = $env:PRINTER_HOST,
    [string]$PrinterUser = $env:PRINTER_USER,
    [string]$PrinterConfigDir = $env:PRINTER_CONFIG_DIR,
    [string]$PrinterSshKey = $env:PRINTER_SSH_KEY
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

$target = "$PrinterUser@$PrinterHost"
$localConfig = Join-Path $PSScriptRoot "..\printer_data\config"
$sshArgs = @()
if ($PrinterSshKey) {
    $sshArgs += @("-i", $PrinterSshKey)
}

ssh @sshArgs $target "mkdir -p /tmp/agent-klipper-config"
scp @sshArgs -r "$localConfig\*" "$target`:/tmp/agent-klipper-config/"
ssh @sshArgs $target "diff -ru $PrinterConfigDir /tmp/agent-klipper-config || true; rm -rf /tmp/agent-klipper-config"
