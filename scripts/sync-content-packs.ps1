# Sync repo content packs into the local Forge run config.
# Usage: powershell -File scripts/sync-content-packs.ps1
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$src = Join-Path $repoRoot "content-packs"
$dst = Join-Path $repoRoot "run\config\cardtable\packs"
if (-not (Test-Path $src)) { throw "content-packs/ not found" }
New-Item -ItemType Directory -Force -Path $dst | Out-Null
Get-ChildItem $src -Directory | ForEach-Object {
    $target = Join-Path $dst $_.Name
    if (Test-Path $target) { Remove-Item -Recurse -Force $target }
    Copy-Item -Recurse $_.FullName $target
    Write-Host "synced $($_.Name) -> $target"
}
Write-Host "Packs now under $dst:"
Get-ChildItem $dst -Directory | ForEach-Object { Write-Host "  - $($_.Name)" }
