param(
    [string]$PrinterHost = $env:PRINTER_HOST,
    [string]$PrinterUser = $env:PRINTER_USER,
    [string]$PrinterConfigDir = $env:PRINTER_CONFIG_DIR,
    [string]$PrinterSshKey = $env:PRINTER_SSH_KEY,
    [string]$PrinterKlipperDir = $env:PRINTER_KLIPPER_DIR,
    [string]$PrinterKlipperExtrasDir = $env:PRINTER_KLIPPER_EXTRAS_DIR,
    [switch]$RestartKlipper
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "read-env.ps1")

if (-not $PrinterHost) { $PrinterHost = $env:PRINTER_HOST }
if (-not $PrinterUser) { $PrinterUser = $env:PRINTER_USER }
if (-not $PrinterConfigDir) { $PrinterConfigDir = $env:PRINTER_CONFIG_DIR }
if (-not $PrinterSshKey) { $PrinterSshKey = $env:PRINTER_SSH_KEY }
if (-not $PrinterKlipperDir) { $PrinterKlipperDir = $env:PRINTER_KLIPPER_DIR }
if (-not $PrinterKlipperExtrasDir) { $PrinterKlipperExtrasDir = $env:PRINTER_KLIPPER_EXTRAS_DIR }

if (-not $PrinterHost) { throw "Set PRINTER_HOST in .env or pass -PrinterHost." }
if (-not $PrinterUser) { $PrinterUser = "biqu" }
if (-not $PrinterConfigDir) { $PrinterConfigDir = "~/printer_data/config" }
if (-not $PrinterKlipperDir) { $PrinterKlipperDir = "~/klipper" }
if (-not $PrinterKlipperExtrasDir) { $PrinterKlipperExtrasDir = "~/klipper/klippy/extras" }

$status = git status --porcelain
if ($status) {
    Write-Host "Refusing to deploy with uncommitted changes:"
    git status --short
    exit 1
}

$target = "$PrinterUser@$PrinterHost"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$localConfig = Join-Path $PSScriptRoot "..\printer_data\config"
$localKlipperExtras = Join-Path $PSScriptRoot "..\klipper_extras"
$sshArgs = @()
if ($PrinterSshKey) {
    $sshArgs += @("-i", $PrinterSshKey)
}

ssh @sshArgs $target "mkdir -p ~/agent-config-backups"
ssh @sshArgs $target "cp -a $PrinterConfigDir ~/agent-config-backups/config-$stamp"
scp @sshArgs -r "$localConfig\*" "$target`:$PrinterConfigDir/"

if (Test-Path -LiteralPath $localKlipperExtras) {
    ssh @sshArgs $target "git -C $PrinterKlipperDir config --local core.excludesFile $PrinterConfigDir/klipper-agent.gitignore"
    $extraBackupDir = "~/agent-config-backups/klipper-extras-$stamp"
    ssh @sshArgs $target "mkdir -p $extraBackupDir"
    ssh @sshArgs $target "mkdir -p $PrinterKlipperExtrasDir"
    Get-ChildItem -LiteralPath $localKlipperExtras -File -Filter *.py | ForEach-Object {
        $remoteExtra = "$PrinterKlipperExtrasDir/$($_.Name)"
        ssh @sshArgs $target "test -f $remoteExtra"
        if ($LASTEXITCODE -eq 0) {
            ssh @sshArgs $target "cp -a $remoteExtra $extraBackupDir/"
        }
        scp @sshArgs $_.FullName "$target`:$PrinterKlipperExtrasDir/"
    }
}

if ($RestartKlipper) {
    ssh @sshArgs $target "sudo systemctl restart klipper"
} else {
    Write-Host "Deployed config. Restart Klipper from Mainsail/Moonraker when ready."
}
