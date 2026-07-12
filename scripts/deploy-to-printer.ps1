param(
    [string]$PrinterHost = $env:PRINTER_HOST,
    [string]$PrinterUser = $env:PRINTER_USER,
    [string]$PrinterConfigDir = $env:PRINTER_CONFIG_DIR,
    [string]$PrinterSshKey = $env:PRINTER_SSH_KEY,
    [string]$PrinterKlipperDir = $env:PRINTER_KLIPPER_DIR,
    [string]$PrinterKlipperExtrasDir = $env:PRINTER_KLIPPER_EXTRAS_DIR,
    [int]$MoonrakerPort = $env:MOONRAKER_PORT,
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
if (-not $MoonrakerPort) { $MoonrakerPort = [int]$env:MOONRAKER_PORT }

if (-not $PrinterHost) { throw "Set PRINTER_HOST in .env or pass -PrinterHost." }
if (-not $PrinterUser) { $PrinterUser = "biqu" }
if (-not $PrinterConfigDir) { $PrinterConfigDir = "~/printer_data/config" }
if (-not $PrinterKlipperDir) { $PrinterKlipperDir = "~/klipper" }
if (-not $PrinterKlipperExtrasDir) { $PrinterKlipperExtrasDir = "~/klipper/klippy/extras" }
if (-not $MoonrakerPort) { $MoonrakerPort = 7125 }

function Assert-NativeSuccess([string]$Operation) {
    if ($LASTEXITCODE -ne 0) {
        throw "$Operation failed with exit code $LASTEXITCODE."
    }
}

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
Assert-NativeSuccess "Creating the remote backup directory"
ssh @sshArgs $target "cp -a $PrinterConfigDir ~/agent-config-backups/config-$stamp"
Assert-NativeSuccess "Backing up the remote printer config"
scp @sshArgs -r "$localConfig\*" "$target`:$PrinterConfigDir/"
Assert-NativeSuccess "Deploying the printer config"

if (Test-Path -LiteralPath $localKlipperExtras) {
    ssh @sshArgs $target "git -C $PrinterKlipperDir config --local core.excludesFile $PrinterConfigDir/klipper-agent.gitignore"
    Assert-NativeSuccess "Configuring the Klipper Git exclusion"
    $extraBackupDir = "~/agent-config-backups/klipper-extras-$stamp"
    ssh @sshArgs $target "mkdir -p $extraBackupDir"
    Assert-NativeSuccess "Creating the Klipper-extra backup directory"
    ssh @sshArgs $target "mkdir -p $PrinterKlipperExtrasDir"
    Assert-NativeSuccess "Creating the Klipper extras directory"
    Get-ChildItem -LiteralPath $localKlipperExtras -File -Filter *.py | ForEach-Object {
        $remoteExtra = "$PrinterKlipperExtrasDir/$($_.Name)"
        ssh @sshArgs $target "test -f $remoteExtra"
        $remoteExtraExists = $LASTEXITCODE -eq 0
        if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne 1) {
            throw "Checking for remote Klipper extra $remoteExtra failed with exit code $LASTEXITCODE."
        }
        if ($remoteExtraExists) {
            ssh @sshArgs $target "cp -a $remoteExtra $extraBackupDir/"
            Assert-NativeSuccess "Backing up Klipper extra $($_.Name)"
        }
        scp @sshArgs $_.FullName "$target`:$PrinterKlipperExtrasDir/"
        Assert-NativeSuccess "Deploying Klipper extra $($_.Name)"
    }
}

if ($RestartKlipper) {
    if (Test-Path -LiteralPath $localKlipperExtras) {
        # A config restart does not reload imported Python modules. Restart the
        # Klipper service whenever repository-managed extras are deployed.
        $restartUri = "http://${PrinterHost}:$MoonrakerPort/machine/services/restart?service=klipper"
    } else {
        $restartUri = "http://${PrinterHost}:$MoonrakerPort/printer/restart"
    }
    Invoke-RestMethod -Method Post -Uri $restartUri -TimeoutSec 15 | Out-Null
    Write-Host "Deployed config and requested the appropriate Klipper restart through Moonraker."
} else {
    Write-Host "Deployed config. Restart Klipper from Mainsail/Moonraker when ready."
}
