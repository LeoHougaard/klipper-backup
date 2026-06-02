Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$status = git status --porcelain
if ($status) {
    Write-Host "Uncommitted changes exist. Commit them before pushing:"
    git status --short
    exit 1
}

git push origin main
git status --short --branch
