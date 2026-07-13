Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$configDir = Join-Path $PSScriptRoot "..\printer_data\config"
$required = @(
    "printer.cfg",
    "toolhead_btt_ebbcan_G0B1_v1.2.cfg",
    "moonraker.conf"
)

foreach ($file in $required) {
    $path = Join-Path $configDir $file
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Missing required config file: $file"
    }
}

$allConfigs = Get-ChildItem -LiteralPath $configDir -File -Include *.cfg,*.conf
$duplicateSections = @()
foreach ($file in $allConfigs) {
    $sections = @{}
    Select-String -LiteralPath $file.FullName -Pattern '^\s*\[([^\]]+)\]\s*$' | ForEach-Object {
        $section = $_.Matches[0].Groups[1].Value.Trim()
        if ($sections.ContainsKey($section)) {
            $duplicateSections += "{0}: duplicate [{1}]" -f $file.Name, $section
        } else {
            $sections[$section] = $true
        }
    }
}

if ($duplicateSections.Count -gt 0) {
    $duplicateSections | ForEach-Object { Write-Warning $_ }
    throw "Duplicate Klipper sections found inside one or more files."
}

Write-Host "Config sanity check passed."

$klipperExtrasDir = Join-Path $PSScriptRoot "..\klipper_extras"
if (Test-Path -LiteralPath $klipperExtrasDir) {
    Get-ChildItem -LiteralPath $klipperExtrasDir -File -Filter *.py | ForEach-Object {
        Get-Content -Raw -LiteralPath $_.FullName |
            python -c "import sys; compile(sys.stdin.read(), sys.argv[1], 'exec')" $_.FullName
        if ($LASTEXITCODE -ne 0) {
            throw "Python syntax check failed: $($_.FullName)"
        }
    }
    $unsafeStatusCalls = Get-ChildItem -LiteralPath $klipperExtrasDir -File -Filter *.py |
        Select-String -Pattern '\.get_status\(None\)'
    if ($unsafeStatusCalls) {
        $unsafeStatusCalls | ForEach-Object { Write-Warning $_ }
        throw "Klipper extras must pass a reactor timestamp to get_status()."
    }
    Write-Host "Klipper extra syntax check passed."
}

$yOffsetTests = Join-Path $PSScriptRoot "..\tests\test_y_axis_z_offset.py"
if (Test-Path -LiteralPath $yOffsetTests) {
    python $yOffsetTests
    if ($LASTEXITCODE -ne 0) {
        throw "Y-axis Z-offset tests failed."
    }
}
