#!/usr/bin/env python3
"""备份新鲜度检查（阶段 4 / S-03）。

<=26h: OK
26-50h: WARNING (exit 0)
>50h 或缺失: CRITICAL (exit 1)

用法：python3 /opt/ilink/scripts/ilink_backup_check.py
"""

import json
import os
import sys
from datetime import datetime
from pathlib import Path

BACKUP_ROOT = Path(os.environ.get("ILINK_BACKUP_ROOT", "/opt/ilink/backups"))
REQUIRED_ARTIFACTS = ["ilink_chat.sql", "rag_knowledge.sqlite", "uploads.tar",
                      "nginx-site.conf", "SHA256SUMS"]


def log(msg):
    print(f"[backup-check] {msg}", flush=True)


def main():
    if not BACKUP_ROOT.exists():
        log(f"CRITICAL: backup root missing: {BACKUP_ROOT}")
        return 1

    candidates = []
    for path in BACKUP_ROOT.iterdir():
        manifest = path / "manifest.json"
        if not path.is_dir() or not manifest.exists():
            continue
        try:
            data = json.loads(manifest.read_text(encoding="utf-8"))
        except Exception:
            continue
        if data.get("managed_by") != "ilink-backup":
            continue
        candidates.append((path.stat().st_mtime, path, data))

    if not candidates:
        log("CRITICAL: no managed backup found")
        return 1

    _, latest, manifest = sorted(candidates)[-1]
    created = datetime.fromisoformat(manifest["created_at"])
    age_hours = (datetime.now() - created).total_seconds() / 3600.0
    log(f"latest: {latest.name} age={age_hours:.1f}h")

    missing = [name for name in REQUIRED_ARTIFACTS if not (latest / name).exists()]
    if missing:
        log(f"CRITICAL: missing artifacts: {', '.join(missing)}")
        return 1

    if age_hours > 50:
        log("CRITICAL: backup older than 50 hours")
        return 1
    if age_hours > 26:
        log("WARNING: backup older than 26 hours")
        return 0

    log("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
