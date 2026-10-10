[CmdletBinding()]
param(
    [string]$Server = 'admin@101.37.254.73',
    [string]$IdentityFile = '',
    [string]$RemoteBackupRoot = '/opt/ilink/backups',
    [string]$RollbackBackupName = '20261010-141347-stage3-2',
    [int]$DailyRetentionDays = 14,
    [int]$WeeklyRetentionWeeks = 8,
    [int]$MonthlyRetentionMonths = 6,
    [int]$RollbackRetentionDays = 90,
    [int]$WarningAfterHours = 26,
    [int]$CriticalAfterHours = 50,
    [string]$OutputRoot = ''
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
if ($WarningAfterHours -lt 1 -or $CriticalAfterHours -le $WarningAfterHours) {
    throw 'Freshness thresholds must satisfy 1 <= WarningAfterHours < CriticalAfterHours.'
}
if ($DailyRetentionDays -lt 1 -or $WeeklyRetentionWeeks -lt 1 -or
    $MonthlyRetentionMonths -lt 1 -or $RollbackRetentionDays -lt 1) {
    throw 'Retention values must be positive.'
}

function Get-PropertyValue {
    param(
        [object]$InputObject,
        [string]$Name,
        $Default = $null
    )
    if ($null -eq $InputObject) {
        return $Default
    }
    $property = $InputObject.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return $Default
    }
    return $property.Value
}

$remoteCode = @'
import hashlib
import json
import os
import re
import sqlite3
from datetime import datetime, timezone

root = os.environ["ILINK_BACKUP_ROOT"]
required_files = [
    "demo-0.0.1-SNAPSHOT.jar",
    "ilink_chat.sql",
    "ilink_chat.sql.sha256",
    "jar.sha256",
    "production.conf",
    "production.conf.sha256",
    "rag_knowledge.sqlite",
    "rag_knowledge.sqlite.sha256",
]


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def created_iso(path):
    name = os.path.basename(path)
    try:
        return datetime.strptime(name[:15], "%Y%m%d-%H%M%S").astimezone().isoformat()
    except ValueError:
        return datetime.fromtimestamp(os.stat(path).st_mtime, timezone.utc).isoformat()


def collect_backup(path):
    name = os.path.basename(path)
    names = set(os.listdir(path))
    missing = [item for item in required_files if item not in names]
    hash_checks = []
    hash_failures = []
    for hash_name in sorted(item for item in names if item.endswith(".sha256")):
        hash_path = os.path.join(path, hash_name)
        try:
            with open(hash_path, encoding="utf-8") as source:
                line = next((value.strip() for value in source if value.strip()), "")
            match = re.match(r"^([0-9a-fA-F]{64})\s+\*?(.+)$", line)
            if not match:
                hash_failures.append({"hash_file": hash_name, "reason": "invalid format"})
                continue
            expected = match.group(1).lower()
            target_name = os.path.basename(match.group(2).strip())
            target_path = os.path.join(path, target_name)
            if not os.path.isfile(target_path):
                hash_failures.append(
                    {"hash_file": hash_name, "target": target_name, "reason": "target missing"}
                )
                continue
            actual = sha256(target_path)
            passed = actual == expected
            hash_checks.append(
                {"hash_file": hash_name, "target": target_name, "passed": passed}
            )
            if not passed:
                hash_failures.append(
                    {"hash_file": hash_name, "target": target_name, "reason": "hash mismatch"}
                )
        except Exception as exc:
            hash_failures.append(
                {"hash_file": hash_name, "reason": str(exc)}
            )

    sql_path = os.path.join(path, "ilink_chat.sql")
    mysql_complete = False
    if os.path.isfile(sql_path):
        try:
            with open(sql_path, "rb") as source:
                mysql_complete = b"Dump completed" in source.read()[-4096:]
        except Exception:
            mysql_complete = False

    sqlite_integrity = None
    sqlite_path = os.path.join(path, "rag_knowledge.sqlite")
    if os.path.isfile(sqlite_path):
        connection = None
        try:
            connection = sqlite3.connect(f"file:{sqlite_path}?mode=ro", uri=True)
            sqlite_integrity = connection.execute("PRAGMA integrity_check").fetchone()[0]
        except Exception as exc:
            sqlite_integrity = f"error: {exc}"
        finally:
            if connection is not None:
                connection.close()

    complete = not missing
    checks_pass = (
        complete
        and not hash_failures
        and mysql_complete
        and sqlite_integrity == "ok"
    )
    return {
        "name": name,
        "created_at": created_iso(path),
        "size_bytes": sum(
            os.path.getsize(os.path.join(path, item))
            for item in names
            if os.path.isfile(os.path.join(path, item))
        ),
        "missing_files": missing,
        "hash_checks": hash_checks,
        "hash_failures": hash_failures,
        "mysql_dump_complete": mysql_complete,
        "sqlite_integrity": sqlite_integrity,
        "complete": complete,
        "checks_pass": checks_pass,
    }


if not os.path.isdir(root):
    print(json.dumps({"root": root, "root_exists": False, "backups": []}))
