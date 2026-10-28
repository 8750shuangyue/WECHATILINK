#!/usr/bin/env python3
"""ILink 生产备份脚本（阶段 4 / S-03）。

产物目录：/opt/ilink/backups/<YYYYmmdd-HHMMSS>-<purpose>/
  - demo-0.0.1-SNAPSHOT.jar
  - ilink_chat.sql（mysqldump，含 Dump completed 校验）
  - rag_knowledge.sqlite（在线一致性副本 + integrity_check）
  - uploads.tar（上传目录）
  - production.conf / nginx-site.conf（能读到时）
  - SHA256SUMS、manifest.json

用法：
  sudo python3 /opt/ilink/scripts/ilink_backup.py --purpose auto
  sudo python3 /opt/ilink/scripts/ilink_backup.py --purpose pre-stage4 --skip-retention

凭据处理：从 /opt/ilink/application-local.properties 读取数据库密码，
通过子进程环境变量 MYSQL_PWD 传递，不写盘、不回显、不创建新数据库用户。
"""

import argparse
import fcntl
import hashlib
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import tarfile
from datetime import datetime
from pathlib import Path

APP_DIR = Path(os.environ.get("ILINK_APP_DIR", "/opt/ilink"))
BACKUP_ROOT = Path(os.environ.get("ILINK_BACKUP_ROOT", str(APP_DIR / "backups")))
JAR_NAME = "demo-0.0.1-SNAPSHOT.jar"
SQLITE_NAME = "rag_knowledge.sqlite"
UPLOADS_DIR = APP_DIR / "uploads"
LOCAL_PROPS = APP_DIR / "application-local.properties"
PRODUCTION_CONF = Path("/etc/systemd/system/ilink.service.d/production.conf")
NGINX_SITE = Path("/etc/nginx/sites-available/sekaipetplant.com")
MYSQL_DB = os.environ.get("ILINK_MYSQL_DB", "ilink_chat")
MYSQL_USER = os.environ.get("ILINK_MYSQL_USER", "root")
LOCK_FILE = Path("/tmp/ilink-backup.lock")
MANAGED_BY = "ilink-backup"

MIN_FREE_BYTES = 2 * 1024 * 1024 * 1024


def log(message):
    print(f"[backup] {message}", flush=True)


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
    if not LOCAL_PROPS.exists():
        fail(f"{LOCAL_PROPS} not found")
    for raw in LOCAL_PROPS.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key.strip() == "spring.datasource.password":
            return value.strip().strip('"').strip("'")
    fail("spring.datasource.password not found in application-local.properties")


def run(cmd, env, stdout_path=None, timeout=3600):
    log("running: " + " ".join(cmd[:3]) + " ...")
    out = open(stdout_path, "wb") if stdout_path else open(os.devnull, "wb")
    try:
        return subprocess.run(cmd, stdout=out, stderr=subprocess.PIPE,
                              env=env, timeout=timeout, check=False)
    finally:
        out.close()


def dump_mysql(target, password):
    env = dict(os.environ)
    env["MYSQL_PWD"] = password
    cmd = ["mysqldump", f"-u{MYSQL_USER}", "--single-transaction", "--quick",
           "--routines", "--triggers", "--skip-lock-tables", MYSQL_DB]
    result = run(cmd, env, stdout_path=target)
    if result.returncode != 0:
        fail("mysqldump failed: " + result.stderr.decode("utf-8", "replace")[:500])
    with open(target, "rb") as handle:
        handle.seek(0, os.SEEK_END)
        size = handle.tell()
        handle.seek(max(0, size - 4096))
        tail = handle.read().decode("utf-8", "replace")
    if "Dump completed" not in tail:
        fail("mysqldump output missing 'Dump completed'")
    log(f"mysql dump ok: {target.name} ({size} bytes)")


def backup_sqlite(source, target):
    with sqlite3.connect(f"file:{source}?mode=ro", uri=True) as src, \
            sqlite3.connect(target) as dst:
        src.backup(dst)
    with sqlite3.connect(f"file:{target}?mode=ro", uri=True) as conn:
        check = conn.execute("PRAGMA integrity_check").fetchone()[0]
    # integrity_check 以 WAL 模式打开会生成 -shm/-wal 边车文件，不属于备份产物
    for suffix in ("-shm", "-wal"):
        sidecar = Path(str(target) + suffix)
        if sidecar.exists():
            sidecar.unlink()
    if check != "ok":
        fail(f"sqlite integrity_check failed: {check}")
    log(f"sqlite backup ok: {target.name}")


def backup_uploads(target):
    if not UPLOADS_DIR.exists():
        log("uploads dir not found, writing empty archive")
    with tarfile.open(target, "w") as archive:
        if UPLOADS_DIR.exists():
            for path in sorted(UPLOADS_DIR.rglob("*")):
                archive.add(path, arcname=str(path.relative_to(APP_DIR)), recursive=False)
    size = target.stat().st_size
    log(f"uploads archive ok: {target.name} ({size} bytes)")
    if size > MIN_FREE_BYTES:
        log("WARN: uploads archive exceeds 2GB, consider hardlink snapshots")


