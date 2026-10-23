#!/usr/bin/env python3
"""Query OSV for vulnerabilities in Maven dependencies.

Usage:
    mvn -B -ntp dependency:list -DincludeScope=runtime \
        -DoutputFile=target/dependency-list.txt
    python scripts/osv_scan.py --dependency-file target/dependency-list.txt

The script uses the public OSV API (https://api.osv.dev) and needs no token.
It exits non-zero when a finding reaches --fail-on (default: high).
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

OSV_BATCH_URL = "https://api.osv.dev/v1/querybatch"
OSV_VULN_URL = "https://api.osv.dev/v1/vulns/"
SEVERITY_RANK = {"UNKNOWN": 0, "LOW": 1, "MODERATE": 2, "MEDIUM": 2, "HIGH": 3, "CRITICAL": 4}
BATCH_SIZE = 200


def parse_dependency_file(path: str):
    deps = {}
    pattern = re.compile(r"^([\w.\-]+):([\w.\-]+):([\w.\-]+)(?::([\w.\-]+))?:([^:]+):(\w+)(?:\s+--.*)?$")
    with open(path, encoding="utf-8", errors="replace") as handle:
        for raw in handle:
            line = raw.strip()
            if not line or line.startswith("[") or line.startswith("#"):
                continue
            match = pattern.match(line)
            if not match:
                continue
            group, artifact, kind = match.group(1), match.group(2), match.group(3)
            version, scope = match.group(5), match.group(6)
            if kind in ("pom",) or "${" in version or scope not in ("compile", "runtime", "test", "provided"):
                continue
            name = f"{group}:{artifact}"
            deps[(name, version)] = scope
    return sorted(deps)


def get_json(url: str, attempts=3):
    last = None
    for attempt in range(attempts):
        try:
            request = urllib.request.Request(url)
            with urllib.request.urlopen(request, timeout=60) as response:
                return json.loads(response.read().decode("utf-8"))
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
            last = exc
            if attempt + 1 < attempts:
                time.sleep(2 * (attempt + 1))
    raise RuntimeError(f"OSV request failed after {attempts} attempts: {last}")


def post_json(url: str, payload, attempts=3):
    body = json.dumps(payload).encode()
    last = None
    for attempt in range(attempts):
        try:
            request = urllib.request.Request(
                url, data=body, headers={"Content-Type": "application/json"}, method="POST"
            )
            with urllib.request.urlopen(request, timeout=60) as response:
                return json.loads(response.read().decode("utf-8"))
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
            last = exc
            if attempt + 1 < attempts:
                time.sleep(2 * (attempt + 1))
    raise RuntimeError(f"OSV request failed after {attempts} attempts: {last}")


def severity_of(vuln):
    database = vuln.get("database_specific") or {}
    level = str(database.get("severity", "")).upper()
    if level == "MODERATE":
        return "MEDIUM"
    if level in SEVERITY_RANK:
        return level
    return "UNKNOWN"


def fixed_versions(vuln, name):
    versions = set()
    for affected in vuln.get("affected", []):
        package = affected.get("package") or {}
        if package.get("ecosystem") != "Maven" or package.get("name") != name:
            continue
        for range_entry in affected.get("ranges", []):
            for event in range_entry.get("events", []):
                if event.get("fixed"):
                    versions.add(event["fixed"])
    return sorted(versions)


def main() -> int:
    parser = argparse.ArgumentParser(description="Scan Maven dependencies with OSV.")
    parser.add_argument("--dependency-file", action="append", required=True,
                        help="output of mvn dependency:list; may be repeated")
    parser.add_argument("--fail-on", default="high",
                        choices=["none", "low", "medium", "high", "critical"])
    parser.add_argument("--baseline", help="JSON baseline of accepted findings")
    parser.add_argument("--update-baseline", metavar="PATH",
                        help="write current findings to PATH as the accepted baseline")
    args = parser.parse_args()

    deps = []
    for path in args.dependency_file:
        deps.extend(parse_dependency_file(path))
    deps = sorted(set(deps))
    if not deps:
        print("No Maven dependencies found. Run mvn dependency:list first.", file=sys.stderr)
        return 2

    stubs = []
    for start in range(0, len(deps), BATCH_SIZE):
        chunk = deps[start:start + BATCH_SIZE]
        queries = [{"package": {"name": name, "ecosystem": "Maven"}, "version": version}
                   for name, version in chunk]
        result = post_json(OSV_BATCH_URL, {"queries": queries}).get("results", [])
        for (name, version), entry in zip(chunk, result):
            for vuln in entry.get("vulns", []):
                if vuln.get("id"):
                    stubs.append((name, version, vuln["id"]))
    stubs = sorted(set(stubs))
    vuln_ids = sorted({vuln_id for _, _, vuln_id in stubs})
    details = {}

    def fetch(vuln_id):
        try:
            return get_json(OSV_VULN_URL + vuln_id)
        except Exception:
            return None

    with ThreadPoolExecutor(max_workers=8) as pool:
        for vuln_id, detail in zip(vuln_ids, pool.map(fetch, vuln_ids)):
            if detail:
                details[vuln_id] = detail

    findings = []
    for name, version, vuln_id in stubs:
        vuln = details.get(vuln_id, {"id": vuln_id})
        findings.append({
            "dependency": name,
            "version": version,
            "id": vuln_id,
            "severity": severity_of(vuln),
            "fixed": ", ".join(fixed_versions(vuln, name)) or "-",
            "summary": (vuln.get("summary") or "").strip(),
        })

    findings.sort(key=lambda item: (-SEVERITY_RANK.get(item["severity"], 0), item["dependency"], item["id"]))
    counts = {}
    for item in findings:
        counts[item["severity"]] = counts.get(item["severity"], 0) + 1

    def finding_key(item):
        return f"{item['dependency']}|{item['version']}|{item['id']}"

    if args.update_baseline:
        payload = {
            "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "findings": sorted({finding_key(item) for item in findings}),
        }
        with open(args.update_baseline, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, indent=2, ensure_ascii=False)
            handle.write("\n")
        print(f"Baseline written: {args.update_baseline} ({len(payload['findings'])} findings)")
        return 0

    baseline = set()
    if args.baseline:
        try:
            with open(args.baseline, encoding="utf-8") as handle:
                data = json.load(handle)
            baseline = set(data.get("findings", []) if isinstance(data, dict) else data)
        except FileNotFoundError:
            print(f"Baseline not found: {args.baseline}; treating as empty.", file=sys.stderr)

    new_findings = [item for item in findings if finding_key(item) not in baseline]
    print(f"Scanned {len(deps)} dependencies; findings: {len(findings)}")
    if args.baseline:
        print(f"  accepted baseline: {len(baseline)}; new: {len(new_findings)}")
    for level in ("CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN"):
        if counts.get(level):
            print(f"  {level}: {counts[level]}")
    if findings:
        print()
        for item in findings:
            print(f"[{item['severity']}] {item['dependency']}:{item['version']} {item['id']} (fixed: {item['fixed']})")
            if item["summary"]:
                print(f"    {item['summary']}")
            print()

    threshold = SEVERITY_RANK[args.fail_on.upper()]
    failing = [item for item in new_findings if SEVERITY_RANK.get(item["severity"], 0) >= threshold]
    if threshold and failing:
        print(f"Failure: {len(failing)} new finding(s) at or above {args.fail_on}.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
