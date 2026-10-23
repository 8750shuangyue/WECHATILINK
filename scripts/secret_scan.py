#!/usr/bin/env python3
"""Dependency-free secret scanner for commits, staged files and history.

Usage:
    python scripts/secret_scan.py --tracked   # scan tracked working-tree files
    python scripts/secret_scan.py --staged    # scan files staged for commit
    python scripts/secret_scan.py --history   # scan every blob in git history

Exit code 1 when any finding is reported. Values are always masked; the
scanner never prints a full credential.
"""

from __future__ import annotations

import argparse
import hashlib
import re
import subprocess
import sys

GENERIC = re.compile(
    r"(?i)\b(password|passwd|pwd|secret|api[_-]?key|access[_-]?key|"
    r"client[_-]?secret|auth[_-]?token|private[_-]?key|aes[_-]?key|token)\b"
    r'\s*[:=]\s*["\']?([^\s"\'#]{8,})'
)

HIGH_SIGNAL = [
    ("private_key", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----")),
    ("aws_access_key", re.compile(r"\b(?:AKIA|ASIA)[0-9A-Z]{16}\b")),
    ("github_token", re.compile(r"\b(?:gh[pousr]|github_pat)_[A-Za-z0-9_]{20,}\b")),
    ("slack_token", re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{10,}\b")),
    ("sk_token", re.compile(r"\bsk-[A-Za-z0-9_-]{20,}\b")),
    ("aliyun_access_key", re.compile(r"\bLTAI[0-9A-Za-z]{8,}\b")),
    ("jwt", re.compile(r"\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b")),
]

PLACEHOLDER_WORDS = (
    "your", "example", "change", "placeholder", "replace", "todo", "none",
    "null", "true", "false", "process.env", "env:", "xxx", "password",
    "test", "demo", "mock", "sample", "fake", "dummy", "redacted", "你的",
    "sk-xxx", "tvl-xxx", "ap-xxx", "sc-xxx",
)

SKIP_SUFFIXES = (
    ".png", ".jpg", ".jpeg", ".gif", ".webp", ".pdf", ".jar", ".class",
    ".woff", ".woff2", ".ttf", ".sqlite", ".db", ".zip", ".gz", ".7z",
    ".rar", ".ico", ".bmp", ".mp4", ".mp3",
)

ALLOWLIST = {
    "scripts/secret_scan.py",
    ".githooks/pre-commit",
    ".github/workflows/secret-scan.yml",
    "SECURITY.md",
    "落地文档/阶段4.1凭据与暴露面自查-2026-10-23.md",
}


def mask(value: str) -> str:
    value = value.strip()
    if len(value) <= 4:
        return "*" * len(value)
    return value[:2] + "…"


def sha8(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8", "replace")).hexdigest()[:8]


def is_placeholder(value: str) -> bool:
    value = value.strip().strip("\"'")
    low = value.lower()
    if not value:
        return True
    if value.startswith(("${", "<", "#", "$")):
        return True
    if "****" in value or set(value) <= {"*", ".", "-"}:
        return True
    if any(word in low for word in PLACEHOLDER_WORDS):
        return True
    if re.fullmatch(r"[A-Za-z_][\w.]*\([^)]*\)[);]*", value):
        return True
    if re.fullmatch(r"[A-Za-z_][\w.]*\)", value):
        return True
    if "(" in value and re.match(r"^[A-Za-z_][\w.]*\(", value):
        return True
    if value.startswith(("request", "req.", "properties.", "this.", "new ", "String ", "System.")):
        return True
    if low.startswith(("get", "set")):
        return True
    if len(value) < 8:
        return True
    return False


def scan_text(path: str, text: str):
    for lineno, line in enumerate(text.splitlines(), 1):
        for name, rx in HIGH_SIGNAL:
            for match in rx.finditer(line):
                if is_placeholder(match.group(0)):
                    continue
                yield path, lineno, name, "", match.group(0)
        for match in GENERIC.finditer(line):
            key, value = match.group(1), match.group(2)
            if is_placeholder(value):
                continue
            yield path, lineno, "generic", key, value


def report(findings):
    unique = {}
    for path, lineno, name, key, value in findings:
        token = (path, lineno, name, key, sha8(value))
        unique[token] = value
    for (path, lineno, name, key, digest), value in sorted(unique.items()):
        label = f"{key}=" if key else ""
        print(f"{path}:{lineno} [{name}] {label}{mask(value)} (len={len(value)}, sha8={digest})")
    return len(unique)


def git(*args):
    return subprocess.run(["git", *args], capture_output=True, check=False)


def decode(data: bytes) -> str:
    return data.decode("utf-8", "replace")


def staged_paths():
    out = decode(git("diff", "--cached", "--name-only", "--diff-filter=ACM", "-z").stdout)
    return [p for p in out.split("\0") if p]


def tracked_paths():
    out = decode(git("ls-files", "-z").stdout)
    return [p for p in out.split("\0") if p]


def should_skip(path: str) -> bool:
    return path in ALLOWLIST or path.endswith(SKIP_SUFFIXES)


def scan_staged():
    for path in staged_paths():
        if should_skip(path):
            continue
        content = decode(git("show", f":{path}").stdout)
        yield from scan_text(path, content)


def scan_tracked():
    for path in tracked_paths():
        if should_skip(path):
            continue
        try:
            with open(path, encoding="utf-8", errors="replace") as handle:
                text = handle.read()
        except OSError:
            continue
        if "\0" in text[:2048]:
            continue
        yield from scan_text(path, text)


def scan_history():
    objects = decode(git("rev-list", "--all", "--objects").stdout)
    sha_paths: dict[str, set[str]] = {}
    for line in objects.splitlines():
        parts = line.split(" ", 1)
        if len(parts) == 2 and len(parts[0]) == 40:
            sha_paths.setdefault(parts[0], set()).add(parts[1])
    order = sorted(sha_paths)
    batch = subprocess.run(
        ["git", "cat-file", "--batch"],
        input="".join(f"{sha}\n" for sha in order).encode(),
        capture_output=True,
        check=False,
    ).stdout
    index = 0
    while index < len(batch):
        newline = batch.find(b"\n", index)
        if newline < 0:
            break
        header = batch[index:newline].decode("utf-8", "replace").split()
        index = newline + 1
        if len(header) < 3:
            break
        sha, kind, size = header[0], header[1], int(header[2])
        data = batch[index:index + size]
        index += size + 1
        if kind != "blob" or b"\0" in data[:2048]:
            continue
        text = data.decode("utf-8", "replace")
        for path in sha_paths.get(sha, set()):
            if should_skip(path):
                continue
            for source, lineno, name, key, value in scan_text(path, text):
                yield source, lineno, name, key, value


def main() -> int:
    parser = argparse.ArgumentParser(description="Scan for credentials before they reach git.")
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--tracked", action="store_true", help="scan tracked working-tree files (default)")
    group.add_argument("--staged", action="store_true", help="scan files staged for commit")
    group.add_argument("--history", action="store_true", help="scan every blob in git history")
    args = parser.parse_args()

    try:
        if hasattr(sys.stdout, "reconfigure"):
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    if args.staged:
        findings = list(scan_staged())
    elif args.history:
        findings = list(scan_history())
    else:
        findings = list(scan_tracked())

    count = report(findings)
    if count:
        print(f"Secret scan failed: {count} finding(s).", file=sys.stderr)
        return 1
    print("Secret scan passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
