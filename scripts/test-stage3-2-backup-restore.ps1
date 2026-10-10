[CmdletBinding()]
param(
    [string]$Server = 'admin@101.37.254.73',
    [string]$IdentityFile = '',
    [string]$RemoteBackupPath = '/opt/ilink/backups/20261010-141347-stage3-2',
    [int]$ExpectedConversationCount = 202
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($IdentityFile)) {
    $IdentityFile = Join-Path $HOME '.ssh\codex-ilink-deploy_ed25519'
}
if (-not (Test-Path -LiteralPath $IdentityFile)) {
    throw "SSH identity file not found: $IdentityFile"
}

$remoteCode = @'
import hashlib
import json
import os
import shutil
import sqlite3
import subprocess
import tempfile

backup_dir = os.environ["ILINK_BACKUP_DIR"]
source_path = os.path.join(backup_dir, "rag_knowledge.sqlite")
hash_path = os.path.join(backup_dir, "rag_knowledge.sqlite.sha256")
sql_dump_path = os.path.join(backup_dir, "ilink_chat.sql")

with open(hash_path, encoding="utf-8") as source:
    expected_hash = source.read().split()[0].lower()
with open(source_path, "rb") as source:
    digest = hashlib.sha256()
    for block in iter(lambda: source.read(1024 * 1024), b""):
        digest.update(block)
actual_hash = digest.hexdigest()

with open(sql_dump_path, "rb") as source:
    sql_dump_tail = source.read()[-4096:]
mysql_dump_complete = b"Dump completed" in sql_dump_tail

temp_dir = tempfile.mkdtemp(prefix="ilink-sqlite-restore-drill-")
restored_path = os.path.join(temp_dir, "restored.sqlite")
try:
    shutil.copy2(source_path, restored_path)
    connection = sqlite3.connect(f"file:{restored_path}?mode=ro", uri=True)
    integrity = connection.execute("PRAGMA integrity_check").fetchone()[0]
    conversation_count = connection.execute(
        """
        SELECT COUNT(*)
        FROM vector_store
        WHERE conversation_id IS NOT NULL
          AND conversation_id <> ''
          AND (source_id IS NULL OR source_id = '')
        """
    ).fetchone()[0]
    public_sources = connection.execute(
        """
        SELECT source_id, COUNT(*)
        FROM vector_store
        WHERE source_id IS NOT NULL
          AND source_id <> ''
          AND (conversation_id IS NULL OR conversation_id = '')
        GROUP BY source_id
        ORDER BY source_id
        """
    ).fetchall()
    unique_index_count = connection.execute(
        "SELECT COUNT(*) FROM sqlite_master "
        "WHERE type='index' AND name='uk_vector_store_document_id'"
    ).fetchone()[0]
    connection.close()
finally:
    shutil.rmtree(temp_dir, ignore_errors=True)

print(json.dumps({
    "backup_sha256_ok": actual_hash == expected_hash,
    "mysql_dump_complete_marker": mysql_dump_complete,
    "restored_integrity": integrity,
    "restored_conversation_count": conversation_count,
    "restored_public_sources": [
        {"source_id": source_id, "chunks": count}
        for source_id, count in public_sources
    ],
    "restored_unique_index_count": unique_index_count,
}, ensure_ascii=False, separators=(",", ":")))
'@

$encodedCode = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($remoteCode))
$remoteCommand = "ILINK_BACKUP_DIR='$RemoteBackupPath' bash -lc 'echo $encodedCode | base64 -d | python3 -'"
$output = & ssh -i $IdentityFile -o StrictHostKeyChecking=yes $Server $remoteCommand
if ($LASTEXITCODE -ne 0) {
    throw 'Remote restore drill failed to execute.'
}

$result = ($output -join "`n") | ConvertFrom-Json
$failures = @()
if (-not $result.backup_sha256_ok) {
    $failures += 'SQLite backup hash mismatch'
}
if (-not $result.mysql_dump_complete_marker) {
    $failures += 'MySQL dump completion marker missing'
}
if ($result.restored_integrity -ne 'ok') {
    $failures += "Restored SQLite integrity check failed: $($result.restored_integrity)"
}
if ([int]$result.restored_conversation_count -ne $ExpectedConversationCount) {
    $failures += "Restored conversation vector count is unexpected: $($result.restored_conversation_count)"
}
if ([int]$result.restored_unique_index_count -ne 1) {
    $failures += 'Restored SQLite unique index missing'
}
$sourceMap = @{}
foreach ($source in $result.restored_public_sources) {
    $sourceMap[[string]$source.source_id] = [int]$source.chunks
}
if ($sourceMap.Count -ne 2 -or $sourceMap['post_1'] -ne 1 -or $sourceMap['post_2'] -ne 1) {
    $failures += 'Restored public source mapping is unexpected'
}

$result | ConvertTo-Json -Depth 8
if ($failures.Count -gt 0) {
    Write-Host 'Restore drill failed:' -ForegroundColor Red
    $failures | ForEach-Object { Write-Host " - $_" -ForegroundColor Red }
    exit 1
}

Write-Host 'Read-only SQLite restore drill passed.' -ForegroundColor Green
