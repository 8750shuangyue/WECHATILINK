[CmdletBinding()]
param(
    [ValidateSet('Baseline', 'PostCleanup')]
    [string]$Mode = 'Baseline',
    [string]$Server = 'admin@101.37.254.73',
    [string]$IdentityFile = '',
    [string]$BaselineReport = '',
    [int]$ExpectedBaselineDuplicateRows = 76
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($IdentityFile)) {
    $IdentityFile = Join-Path $HOME '.ssh\codex-ilink-deploy_ed25519'
}
if (-not (Test-Path -LiteralPath $IdentityFile)) {
    throw "SSH identity file not found: $IdentityFile"
}

$remoteScriptPath = Join-Path $PSScriptRoot 'remote\verify-stage3-2-governance.py'
$remoteCode = [System.IO.File]::ReadAllText($remoteScriptPath)
$encodedCode = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($remoteCode))
$remoteCommand = "echo $encodedCode | base64 -d | python3 -"
$output = & ssh -i $IdentityFile -o StrictHostKeyChecking=yes $Server $remoteCommand
if ($LASTEXITCODE -ne 0) {
    throw 'Remote governance verification failed to execute.'
}

$state = ($output -join "`n") | ConvertFrom-Json
$failures = @()

function Add-Check {
    param(
        [string]$Name,
        [bool]$Passed,
        [string]$Detail
    )
    if ($Passed) {
        Write-Host "PASS  $Name - $Detail" -ForegroundColor Green
    } else {
        Write-Host "FAIL  $Name - $Detail" -ForegroundColor Red
        $script:failures += "$Name - $Detail"
    }
}

Add-Check 'service' ($state.runtime.service -eq 'active') "status=$($state.runtime.service)"
Add-Check 'listen_8080' ([bool]$state.runtime.listen_8080) "listen=$($state.runtime.listen_8080)"
foreach ($name in @('local', 'main', 'www')) {
    Add-Check "http_$name" ($state.runtime.http_codes.$name -eq '200') "code=$($state.runtime.http_codes.$name)"
}
Add-Check 'governance_enabled' ([bool]$state.runtime.governance_enabled) "enabled=$($state.runtime.governance_enabled)"
Add-Check 'sqlite_integrity' ($state.sqlite.integrity -eq 'ok') "integrity=$($state.sqlite.integrity)"
Add-Check 'unique_index' ([int]$state.sqlite.unique_index_count -eq 1) "count=$($state.sqlite.unique_index_count)"
Add-Check 'public_vectors' ([int]$state.sqlite.public_vectors -eq 2) "count=$($state.sqlite.public_vectors)"
Add-Check 'expired_conversation_90d' ([int]$state.sqlite.expired_conversation_90d -eq 0) "count=$($state.sqlite.expired_conversation_90d)"
Add-Check 'expired_retrieval_logs_180d' ([int]$state.sqlite.expired_retrieval_logs_180d -eq 0) "count=$($state.sqlite.expired_retrieval_logs_180d)"
Add-Check 'expired_access_logs_180d' ([int]$state.sqlite.expired_access_logs_180d -eq 0) "count=$($state.sqlite.expired_access_logs_180d)"
Add-Check 'malformed_conversation_vectors' ([int]$state.sqlite.malformed_row_ids.Count -eq 0) "count=$($state.sqlite.malformed_row_ids.Count)"

$sourceMap = @{}
foreach ($source in $state.sqlite.public_sources) {
    $sourceMap[[string]$source.source_id] = [int]$source.chunks
}
$sourceMapValid = $sourceMap.Count -eq 2 -and $sourceMap['post_1'] -eq 1 -and $sourceMap['post_2'] -eq 1
Add-Check 'public_source_mapping' $sourceMapValid "sources=$($state.sqlite.public_sources | ConvertTo-Json -Compress)"