else:
    backups = [
        collect_backup(os.path.join(root, name))
        for name in sorted(os.listdir(root))
        if os.path.isdir(os.path.join(root, name))
    ]
    print(
        json.dumps(
            {
                "root": root,
                "root_exists": True,
                "collected_at": datetime.now(timezone.utc).isoformat(),
                "backups": backups,
            },
            ensure_ascii=False,
            separators=(",", ":"),
        )
    )
'@

$encodedCode = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($remoteCode))
$remoteCommand = "ILINK_BACKUP_ROOT='$RemoteBackupRoot' bash -lc 'echo $encodedCode | base64 -d | python3 -'"
$output = & ssh -i $IdentityFile -o StrictHostKeyChecking=yes $Server $remoteCommand
if ($LASTEXITCODE -ne 0) {
    throw 'Remote backup retention inventory failed to execute.'
}

$state = ($output -join "`n") | ConvertFrom-Json
$critical = [System.Collections.Generic.List[string]]::new()
$warnings = [System.Collections.Generic.List[string]]::new()
if (-not [bool](Get-PropertyValue -InputObject $state -Name 'root_exists' -Default $false)) {
    $critical.Add("Backup root is missing: $RemoteBackupRoot")
}

$collectedAt = [DateTimeOffset]::Parse(
    [string](Get-PropertyValue -InputObject $state -Name 'collected_at' -Default ([DateTimeOffset]::UtcNow.ToString('o')))
)
$items = [System.Collections.Generic.List[object]]::new()
foreach ($backup in @(Get-PropertyValue -InputObject $state -Name 'backups' -Default @())) {
    $createdAt = [DateTimeOffset]::Parse([string]$backup.created_at)
    $ageHours = [Math]::Round(($collectedAt - $createdAt).TotalHours, 2)
    $hashChecks = @(Get-PropertyValue -InputObject $backup -Name 'hash_checks' -Default @())
    $hashFailures = @(Get-PropertyValue -InputObject $backup -Name 'hash_failures' -Default @())
    $missingFiles = @(Get-PropertyValue -InputObject $backup -Name 'missing_files' -Default @())
    $items.Add([pscustomobject]@{
            name = [string]$backup.name
            created_at = $createdAt
            age_hours = $ageHours
            size_bytes = [long](Get-PropertyValue -InputObject $backup -Name 'size_bytes' -Default 0)
            complete = [bool](Get-PropertyValue -InputObject $backup -Name 'complete' -Default $false)
            checks_pass = [bool](Get-PropertyValue -InputObject $backup -Name 'checks_pass' -Default $false)
            mysql_dump_complete = [bool](Get-PropertyValue -InputObject $backup -Name 'mysql_dump_complete' -Default $false)
            sqlite_integrity = [string](Get-PropertyValue -InputObject $backup -Name 'sqlite_integrity' -Default 'not checked')
            missing_files = $missingFiles
            hash_checks = $hashChecks
            hash_failures = $hashFailures
            retain_reasons = [System.Collections.Generic.List[string]]::new()
        })
}

$rollbackBackup = @($items | Where-Object { $_.name -eq $RollbackBackupName } | Select-Object -First 1)
if ($rollbackBackup.Count -eq 0) {
    $critical.Add("Current rollback backup is missing: $RollbackBackupName")
} else {
    $rollbackBackup[0].retain_reasons.Add("rollback-$RollbackRetentionDays-days")
    if (-not $rollbackBackup[0].checks_pass) {
        $critical.Add("Current rollback backup failed integrity checks: $RollbackBackupName")
    }
}

foreach ($item in $items) {
    if ($item.checks_pass) {
        continue
    }
    if ($item.missing_files.Count -gt 0) {
        $warnings.Add("$($item.name): missing $($item.missing_files -join ', ')")
        continue
    }
    if ($item.hash_failures.Count -gt 0) {
        $critical.Add("$($item.name): hash verification failed")
    }
    if (-not $item.mysql_dump_complete) {
        $critical.Add("$($item.name): MySQL dump completion marker missing")
    }
    if ($item.sqlite_integrity -ne 'ok') {
        $critical.Add("$($item.name): SQLite integrity=$($item.sqlite_integrity)")
    }
}

$dailyCandidates = @($items | Where-Object { $_.age_hours -le $DailyRetentionDays })
foreach ($item in $dailyCandidates) {
    $item.retain_reasons.Add("daily-$DailyRetentionDays-days")
}

$weekCalendar = [System.Globalization.GregorianCalendar]::new()
$weeklyCandidates = @($items | Where-Object {
        $_.age_hours -le ($WeeklyRetentionWeeks * 7)
    })
$weeklyKeepers = @($weeklyCandidates |
    Group-Object {
        $year = $weekCalendar.GetYear($_.created_at.LocalDateTime)
        $week = $weekCalendar.GetWeekOfYear(
            $_.created_at.LocalDateTime,
            [System.Globalization.CalendarWeekRule]::FirstFourDayWeek,
            [System.DayOfWeek]::Monday
        )
        "$year-W$week"
    } |
    ForEach-Object { $_.Group | Sort-Object created_at -Descending | Select-Object -First 1 })
