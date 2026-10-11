[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$UserName = '',
    [string]$Password = '',
    [string]$KnowledgeDir = '',
    [switch]$DryRunOnly,
    [switch]$SkipSeedCleanup
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($KnowledgeDir)) {
    $KnowledgeDir = Join-Path $repoRoot 'knowledge'
}

if (-not (Test-Path -LiteralPath $KnowledgeDir -PathType Container)) {
    throw "Knowledge directory not found: $KnowledgeDir"
}

if ([string]::IsNullOrWhiteSpace($UserName) -or [string]::IsNullOrWhiteSpace($Password)) {
    throw 'UserName and Password are required. Example: .\import-knowledge-stage2.3.ps1 -UserName admin -Password "your-password"'
}

$session = $null
$loginBody = @{ userName = $UserName; password = $Password } | ConvertTo-Json -Compress

Write-Host "[1/5] Logging in to $BaseUrl ..." -ForegroundColor Cyan
try {
    $loginResp = Invoke-RestMethod -Uri "$BaseUrl/api/auth/login" -Method Post `
        -Body $loginBody -ContentType 'application/json' -SessionVariable session -ErrorAction Stop
} catch {
    throw "Login failed: $($_.Exception.Message)"
}
Write-Host "      Login OK." -ForegroundColor Green

$kbFiles = Get-ChildItem -LiteralPath $KnowledgeDir -Filter '*.md' | Sort-Object Name
if ($kbFiles.Count -eq 0) {
    throw "No .md files found in $KnowledgeDir"
}
Write-Host "[2/5] Found $($kbFiles.Count) knowledge documents." -ForegroundColor Cyan

$dryRunResults = @()
$importResults = @()

Write-Host "[3/5] DryRun validation ..." -ForegroundColor Cyan
foreach ($file in $kbFiles) {
    $sourceId = $file.BaseName
    Write-Host "  dryRun: $sourceId ..." -NoNewline

    $form = @{
        file   = Get-Item -LiteralPath $file.FullName
        dryRun = 'true'
    }
    try {
        $resp = Invoke-RestMethod -Uri "$BaseUrl/api/kb/upload" -Method Post `
            -Form $form -WebSession $session -ErrorAction Stop
    } catch {
        Write-Host " FAILED" -ForegroundColor Red
        $dryRunResults += [pscustomobject]@{ sourceId = $sourceId; status = 'error'; error = $_.Exception.Message }
        continue
    }

    if ($resp.success) {
        $chunkCount = $resp.count
        $charCount = $resp.chars
        $status = if ($chunkCount -ge 1 -and $chunkCount -le 5) { 'ok' } else { 'warn' }
        $color = if ($status -eq 'ok') { 'Green' } else { 'Yellow' }
        Write-Host " OK (chunks=$chunkCount, chars=$charCount)" -ForegroundColor $color
        $dryRunResults += [pscustomobject]@{ sourceId = $sourceId; status = $status; chunks = $chunkCount; chars = $charCount }
    } else {
        Write-Host " FAILED: $($resp.error)" -ForegroundColor Red
        $dryRunResults += [pscustomobject]@{ sourceId = $sourceId; status = 'error'; error = $resp.error }
    }
}

$failed = $dryRunResults | Where-Object { $_.status -eq 'error' }
if ($failed) {
    Write-Host "`nDryRun failed for $($failed.Count) document(s). Aborting before formal import." -ForegroundColor Red
    $failed | Format-Table sourceId, error
    exit 1
}

$warned = $dryRunResults | Where-Object { $_.status -eq 'warn' }
if ($warned) {
    Write-Host "`nWarning: $($warned.Count) document(s) have unusual chunk counts:" -ForegroundColor Yellow
    $warned | Format-Table sourceId, chunks, chars
}

if ($DryRunOnly) {
    Write-Host "`nDryRunOnly mode: skipping formal import and seed cleanup." -ForegroundColor Yellow
    Write-Host "`n=== DryRun Summary ===" -ForegroundColor Cyan
    $dryRunResults | Format-Table sourceId, status, chunks, chars
    exit 0
}

