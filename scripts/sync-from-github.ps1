Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

git fetch origin
git pull --ff-only origin main
git status --short --branch
