#!/usr/bin/env python3
"""Summarize rerank behaviour from the production RAG retrieval log (read-only).

Usage:
    python3 rerank-report.py [/opt/ilink/rag_knowledge.sqlite] [days]
"""
import datetime
import json
import sqlite3
import sys
from collections import Counter

path = sys.argv[1] if len(sys.argv) > 1 else "/opt/ilink/rag_knowledge.sqlite"
days = int(sys.argv[2]) if len(sys.argv) > 2 else 7
cut_ms = int((datetime.datetime.now() - datetime.timedelta(days=days)).timestamp() * 1000)

con = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
rows = con.execute(
    "select created_at, duration_ms, stats_json from rag_retrieval_log where created_at >= ?",
    (cut_ms,),
).fetchall()

total = len(rows)
enabled = applied = 0
fallbacks = Counter()
applied_ms = []
retrieval_ms = []

for created_at, duration_ms, stats_json in rows:
    try:
        stats = json.loads(stats_json or "{}")
    except Exception:
        stats = {}
    if stats.get("rerankEnabled"):
        enabled += 1
    if stats.get("rerankApplied"):
        applied += 1
        value = stats.get("rerankDurationMs")
        if isinstance(value, (int, float)):
            applied_ms.append(value)
    reason = stats.get("rerankFallbackReason")
    if reason:
        fallbacks[reason] += 1
    if isinstance(duration_ms, (int, float)):
        retrieval_ms.append(duration_ms)


def percentile(values, p):
    if not values:
        return None
    values = sorted(values)
    index = min(len(values) - 1, int(round((len(values) - 1) * p)))
    return values[index]


print(f"window: last {days} day(s), rows={total}")
if total:
    print(f"rerankEnabled={enabled} ({enabled / total:.1%}), applied={applied} ({applied / total:.1%})")
print("fallback:", dict(fallbacks) or "none")
print(f"rerank ms: p50={percentile(applied_ms, 0.5)} p95={percentile(applied_ms, 0.95)}")
print(f"retrieval ms: p50={percentile(retrieval_ms, 0.5)} p95={percentile(retrieval_ms, 0.95)}")