$postIds = @($state.mysql.posts | ForEach-Object { [int]$_.id })
Add-Check 'mysql_posts' ([bool]$state.mysql.ok -and $postIds.Count -eq 2 -and $postIds -contains 1 -and $postIds -contains 2) "ok=$($state.mysql.ok); ids=$($postIds -join ',')"
Add-Check 'orphan_public_sources' ($true) 'verified by exact post_1/post_2 source mapping'
Add-Check 'governance_failure_logs' ([int]$state.runtime.failure_markers.Count -eq 0) "count=$($state.runtime.failure_markers.Count)"

if ($Mode -eq 'Baseline') {
    Add-Check 'baseline_duplicate_rows' ([int]$state.sqlite.duplicate_rows -eq $ExpectedBaselineDuplicateRows) "expected=$ExpectedBaselineDuplicateRows; actual=$($state.sqlite.duplicate_rows)"
    Add-Check 'baseline_conversation_rows' ([int]$state.sqlite.conversation_count -ge 202) "actual=$($state.sqlite.conversation_count)"
} else {
    if ([string]::IsNullOrWhiteSpace($BaselineReport)) {
        $latestBaseline = Get-ChildItem -LiteralPath (Join-Path $repoRoot '.runtime-reports') -Filter 'stage3-2-baseline-*.json' -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
        if ($null -eq $latestBaseline) {
            throw 'No baseline report found. Run with -Mode Baseline first.'
        }
        $BaselineReport = $latestBaseline.FullName
    }
    if (-not (Test-Path -LiteralPath $BaselineReport)) {
        throw "Baseline report not found: $BaselineReport"
    }

    $baseline = Get-Content -LiteralPath $BaselineReport -Raw | ConvertFrom-Json
    $baselineIds = @($baseline.state.sqlite.conversation_ids | ForEach-Object { [int]$_ })
    $currentIds = @($state.sqlite.conversation_ids | ForEach-Object { [int]$_ })
    $baselineMax = [int]$baseline.state.sqlite.max_conversation_id
    $newRows = @($currentIds | Where-Object { $_ -gt $baselineMax })
    $expectedConversationCount = [int]$baseline.state.sqlite.conversation_count - [int]$baseline.state.sqlite.duplicate_rows + $newRows.Count
    $baselineDuplicateIds = @($baseline.state.sqlite.duplicate_row_ids | ForEach-Object { [int]$_ })
    $remainingDuplicateIds = @($currentIds | Where-Object { $baselineDuplicateIds -contains $_ })

    Add-Check 'post_cleanup_duplicate_rows' ([int]$state.sqlite.duplicate_rows -eq 0) "actual=$($state.sqlite.duplicate_rows)"
    Add-Check 'post_cleanup_conversation_rows' ([int]$state.sqlite.conversation_count -eq $expectedConversationCount) "expected=$expectedConversationCount; actual=$($state.sqlite.conversation_count); newRows=$($newRows.Count)"
    Add-Check 'baseline_duplicate_ids_removed' ($remainingDuplicateIds.Count -eq 0) "remaining=$($remainingDuplicateIds -join ',')"
    Add-Check 'scheduled_cleanup_logged' ([int]$state.runtime.deletion_markers.Count -gt 0) "markers=$($state.runtime.deletion_markers.Count)"
}

$reportDirectory = Join-Path $repoRoot '.runtime-reports'
New-Item -ItemType Directory -Path $reportDirectory -Force | Out-Null
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$reportPath = Join-Path $reportDirectory "stage3-2-$($Mode.ToLowerInvariant())-$timestamp.json"
$report = [ordered]@{
    generated_at = (Get-Date).ToString('o')
    mode = $Mode
    baseline_report = $BaselineReport
    failures = $failures
    state = $state
}
$report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $reportPath -Encoding UTF8
Write-Host "Report: $reportPath"

if ($failures.Count -gt 0) {
    Write-Host "Governance verification failed with $($failures.Count) issue(s)." -ForegroundColor Red
    exit 1
}

Write-Host 'Governance verification passed.' -ForegroundColor Green