Write-Host "`n[4/5] Formal import ..." -ForegroundColor Cyan
foreach ($file in $kbFiles) {
    $sourceId = $file.BaseName
    Write-Host "  import: $sourceId ..." -NoNewline

    $form = @{
        file     = Get-Item -LiteralPath $file.FullName
        sourceId = $sourceId
    }
    try {
        $resp = Invoke-RestMethod -Uri "$BaseUrl/api/kb/upload" -Method Post `
            -Form $form -WebSession $session -ErrorAction Stop
    } catch {
        Write-Host " FAILED" -ForegroundColor Red
        $importResults += [pscustomobject]@{ sourceId = $sourceId; status = 'error'; error = $_.Exception.Message }
        continue
    }

    if ($resp.success) {
        Write-Host " OK (chunks=$($resp.count), replaced=$($resp.replacedPreviousCount))" -ForegroundColor Green
        $importResults += [pscustomobject]@{ sourceId = $sourceId; status = 'ok'; chunks = $resp.count; replaced = $resp.replacedPreviousCount }
    } else {
        Write-Host " FAILED: $($resp.error)" -ForegroundColor Red
        $importResults += [pscustomobject]@{ sourceId = $sourceId; status = 'error'; error = $resp.error }
    }
}

$importFailed = $importResults | Where-Object { $_.status -eq 'error' }
if ($importFailed) {
    Write-Host "`nWARNING: $($importFailed.Count) document(s) failed formal import:" -ForegroundColor Red
    $importFailed | Format-Table sourceId, error
}

if (-not $SkipSeedCleanup) {
    Write-Host "`n[5/5] Cleaning up test seeds (post_1, post_2) ..." -ForegroundColor Cyan
    foreach ($seed in @('post_1', 'post_2')) {
        Write-Host "  delete: $seed ..." -NoNewline
        try {
            $delResp = Invoke-RestMethod -Uri "$BaseUrl/api/kb/$seed" -Method Delete `
                -WebSession $session -ErrorAction Stop
            Write-Host " OK" -ForegroundColor Green
        } catch {
            $statusCode = $_.Exception.Response.StatusCode.value__
            if ($statusCode -eq 404) {
                Write-Host " NOT FOUND (already absent)" -ForegroundColor Yellow
            } else {
                Write-Host " FAILED ($statusCode)" -ForegroundColor Red
            }
        }
    }
} else {
    Write-Host "`n[5/5] Skipping seed cleanup (-SkipSeedCleanup)." -ForegroundColor Yellow
}

Write-Host "`n=== Verifying knowledge list ===" -ForegroundColor Cyan
try {
    $listResp = Invoke-RestMethod -Uri "$BaseUrl/api/kb/list" -Method Get `
        -WebSession $session -ErrorAction Stop
    if ($listResp.success -and $listResp.data) {
        Write-Host "Public knowledge documents:" -ForegroundColor White
        $listResp.data | Format-Table sourceId, count, timestamp
        $totalChunks = ($listResp.data | Measure-Object -Property count -Sum).Sum
        Write-Host "Total public vectors: $totalChunks" -ForegroundColor Cyan

        $remainingSeeds = $listResp.data | Where-Object { $_.sourceId -match '^post_\d+$' }
        if ($remainingSeeds) {
            Write-Host "WARNING: Test seeds still present:" -ForegroundColor Red
            $remainingSeeds | Format-Table sourceId, count
        } else {
            Write-Host "No test seeds (post_N) remaining." -ForegroundColor Green
        }
    }
} catch {
    Write-Host "Failed to list knowledge: $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host "`n=== Import Summary ===" -ForegroundColor Cyan
$importResults | Format-Table sourceId, status, chunks, replaced
$okCount = ($importResults | Where-Object { $_.status -eq 'ok' }).Count
Write-Host "Imported: $okCount / $($kbFiles.Count) documents" -ForegroundColor $(if ($okCount -eq $kbFiles.Count) { 'Green' } else { 'Yellow' })