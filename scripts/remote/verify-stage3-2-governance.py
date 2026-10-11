import hashlib
import json
import os
import re
import shutil
import sqlite3
import subprocess


DB_PATH = "/opt/ilink/rag_knowledge.sqlite"
CONFIG_PATH = "/etc/systemd/system/ilink.service.d/production.conf"
LOCAL_PROPERTIES_PATH = "/opt/ilink/application-local.properties"
PREFIX = "用户: "
SEPARATOR = "\n助手: "


def run(args, sudo=False, env=None):
    command = ["sudo"] + args if sudo else args
    return subprocess.run(
        command,
        text=True,
        capture_output=True,
        check=False,
        env=env,
    )


def utf16_len(value):
    if value is None:
        return 0
    return len(value.encode("utf-16-le")) // 2


def append_length_prefixed(parts, value):
    normalized = "" if value is None else value
    parts.append(str(utf16_len(normalized)))
    parts.append(":")
    parts.append(normalized)


def stable_document_id(user_id, conversation_id, user_message, assistant_reply):
    parts = []
    append_length_prefixed(parts, user_id)
    append_length_prefixed(parts, conversation_id)
    append_length_prefixed(parts, user_message)
    append_length_prefixed(parts, assistant_reply)
    return hashlib.sha256("".join(parts).encode("utf-8")).hexdigest()


def select_keeper(rows, stable_id):
    for row in rows:
        if row["document_id"] == stable_id:
            return row

    def normalized_id(row):
        value = row["id"]
        return None if value is None else int(value)

    def keeper_key(row):
        row_id = normalized_id(row)
        return (
            row["timestamp"] is None,
            "" if row["timestamp"] is None else str(row["timestamp"]),
            row_id is None,
            -1 if row_id is None else row_id,
        )

    return min(rows, key=keeper_key)


def parse_properties(path):
    values = {}
    with open(path, encoding="utf-8") as source:
        for raw in source:
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def collect_mysql_state():
    try:
        properties = parse_properties(LOCAL_PROPERTIES_PATH)
        env = os.environ.copy()
        env["MYSQL_PWD"] = properties.get("spring.datasource.password", "")
        result = run(
            [
                "mysql",
                "-uroot",
                "-N",
                "-B",
                "ilink_chat",
                "-e",
                "SELECT id, title FROM community_posts ORDER BY id;",
            ],
            env=env,
        )
        if result.returncode != 0:
            return {"ok": False, "posts": []}
        posts = []
        for line in result.stdout.splitlines():
            if not line.strip():
                continue
            parts = line.split("\t", 1)
            posts.append(
                {
                    "id": int(parts[0]),
                    "title": parts[1] if len(parts) > 1 else "",
                }
            )
        return {"ok": True, "posts": posts}
    except Exception:
        return {"ok": False, "posts": []}