def copy_optional(source, target):
    if not source.exists():
        log(f"skip missing {source}")
        return False
    shutil.copy2(source, target)
    return True


def write_checksums(directory, names):
    lines = []
    for name in names:
        path = directory / name
        if path.exists():
            lines.append(f"{sha256_file(path)}  {name}")
    (directory / "SHA256SUMS").write_text("\n".join(lines) + "\n", encoding="utf-8")


def verify_checksums(directory):
    sums = directory / "SHA256SUMS"
    if not sums.exists():
        fail("SHA256SUMS missing")
    for line in sums.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        expected, name = line.split("  ", 1)
        actual = sha256_file(directory / name)
        if actual != expected:
            fail(f"checksum mismatch: {name}")


def apply_retention(root, current_dir, keep_days=14, weekly_days=56, monthly_days=183):
    entries = []
    for path in sorted(root.iterdir()):
        if not path.is_dir() or path == current_dir:
            continue
        manifest = path / "manifest.json"
        if not manifest.exists():
            continue
        try:
            data = json.loads(manifest.read_text(encoding="utf-8"))
        except Exception:
            continue
        if data.get("managed_by") != MANAGED_BY:
            continue
        age_days = (datetime.now() - datetime.fromtimestamp(path.stat().st_mtime)).days
        entries.append((age_days, path))

    entries.sort(key=lambda item: item[0])
    keep = set()
    weekly_seen = set()
    monthly_seen = set()
    for age_days, path in entries:
        if age_days <= keep_days:
            keep.add(path)
        elif age_days <= weekly_days:
            iso = datetime.fromtimestamp(path.stat().st_mtime).isocalendar()
            key = (iso[0], iso[1])
            if key in weekly_seen:
                continue
            weekly_seen.add(key)
            keep.add(path)
        elif age_days <= monthly_days:
            stamp = datetime.fromtimestamp(path.stat().st_mtime)
            key = (stamp.year, stamp.month)
            if key in monthly_seen:
                continue
            monthly_seen.add(key)
            keep.add(path)
        # older than monthly window: not kept

    for age_days, path in entries:
        if path in keep:
            continue
        log(f"retention: deleting {path.name} (age {age_days}d)")
        shutil.rmtree(path, ignore_errors=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--purpose", default="auto")
    parser.add_argument("--skip-retention", action="store_true")
    args = parser.parse_args()

    BACKUP_ROOT.mkdir(parents=True, exist_ok=True)
    LOCK_FILE.touch(exist_ok=True)
    lock_handle = open(LOCK_FILE, "w")
    try:
        fcntl.flock(lock_handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        fail("another backup is running")

    usage = shutil.disk_usage(BACKUP_ROOT)
    if usage.free < max(MIN_FREE_BYTES, int(usage.total * 0.15)):
        fail(f"not enough disk space: {usage.free} bytes free")

    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    target = BACKUP_ROOT / f"{stamp}-{args.purpose}"
    target.mkdir(parents=True, exist_ok=False)
    log(f"target: {target}")

    names = []
    try:
        jar = APP_DIR / JAR_NAME
        if not jar.exists():
            fail(f"jar not found: {jar}")
        shutil.copy2(jar, target / JAR_NAME)
        names.append(JAR_NAME)

        dump_mysql(target / "ilink_chat.sql", mysql_password())
        names.append("ilink_chat.sql")

        sqlite_source = APP_DIR / SQLITE_NAME
        if sqlite_source.exists():
            backup_sqlite(sqlite_source, target / SQLITE_NAME)
            names.append(SQLITE_NAME)
        else:
            log(f"WARN: sqlite not found: {sqlite_source}")

        backup_uploads(target / "uploads.tar")
        names.append("uploads.tar")

        if copy_optional(PRODUCTION_CONF, target / "production.conf"):
            names.append("production.conf")
        if copy_optional(NGINX_SITE, target / "nginx-site.conf"):
            names.append("nginx-site.conf")

        write_checksums(target, names)
        verify_checksums(target)
        log("checksums verified")

        manifest = {
            "managed_by": MANAGED_BY,
            "created_at": datetime.now().isoformat(timespec="seconds"),
            "purpose": args.purpose,
            "mysql_db": MYSQL_DB,
            "artifacts": {name: (target / name).stat().st_size for name in names},
        }
        tmp = target / "manifest.json.tmp"
        tmp.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
        tmp.replace(target / "manifest.json")
        log("manifest written")

        if not args.skip_retention and args.purpose == "auto":
            apply_retention(BACKUP_ROOT, target)
    except Exception as exc:
        log(f"backup failed: {exc}")
        # 保留失败目录便于排查，但移除 manifest 以免被当成本次可用备份
        manifest = target / "manifest.json"
        if manifest.exists():
            manifest.rename(target / "manifest.failed.json")
        sys.exit(1)
    finally:
        fcntl.flock(lock_handle, fcntl.LOCK_UN)
        lock_handle.close()

    log("backup completed successfully")


if __name__ == "__main__":
    main()