foreach ($item in $weeklyKeepers) {
    $item.retain_reasons.Add("weekly-$WeeklyRetentionWeeks-weeks")
}

$monthlyCandidates = @($items | Where-Object {
        $_.age_hours -le ($MonthlyRetentionMonths * 31)
    })
$monthlyKeepers = @($monthlyCandidates |
    Group-Object { $_.created_at.ToString('yyyy-MM') } |
    ForEach-Object { $_.Group | Sort-Object created_at -Descending | Select-Object -First 1 })
foreach ($item in $monthlyKeepers) {
    $item.retain_reasons.Add("monthly-$MonthlyRetentionMonths-months")
}

$completeBackups = @($items | Where-Object { $_.checks_pass } | Sort-Object created_at -Descending)
$latestComplete = $completeBackups | Select-Object -First 1
if ($null -eq $latestComplete) {
    $critical.Add('No complete and integrity-verified backup is available.')
} elseif ($latestComplete.age_hours -gt $CriticalAfterHours) {
    $critical.Add("Latest verified backup is $($latestComplete.age_hours) hours old.")
} elseif ($latestComplete.age_hours -gt $WarningAfterHours) {
    $warnings.Add("Latest verified backup is $($latestComplete.age_hours) hours old.")
}

$status = if ($critical.Count -gt 0) { 'CRITICAL' } elseif ($warnings.Count -gt 0) { 'WARNING' } else { 'OK' }
$reportStamp = (Get-Date).ToString('yyyyMMdd-HHmmss')
New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null
$jsonPath = Join-Path $OutputRoot "stage3-2-backup-retention-$reportStamp.json"
$markdownPath = Join-Path $OutputRoot "stage3-2-backup-retention-$reportStamp.md"
$report = [ordered]@{
    generated_at = (Get-Date).ToString('o')
    server = $Server
    remote_backup_root = $RemoteBackupRoot
    policy = [ordered]@{
        daily_days = $DailyRetentionDays
        weekly_weeks = $WeeklyRetentionWeeks
        monthly_months = $MonthlyRetentionMonths
        rollback_days = $RollbackRetentionDays
        warning_after_hours = $WarningAfterHours
        critical_after_hours = $CriticalAfterHours
    }
    status = $status
    critical = $critical
    warnings = $warnings
    backups = $items
}
$report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $jsonPath -Encoding UTF8

$markdown = [System.Collections.Generic.List[string]]::new()
$markdown.Add('# 阶段 3.2 备份保留与新鲜度检查')
$markdown.Add('')
$markdown.Add("- 检查时间：``$((Get-Date).ToString('yyyy-MM-dd HH:mm:ss zzz'))``")
$markdown.Add("- 备份目录：``$RemoteBackupRoot``")
$markdown.Add("- 结论：``$status``")
$markdown.Add('')
$markdown.Add('## 策略')
$markdown.Add('')
$markdown.Add("- 每日包保留 ``$DailyRetentionDays`` 天。")
$markdown.Add("- 每周包保留 ``$WeeklyRetentionWeeks`` 周。")
$markdown.Add("- 每月包保留 ``$MonthlyRetentionMonths`` 个月。")
$markdown.Add("- 当前回滚包保留 ``$RollbackRetentionDays`` 天。")
$markdown.Add("- 最新完整包超过 ``$WarningAfterHours`` 小时为 WARNING，超过 ``$CriticalAfterHours`` 小时为 CRITICAL。")
$markdown.Add('')
$markdown.Add('## 备份包')
$markdown.Add('')
$markdown.Add('| 备份包 | 年龄（小时） | 完整 | 校验 | 保留原因 |')
$markdown.Add('| --- | ---: | --- | --- | --- |')
foreach ($item in ($items | Sort-Object created_at -Descending)) {
    $reasons = if ($item.retain_reasons.Count -gt 0) {
        $item.retain_reasons -join ', '
    } else {
        '待到期复核'
    }
    $markdown.Add("| $($item.name) | $($item.age_hours) | $($item.complete) | $($item.checks_pass) | $reasons |")
}
$markdown.Add('')
$markdown.Add('## 告警')
$markdown.Add('')
if ($critical.Count -eq 0 -and $warnings.Count -eq 0) {
    $markdown.Add('- 无。')
} else {
    foreach ($item in $critical) {
        $markdown.Add("- CRITICAL: $item")
    }
    foreach ($item in $warnings) {
        $markdown.Add("- WARNING: $item")
    }
}
$markdown.Add('')
$markdown.Add("- JSON：``$jsonPath``")
$markdown | Set-Content -LiteralPath $markdownPath -Encoding UTF8

Write-Host "Report: $jsonPath"
Write-Host "Summary: $markdownPath"
Write-Host "Backup retention status: $status"
if ($critical.Count -gt 0) {
    Write-Host "Backup retention check found $($critical.Count) critical issue(s)." -ForegroundColor Red
    exit 1
}
if ($warnings.Count -gt 0) {
    Write-Host "Backup retention check completed with $($warnings.Count) warning(s)." -ForegroundColor Yellow
} else {
    Write-Host 'Backup retention check passed.' -ForegroundColor Green
}