def collect_sqlite_state():
    connection = sqlite3.connect(f"file:{DB_PATH}?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row

    def scalar(sql, params=()):
        return connection.execute(sql, params).fetchone()[0]

    integrity = scalar("PRAGMA integrity_check")
    unique_index_count = scalar(
        "SELECT COUNT(*) FROM sqlite_master "
        "WHERE type='index' AND name='uk_vector_store_document_id'"
    )
    rows = connection.execute(
        """
        SELECT id, document_id, user_id, conversation_id, content, timestamp
        FROM vector_store
        WHERE conversation_id IS NOT NULL
          AND conversation_id <> ''
          AND (source_id IS NULL OR source_id = '')
        ORDER BY id ASC
        """
    ).fetchall()
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
    expired_conversation = scalar(
        """
        SELECT COUNT(*)
        FROM vector_store
        WHERE conversation_id IS NOT NULL
          AND conversation_id <> ''
          AND (source_id IS NULL OR source_id = '')
          AND datetime(timestamp) < datetime('now', 'localtime', '-90 days')
        """
    )
    expired_retrieval_logs = scalar(
        """
        SELECT COUNT(*)
        FROM rag_retrieval_log
        WHERE datetime(created_at) < datetime('now', 'localtime', '-180 days')
        """
    )
    expired_access_logs = scalar(
        """
        SELECT COUNT(*)
        FROM rag_observability_access_log
        WHERE datetime(created_at) < datetime('now', 'localtime', '-180 days')
        """
    )

    groups = {}
    malformed_row_ids = []
    for raw_row in rows:
        row = dict(raw_row)
        content = row["content"]
        conversation_id = row["conversation_id"]
        if (
            content is None
            or conversation_id is None
            or conversation_id.strip() == ""
            or not content.startswith(PREFIX)
        ):
            malformed_row_ids.append(row["id"])
            continue
        separator_index = content.find(SEPARATOR, len(PREFIX))
        if separator_index < 0:
            malformed_row_ids.append(row["id"])
            continue
        user_message = content[len(PREFIX):separator_index]
        assistant_reply = content[separator_index + len(SEPARATOR):]
        if assistant_reply == "":
            malformed_row_ids.append(row["id"])
            continue
        stable_id = stable_document_id(
            row["user_id"],
            conversation_id,
            user_message,
            assistant_reply,
        )
        groups.setdefault(stable_id, []).append(row)

    duplicate_groups = {
        stable_id: group for stable_id, group in groups.items() if len(group) > 1
    }
    duplicate_row_ids = []
    for stable_id, group in duplicate_groups.items():
        keeper = select_keeper(group, stable_id)
        duplicate_row_ids.extend(
            row["id"] for row in group if row["id"] != keeper["id"]
        )
    groups_needing_migration = sum(
        1
        for stable_id, group in groups.items()
        if not any(row["document_id"] == stable_id for row in group)
    )

    conversation_ids = [row["id"] for row in rows]
    max_conversation_id = max(conversation_ids) if conversation_ids else None
    connection.close()

    return {
        "integrity": integrity,
        "unique_index_count": unique_index_count,
        "conversation_count": len(rows),
        "conversation_ids": conversation_ids,
        "max_conversation_id": max_conversation_id,
        "public_vectors": sum(count for _, count in public_sources),
        "public_sources": [
            {"source_id": source_id, "chunks": count}
            for source_id, count in public_sources
        ],
        "expired_conversation_90d": expired_conversation,
        "expired_retrieval_logs_180d": expired_retrieval_logs,
        "expired_access_logs_180d": expired_access_logs,
        "stable_groups": len(groups),
        "duplicate_groups": len(duplicate_groups),
        "duplicate_rows": len(duplicate_row_ids),
        "duplicate_row_ids": sorted(duplicate_row_ids),
        "groups_needing_migration": groups_needing_migration,
        "malformed_row_ids": malformed_row_ids,
        "disk_size_bytes": os.path.getsize(DB_PATH),
    }


def collect_runtime_state():
    service = run(["systemctl", "is-active", "ilink"]).stdout.strip()
    active_entered = run(
        ["systemctl", "show", "ilink", "-p", "ActiveEnterTimestamp", "--value"]
    ).stdout.strip()
    listener_output = run(["ss", "-ltn"]).stdout
    listen_8080 = (
        "127.0.0.1:8080" in listener_output
        or "[::ffff:127.0.0.1]:8080" in listener_output
    )
    http_codes = {}
    for name, url in (
        ("local", "http://127.0.0.1:8080/"),
        ("main", "https://sekaipetplant.com/"),
        ("www", "https://www.sekaipetplant.com/"),
    ):
        result = run(
            ["curl", "-sS", "-o", "/dev/null", "-w", "%{http_code}", url]
        )
        http_codes[name] = result.stdout.strip() if result.returncode == 0 else "error"

    config = run(["cat", CONFIG_PATH], sudo=True).stdout
    governance_match = re.search(
        r'(?m)^\s*Environment="APP_DATA_GOVERNANCE_ENABLED=([^"]*)"\s*$',
        config,
    )
    governance_enabled = (
        governance_match.group(1).lower() == "true"
        if governance_match
        else False
    )

    journal = run(
        ["journalctl", "-u", "ilink", "--since", "today 00:00", "--no-pager"]
    ).stdout
    if not journal:
        journal = run(["tail", "-n", "3000", "/opt/ilink/app.log"]).stdout
    deletion_markers = []
    failure_markers = []
    for line in journal.splitlines():
        if any(
            marker in line
            for marker in (
                "Scheduled duplicate conversation vector cleanup removed",
                "Scheduled conversation vector retention cleanup removed",
                "Scheduled RAG audit retention cleanup removed",
                "orphan community post",
            )
        ):
            deletion_markers.append(line)
        if any(
            marker in line
            for marker in (
                "vector index reload failed",
                "Failed to clear orphan",
                "Unexpected error occurred in scheduled task",
            )
        ):
            failure_markers.append(line)

    return {
        "service": service,
        "active_entered": active_entered,
        "listen_8080": listen_8080,
        "http_codes": http_codes,
        "governance_enabled": governance_enabled,
        "deletion_markers": deletion_markers[-20:],
        "failure_markers": failure_markers[-20:],
    }


def collect_disk_state():
    paths = {
        "application": "/opt/ilink",
        "backups": "/opt/ilink/backups",
    }
    result = {}
    for name, path in paths.items():
        if not os.path.exists(path):
            result[name] = {
                "path": path,
                "available": False,
                "total_bytes": None,
                "free_bytes": None,
                "used_percent": None,
            }
            continue
        usage = shutil.disk_usage(path)
        used_percent = round((usage.used / usage.total) * 100, 2) if usage.total else None
        result[name] = {
            "path": path,
            "available": True,
            "total_bytes": usage.total,
            "free_bytes": usage.free,
            "used_percent": used_percent,
        }
    return result


state = {
    "collected_at": subprocess.run(
        ["date", "-Iseconds"], text=True, capture_output=True, check=False
    ).stdout.strip(),
    "runtime": collect_runtime_state(),
    "sqlite": collect_sqlite_state(),
    "mysql": collect_mysql_state(),
    "disk": collect_disk_state(),
}
print(json.dumps(state, ensure_ascii=False, separators=(",", ":")))
