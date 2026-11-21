<#
.SYNOPSIS
    Syncs HushGram17 with official HushGram releases (not every commit).

.DESCRIPTION
    Fetches upstream tags, compares with the latest local tag, and if a new
    release exists, merges it into your custom-features branch.

    Your custom patches (Save Instants, etc.) sit in their own files, so
    merge conflicts are rare. If one happens, the script stops and shows
    which files conflict so you can fix them.

.PARAMETER Check
    Only check if a new release is available, don't merge.

.PARAMETER Tag
    Merge a specific tag instead of the latest.

.EXAMPLE
    .\scripts\sync-upstream.ps1               # merge latest release
    .\scripts\sync-upstream.ps1 -Check        # just check
    .\scripts\sync-upstream.ps1 -Tag v0.0.22  # merge a specific release
#>
param(
    [switch]$Check,
    [string]$Tag
)

$ErrorActionPreference = "Stop"
$root = (Get-Item $PSScriptRoot).Parent.FullName
Set-Location $root

Write-Host "`n=== Syncing with upstream HushGram ===" -ForegroundColor Cyan

# Make sure upstream remote exists
$remotes = git remote 2>&1
if ($remotes -notcontains "upstream") {
    Write-Host "Adding upstream remote..." -ForegroundColor Yellow
    git remote add upstream "https://github.com/SysAdminDoc/HushGram.git"
}

# Fetch upstream tags and branches
Write-Host "Fetching upstream..." -ForegroundColor Yellow
git fetch upstream --tags 2>&1 | ForEach-Object { Write-Host "  $_" -ForegroundColor Gray }

# Find the latest upstream release tag (format: v0.0.XX)
if ($Tag) {
    $latestTag = $Tag
} else {
    $allTags = git ls-remote --tags upstream 2>&1 |
        ForEach-Object { if ($_ -match "refs/tags/(v[\d.]+)$") { $matches[1] } } |
        Sort-Object { $_ -replace 'v','' -split '\.' | ForEach-Object { [int]$_ } }
    $latestTag = $allTags | Select-Object -Last 1
}

if (-not $latestTag) {
    Write-Host " Could not find any upstream release tags." -ForegroundColor Red
    exit 1
}

# Find our current version
$currentVersion = (Get-Content "gradle.properties" | Where-Object { $_ -match "^version\s*=" }) -replace "^version\s*=\s*",""
Write-Host ""
Write-Host "  Your version:     $currentVersion" -ForegroundColor White
Write-Host "  Latest upstream:  $latestTag" -ForegroundColor White

$upstreamVersion = $latestTag -replace "^v",""
if ($currentVersion -eq $upstreamVersion) {
    Write-Host "`n Already up to date!" -ForegroundColor Green
    exit 0
}

Write-Host "`n  New release available: $latestTag" -ForegroundColor Yellow

if ($Check) {
    Write-Host ""
    Write-Host "  Run without -Check to merge it." -ForegroundColor Gray
    exit 0
}

# Make sure we're on custom-features
$branch = git branch --show-current 2>&1
if ($branch -ne "custom-features") {
    Write-Host "`n  Switching to custom-features..." -ForegroundColor Yellow
    git checkout custom-features 2>&1 | ForEach-Object { Write-Host "  $_" -ForegroundColor Gray }
}

# Check for uncommitted changes
$dirty = git status --porcelain 2>&1
if ($dirty) {
    Write-Host "`n Uncommitted changes detected. Commit or stash them first." -ForegroundColor Red
    git status --short
    exit 1
}

# Merge the release tag
Write-Host "`n=== Merging $latestTag into custom-features ===" -ForegroundColor Cyan
$mergeResult = git merge $latestTag --no-edit 2>&1
$mergeExit = $LASTEXITCODE

$mergeResult | ForEach-Object { Write-Host "  $_" -ForegroundColor Gray }

if ($mergeExit -ne 0) {
    Write-Host "`n Merge conflict!" -ForegroundColor Red
    Write-Host "  Fix the conflicts, then run:" -ForegroundColor Yellow
    Write-Host "    git add ." -ForegroundColor White
    Write-Host "    git merge --continue" -ForegroundColor White
    Write-Host ""
    Write-Host "  Conflicting files:" -ForegroundColor Yellow
    git diff --name-only --diff-filter=U 2>&1 | ForEach-Object { Write-Host "    $_" -ForegroundColor Red }
    exit 1
}

# Update version in gradle.properties to match
$newVersion = $latestTag -replace "^v",""
(Get-Content "gradle.properties") -replace "^version\s*=.*","version = $newVersion" | Set-Content "gradle.properties"
Write-Host ""
Write-Host " Merged $latestTag successfully!" -ForegroundColor Green
Write-Host "  Version updated to $newVersion" -ForegroundColor Gray
Write-Host ""
Write-Host "  Next steps:" -ForegroundColor Yellow
Write-Host "    1. Run .\scripts\build-and-push.ps1 to build and deploy" -ForegroundColor White
Write-Host "    2. Test on your phone" -ForegroundColor White
Write-Host "    3. git push origin custom-features" -ForegroundColor White
Write-Host ""
