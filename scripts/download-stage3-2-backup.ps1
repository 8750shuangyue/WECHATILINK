[CmdletBinding()]
param(
    [string]$Server = 'admin@101.37.254.73',
    [string]$IdentityFile = '',
    [string]$RemoteBackupPath = '/opt/ilink/backups/20261010-141347-stage3-2',
    [string]$LocalRoot = ''
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($IdentityFile)) {
    $IdentityFile = Join-Path $HOME '.ssh\codex-ilink-deploy_ed25519'
}
if ([string]::IsNullOrWhiteSpace($LocalRoot)) {
    $LocalRoot = Join-Path $repoRoot '.runtime-backups'
}
if (-not (Test-Path -LiteralPath $IdentityFile)) {
    throw "SSH identity file not found: $IdentityFile"
}

$backupName = Split-Path -Leaf $RemoteBackupPath
$localBackupPath = Join-Path $LocalRoot $backupName
New-Item -ItemType Directory -Path $localBackupPath -Force | Out-Null

$files = @(
    'demo-0.0.1-SNAPSHOT.jar',
    'ilink_chat.sql',
    'ilink_chat.sql.sha256',
    'jar.sha256',
    'production.conf',
    'production.conf.sha256',
    'rag_knowledge.sqlite',
    'rag_knowledge.sqlite.sha256'
)

foreach ($file in $files) {
    Write-Host "Downloading $file..."
    & scp -i $IdentityFile -o StrictHostKeyChecking=yes "$Server`:$RemoteBackupPath/$file" $localBackupPath
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to download $file"
    }
}

$verified = @()
foreach ($hashFile in Get-ChildItem -LiteralPath $localBackupPath -Filter '*.sha256') {
    $line = Get-Content -LiteralPath $hashFile.FullName -First 1
    if ($line -notmatch '^([0-9a-fA-F]{64})\s+\*?(.+)$') {
        throw "Invalid hash file: $($hashFile.Name)"
    }

    $expectedHash = $matches[1].ToLowerInvariant()
    $targetName = Split-Path -Leaf $matches[2].Trim()
    $targetPath = Join-Path $localBackupPath $targetName
    if (-not (Test-Path -LiteralPath $targetPath)) {
        throw "Backup target is missing for $($hashFile.Name): $targetName"
    }

    $actualHash = (Get-FileHash -LiteralPath $targetPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $expectedHash) {
        throw "Hash mismatch for $targetName"
    }

    $verified += [ordered]@{
        file = $targetName
        sha256 = $actualHash
    }
}

try {
    $userPrincipal = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
    & icacls $LocalRoot /inheritance:r /grant:r "${userPrincipal}:(OI)(CI)F" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "icacls exited with code $LASTEXITCODE"
    }
} catch {
    Write-Warning 'Could not tighten Windows ACLs for the local backup directory.'
}

$summary = [ordered]@{
    downloaded_at = (Get-Date).ToString('o')
    server = $Server
    remote_path = $RemoteBackupPath
    local_path = $localBackupPath
    files = $files
    verified_hashes = $verified
}

$summary | ConvertTo-Json -Depth 5
Write-Host 'Offline backup download and hash verification passed.' -ForegroundColor Green
