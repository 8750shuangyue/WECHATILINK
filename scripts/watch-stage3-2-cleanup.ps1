[CmdletBinding()]
param(
    [string]$Server = 'admin@101.37.254.73',
    [string]$IdentityFile = '',
    [string]$OutputRoot = '',
    [string]$WindowStart = '02:50',
    [string]$WindowEnd = '05:00',
    [int]$PollSeconds = 60,
    [switch]$RunNow,
    [switch]$Once
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($IdentityFile)) {
    $IdentityFile = Join-Path $HOME '.ssh\codex-ilink-deploy_ed25519'
}
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $repoRoot '.runtime-reports'
}
if (-not (Test-Path -LiteralPath $IdentityFile)) {
    throw "SSH identity file not found: $IdentityFile"
}
if ($PollSeconds -lt 15) {
    throw 'PollSeconds must be at least 15.'
}

$remoteScriptPath = Join-Path $PSScriptRoot 'remote\verify-stage3-2-governance.py'
$remoteCode = [System.IO.File]::ReadAllText($remoteScriptPath)
$encodedCode = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($remoteCode))
$remoteCommand = "echo $encodedCode | base64 -d | python3 -"

function Get-RemoteState {
    $output = & ssh -i $IdentityFile -o StrictHostKeyChecking=yes $Server $remoteCommand
    if ($LASTEXITCODE -ne 0) {
        throw "Remote governance collection failed with SSH exit code $LASTEXITCODE."
    }
    return (($output -join "`n") | ConvertFrom-Json)
}

function Get-WindowTime {
    param([string]$Value, [string]$Name)
    if ($Value -notmatch '^([01]\d|2[0-3]):([0-5]\d)$') {
        throw "$Name must use HH:mm format: $Value"
    }
    $today = (Get-Date).Date
    return $today.AddHours([int]$matches[1]).AddMinutes([int]$matches[2])
}

function Format-Bytes {
    param([Nullable[long]]$Value)
    if ($null -eq $Value) {
        return 'n/a'
    }
    if ($Value -ge 1073741824) {
        return ('{0:N2} GiB' -f ($Value / 1073741824))
    }
    if ($Value -ge 1048576) {
        return ('{0:N2} MiB' -f ($Value / 1048576))
    }
    if ($Value -ge 1024) {
        return ('{0:N2} KiB' -f ($Value / 1024))
    }
    return "$Value B"
}

function Get-SourceText {
    param($Sources)
    $sourceItems = @($Sources)
    if ($sourceItems.Count -eq 0) {
        return 'none'
    }
    return (($sourceItems | ForEach-Object {
                "$($_.source_id)=$($_.chunks)"
            }) -join ', ')
}

function Get-DeleteMarkerCount {
    param($State)
    if ($null -eq $State.runtime.deletion_markers) {
        return 0
    }
    return @($State.runtime.deletion_markers).Count
}

New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null
$runStamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$jsonlPath = Join-Path $OutputRoot "stage3-2-cleanup-watch-$runStamp.jsonl"
$beforePath = Join-Path $OutputRoot "stage3-2-cleanup-watch-$runStamp-before.json"
$afterPath = Join-Path $OutputRoot "stage3-2-cleanup-watch-$runStamp-after.json"
$summaryPath = Join-Path $OutputRoot "stage3-2-cleanup-watch-$runStamp.md"

$start = Get-WindowTime -Value $WindowStart -Name 'WindowStart'
$end = Get-WindowTime -Value $WindowEnd -Name 'WindowEnd'
if ($RunNow -or $Once) {
    $scanStart = Get-Date
    $scanEnd = if ($Once) { $scanStart } else { (Get-Date) + ($end - $start) }
} else {
    $now = Get-Date
    if ($now -lt $start) {
        Write-Host "Waiting for cleanup observation window at $WindowStart..."
        while ((Get-Date) -lt $start) {
            $remaining = [int][Math]::Min(300, ($start - (Get-Date)).TotalSeconds)
            if ($remaining -gt 0) {
                Start-Sleep -Seconds $remaining
            }
        }
    } elseif ($now -gt $end) {
        throw "Current time is after today's observation window ($WindowStart-$WindowEnd). Use -RunNow or -Once for an explicit manual collection."
    }
    $scanStart = Get-Date
    $scanEnd = $end
}

$snapshots = [System.Collections.Generic.List[object]]::new()
$sequence = 0
$scriptFailure = $null

