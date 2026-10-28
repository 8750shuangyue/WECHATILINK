#!/usr/bin/env python3
"""ILink 恢复演练（阶段 4 / S-03）。

MySQL：把备份 dump 导入固定临时库 ilink_chat_restore_drill，校验表与行数后删除。
       绝不写入 ilink_chat。SSH 到生产执行：sudo python3 ...
SQLite：副本 integrity_check + 向量计数 + 唯一索引检查。
uploads.tar：成员安全性检查与数量核对。

用法：
  sudo python3 /opt/ilink/scripts/ilink_restore_drill.py [--backup-dir PATH]
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import tarfile
import tempfile
import time
from pathlib import Path

BACKUP_ROOT = Path(os.environ.get("ILINK_BACKUP_ROOT", "/opt/ilink/backups"))
LOCAL_PROPS = Path("/opt/ilink/application-local.properties")
MYSQL_DB = os.environ.get("ILINK_MYSQL_DB", "ilink_chat")
MYSQL_USER = os.environ.get("ILINK_MYSQL_USER", "root")
DRILL_DB = "ilink_chat_restore_drill"
SAFE_IDENT = re.compile(r"^[A-Za-z0-9_]+$")
CORE_TABLES = ["users", "conversations", "messages", "community_posts",
               "products", "care_targets", "care_record"]


def log(message):
    print(f"[drill] {message}", flush=True)


def fail(message):
    log(f"ERROR: {message}")
    sys.exit(1)


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def mysql_password():
    for raw in LOCAL_PROPS.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw.strip()
        if line.startswith("spring.datasource.password="):
            return line.split("=", 1)[1].strip().strip('"').strip("'")
    fail("spring.datasource.password not found")


def mysql(env, args, stdin_path=None, capture=True):
    out = subprocess.PIPE if capture else subprocess.DEVNULL
    stdin = open(stdin_path, "rb") if stdin_path else subprocess.DEVNULL
    try:
        return subprocess.run(["mysql", f"-u{MYSQL_USER}", "-N", "-B"] + args,
                              stdin=stdin, stdout=out, stderr=subprocess.PIPE,
                              env=env, timeout=3600, check=False)
    finally:
        if stdin_path:
            stdin.close()


def verify_checksums(directory):
    sums = directory / "SHA256SUMS"
    if not sums.exists():
        fail("SHA256SUMS missing")
    for line in sums.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        expected, name = line.split("  ", 1)
        if sha256_file(directory / name) != expected:
            fail(f"checksum mismatch: {name}")
    log("checksums verified")


def assert_dump_is_data_only(dump_path):
    forbidden = re.compile(r"^\s*(USE\s|CREATE\s+DATABASE|CREATE\s+SCHEMA|DROP\s+DATABASE)",
                           re.IGNORECASE)
    with open(dump_path, "r", encoding="utf-8", errors="replace") as handle:
        for line in handle:
            if forbidden.match(line):
                fail(f"dump contains unsafe statement: {line.strip()[:120]}")


def drill_mysql(directory, env):
    dump = directory / "ilink_chat.sql"
    if not dump.exists():
        fail("ilink_chat.sql missing")
    assert_dump_is_data_only(dump)

    start = time.time()
    try:
        result = mysql(env, ["-e", f"DROP DATABASE IF EXISTS {DRILL_DB}; CREATE DATABASE {DRILL_DB}"])
        if result.returncode != 0:
            fail("cannot create drill database: " + result.stderr.decode("utf-8", "replace")[:300])

        result = mysql(env, [DRILL_DB], stdin_path=dump)
        if result.returncode != 0:
            fail("import failed: " + result.stderr.decode("utf-8", "replace")[:500])

        drill_tables = set(
            row.decode().strip() for row in
            mysql(env, ["-e", f"SHOW TABLES FROM {DRILL_DB}"]).stdout.splitlines())
        log(f"drill tables: {len(drill_tables)}")

        for table in CORE_TABLES:
            if not SAFE_IDENT.match(table):
                continue
            source = mysql(env, ["-e", f"SELECT COUNT(*) FROM `{MYSQL_DB}`.`{table}`"])
            if source.returncode != 0:
                continue  # 源库没有该表
            if table not in drill_tables:
                fail(f"core table missing in drill: {table}")
            source_count = int(source.stdout.decode().strip() or 0)
            drill_count = int(mysql(env, ["-e",
                    f"SELECT COUNT(*) FROM `{DRILL_DB}`.`{table}`"]).stdout.decode().strip() or 0)
            log(f"rowcount {table}: source={source_count} drill={drill_count}")
            if source_count > 0 and drill_count == 0:
                fail(f"drill table unexpectedly empty: {table}")

        check_targets = [t for t in CORE_TABLES if t in drill_tables]
        if check_targets:
            result = mysql(env, ["-e",
                    f"CHECK TABLE " + ", ".join(f"`{DRILL_DB}`.`{t}`" for t in check_targets)])
            output = result.stdout.decode("utf-8", "replace")
            if "error" in output.lower():
                fail("CHECK TABLE reported errors: " + output[:500])
            log("CHECK TABLE ok")
    finally:
        mysql(env, ["-e", f"DROP DATABASE IF EXISTS {DRILL_DB}"], capture=False)
        log(f"mysql drill finished in {time.time() - start:.1f}s; drill db dropped")


def drill_sqlite(directory):
    source = directory / "rag_knowledge.sqlite"
    if not source.exists():
        log("skip sqlite drill (no backup file)")
        return
    with tempfile.TemporaryDirectory() as tmp:
        copy = Path(tmp) / "rag_knowledge.sqlite"
        shutil.copy2(source, copy)
        with sqlite3.connect(f"file:{copy}?mode=ro", uri=True) as conn:
            check = conn.execute("PRAGMA integrity_check").fetchone()[0]
            if check != "ok":
                fail(f"sqlite integrity_check failed: {check}")
            total = conn.execute("SELECT COUNT(*) FROM vector_store").fetchone()[0]
            conversations = conn.execute(
                "SELECT COUNT(DISTINCT conversation_id) FROM vector_store "
                "WHERE conversation_id IS NOT NULL").fetchone()[0]
            public = conn.execute(
                "SELECT COUNT(*) FROM vector_store WHERE conversation_id IS NULL").fetchone()[0]
            index = conn.execute(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='index' "
                "AND name='uk_vector_store_document_id'").fetchone()[0]
        log(f"sqlite ok: vectors={total}, conversations={conversations}, public={public}, "
            f"unique_index={bool(index)}")
        if not index:
            fail("sqlite unique index uk_vector_store_document_id missing")


def drill_uploads(directory):
    archive = directory / "uploads.tar"
    if not archive.exists():
        log("skip uploads drill (no archive)")
        return
    count = 0
    total_bytes = 0
    with tarfile.open(archive, "r") as tar:
        for member in tar.getmembers():
            name = member.name.replace("\\", "/")
            if name.startswith("/") or ".." in name.split("/"):
                fail(f"unsafe tar member: {member.name}")
            if member.isfile():
                count += 1
                total_bytes += member.size
    log(f"uploads ok: files={count}, bytes={total_bytes}")


def latest_backup():
    candidates = []
    for path in BACKUP_ROOT.iterdir():
        if path.is_dir() and (path / "manifest.json").exists():
            candidates.append((path.stat().st_mtime, path))
    if not candidates:
        fail("no backup with manifest.json found")
    return sorted(candidates)[-1][1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--backup-dir")
    args = parser.parse_args()

    directory = Path(args.backup_dir) if args.backup_dir else latest_backup()
    log(f"drill source: {directory}")
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    log(f"backup created_at: {manifest.get('created_at')}")

    verify_checksums(directory)

    env = dict(os.environ)
    env["MYSQL_PWD"] = mysql_password()
    drill_mysql(directory, env)
    drill_sqlite(directory)
    drill_uploads(directory)
    log("restore drill PASSED")


if __name__ == "__main__":
    main()