try {
    while ($true) {
        $sequence++
        $collectedAt = Get-Date
        try {
            $state = Get-RemoteState
            $envelope = [ordered]@{
                sequence = $sequence
                collected_at = $collectedAt.ToString('o')
                collection_error = $null
                state = $state
            }
        } catch {
            $envelope = [ordered]@{
                sequence = $sequence
                collected_at = $collectedAt.ToString('o')
                collection_error = $_.Exception.Message
                state = $null
            }
        }

        $snapshots.Add([pscustomobject]$envelope)
        ($envelope | ConvertTo-Json -Depth 40 -Compress) |
            Add-Content -LiteralPath $jsonlPath -Encoding UTF8

        if ($null -ne $envelope.state) {
            Write-Host ("[{0}] service={1}; http={2}/{3}/{4}; conversation={5}; duplicates={6}; public={7}" -f `
                    $collectedAt.ToString('HH:mm:ss'),
                $envelope.state.runtime.service,
                $envelope.state.runtime.http_codes.local,
                $envelope.state.runtime.http_codes.main,
                $envelope.state.runtime.http_codes.www,
                $envelope.state.sqlite.conversation_count,
                $envelope.state.sqlite.duplicate_rows,
                $envelope.state.sqlite.public_vectors)
        } else {
            Write-Warning ("[{0}] collection failed: {1}" -f `
                    $collectedAt.ToString('HH:mm:ss'), $envelope.collection_error)
        }

        if ($Once) {
            break
        }
        $remaining = [int][Math]::Floor(($scanEnd - (Get-Date)).TotalSeconds)
        if ($remaining -le 0) {
            break
        }
        Start-Sleep -Seconds ([Math]::Min($PollSeconds, $remaining))
    }
} catch {
    $scriptFailure = $_
    throw
} finally {
    try {
        if ($snapshots.Count -gt 0) {
        $validSnapshots = @($snapshots | Where-Object { $null -ne $_.state })
        if ($validSnapshots.Count -eq 0) {
            throw 'No valid governance snapshots were collected.'
        }

        $before = $validSnapshots[0].state
        $after = $validSnapshots[-1].state
        $before | ConvertTo-Json -Depth 40 | Set-Content -LiteralPath $beforePath -Encoding UTF8
        $after | ConvertTo-Json -Depth 40 | Set-Content -LiteralPath $afterPath -Encoding UTF8

        $healthFailures = [System.Collections.Generic.List[string]]::new()
        $warnings = [System.Collections.Generic.List[string]]::new()
        if ($after.runtime.service -ne 'active') {
            $healthFailures.Add("service=$($after.runtime.service)")
        }
        foreach ($name in @('local', 'main', 'www')) {
            if ($after.runtime.http_codes.$name -ne '200') {
                $healthFailures.Add("http_$name=$($after.runtime.http_codes.$name)")
            }
        }
        if ($after.sqlite.integrity -ne 'ok') {
            $healthFailures.Add("sqlite_integrity=$($after.sqlite.integrity)")
        }
        if (-not [bool]$after.runtime.listen_8080) {
            $healthFailures.Add('listen_8080=false')
        }
        $failureMarkerCount = @($after.runtime.failure_markers).Count
        if ($failureMarkerCount -gt 0) {
            $healthFailures.Add("failure_markers=$failureMarkerCount")
        }
        $malformedVectorCount = @($after.sqlite.malformed_row_ids).Count
        if ($malformedVectorCount -gt 0) {
            $healthFailures.Add("malformed_vectors=$malformedVectorCount")
        }
        if ($snapshots.Count -lt 2) {
            $warnings.Add('Only one valid snapshot was collected; before/after comparison is not available.')
        }
        if ((Get-DeleteMarkerCount -State $after) -eq 0) {
            $warnings.Add('No scheduled cleanup marker was observed in the current journal window.')
        }
        if ([int]$after.sqlite.duplicate_rows -gt 0) {
            $warnings.Add("Duplicate conversation rows remain: $($after.sqlite.duplicate_rows).")
        }

        $duplicateRemoved = [int]$before.sqlite.duplicate_rows - [int]$after.sqlite.duplicate_rows
        $conversationRemoved = [int]$before.sqlite.conversation_count - [int]$after.sqlite.conversation_count
        $status = if ($healthFailures.Count -gt 0) { 'CRITICAL' } elseif ($warnings.Count -gt 0) { 'WARNING' } else { 'OK' }

        $cleanupMarkers = @()
        if ($null -ne $after.runtime.deletion_markers) {
            $cleanupMarkers = @($after.runtime.deletion_markers)
        }
        $failureMarkers = @()
        if ($null -ne $after.runtime.failure_markers) {
            $failureMarkers = @($after.runtime.failure_markers)
        }

        $markdown = [System.Collections.Generic.List[string]]::new()
        $markdown.Add('# 阶段 3.2 首次治理自动观测摘要')
        $markdown.Add('')
        $markdown.Add("- 观测窗口：``$WindowStart`` - ``$WindowEnd``")
        $markdown.Add("- 采集次数：``$($snapshots.Count)``")
        $markdown.Add("- 生成时间：``$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')``")
        $markdown.Add("- 结论：``$status``")
        $markdown.Add('')
        $markdown.Add('## 前后对比')
        $markdown.Add('')
        $markdown.Add('| 项目 | 清理前 | 清理后 | 变化 |')
        $markdown.Add('| --- | ---: | ---: | ---: |')
        $markdown.Add("| 对话向量 | $($before.sqlite.conversation_count) | $($after.sqlite.conversation_count) | $conversationRemoved |")
        $markdown.Add("| 重复组 | $($before.sqlite.duplicate_groups) | $($after.sqlite.duplicate_groups) | $([int]$before.sqlite.duplicate_groups - [int]$after.sqlite.duplicate_groups) |")
        $markdown.Add("| 重复行 | $($before.sqlite.duplicate_rows) | $($after.sqlite.duplicate_rows) | $duplicateRemoved |")
        $markdown.Add("| 公共向量 | $($before.sqlite.public_vectors) | $($after.sqlite.public_vectors) | $([int]$before.sqlite.public_vectors - [int]$after.sqlite.public_vectors) |")
        $markdown.Add("| 过期对话向量 | $($before.sqlite.expired_conversation_90d) | $($after.sqlite.expired_conversation_90d) | $([int]$before.sqlite.expired_conversation_90d - [int]$after.sqlite.expired_conversation_90d) |")
        $markdown.Add("| 过期检索日志 | $($before.sqlite.expired_retrieval_logs_180d) | $($after.sqlite.expired_retrieval_logs_180d) | $([int]$before.sqlite.expired_retrieval_logs_180d - [int]$after.sqlite.expired_retrieval_logs_180d) |")
        $markdown.Add("| 过期访问日志 | $($before.sqlite.expired_access_logs_180d) | $($after.sqlite.expired_access_logs_180d) | $([int]$before.sqlite.expired_access_logs_180d - [int]$after.sqlite.expired_access_logs_180d) |")
        $markdown.Add("| 公共来源 | $(Get-SourceText -Sources $before.sqlite.public_sources) | $(Get-SourceText -Sources $after.sqlite.public_sources) | - |")
        $markdown.Add("| 磁盘可用 | $(Format-Bytes -Value $before.disk.application.free_bytes) | $(Format-Bytes -Value $after.disk.application.free_bytes) | - |")
        $markdown.Add('')
        $markdown.Add('## 运行状态')
        $markdown.Add('')
        $markdown.Add("| 项目 | 清理前 | 清理后 |")
        $markdown.Add('| --- | --- | --- |')
        $markdown.Add("| 服务 | $($before.runtime.service) | $($after.runtime.service) |")
        $markdown.Add("| 监听 8080 | $($before.runtime.listen_8080) | $($after.runtime.listen_8080) |")
        $markdown.Add("| SQLite 完整性 | $($before.sqlite.integrity) | $($after.sqlite.integrity) |")
        $markdown.Add("| 本机 HTTP | $($before.runtime.http_codes.local) | $($after.runtime.http_codes.local) |")
        $markdown.Add("| 主域名 HTTP | $($before.runtime.http_codes.main) | $($after.runtime.http_codes.main) |")
        $markdown.Add("| www HTTP | $($before.runtime.http_codes.www) | $($after.runtime.http_codes.www) |")
        $markdown.Add('')
        $markdown.Add('## 治理日志')
        $markdown.Add('')
        if ($cleanupMarkers.Count -eq 0) {
            $markdown.Add('- 未观察到计划内清理日志。')
        } else {
            foreach ($line in $cleanupMarkers) {
                $markdown.Add("- ``$line``")
            }
        }
        $markdown.Add('')
        $markdown.Add('## 异常与告警')
        $markdown.Add('')
        if ($healthFailures.Count -eq 0 -and $warnings.Count -eq 0) {
            $markdown.Add('- 无。')
        } else {
            foreach ($item in $healthFailures) {
                $markdown.Add("- CRITICAL: $item")
            }
            foreach ($item in $warnings) {
                $markdown.Add("- WARNING: $item")
            }
        }
        if ($failureMarkers.Count -gt 0) {
            $markdown.Add('')
            $markdown.Add('失败日志：')
            foreach ($line in $failureMarkers) {
                $markdown.Add("- ``$line``")
            }
        }
        $markdown.Add('')
        $markdown.Add('## 文件')
        $markdown.Add('')
        $markdown.Add("- JSONL：``$jsonlPath``")
        $markdown.Add("- 清理前快照：``$beforePath``")
        $markdown.Add("- 清理后快照：``$afterPath``")

        $markdown | Set-Content -LiteralPath $summaryPath -Encoding UTF8
        Write-Host "JSONL: $jsonlPath"
        Write-Host "Summary: $summaryPath"
        Write-Host "Watch status: $status"

        if ($healthFailures.Count -gt 0) {
            Write-Host "Cleanup watch detected $($healthFailures.Count) critical issue(s)." -ForegroundColor Red
            exit 1
        }
            if ($warnings.Count -gt 0) {
                Write-Host "Cleanup watch completed with $($warnings.Count) warning(s)." -ForegroundColor Yellow
            } else {
                Write-Host 'Cleanup watch completed successfully.' -ForegroundColor Green
            }
        }
    } catch {
        if ($null -ne $scriptFailure) {
            Write-Warning "Cleanup observation failed before report generation: $($scriptFailure.Exception.Message)"
        } else {
            throw
        }
    }
}
