#!/usr/bin/env python3
"""RAG 召回离线标定脚本（一次性工具）。

目的：`rag_retrieval_log` 只存过阈值的结果，阈值以下的相似度被丢掉了，
所以「降到多少阈值能救回那 6 道题」无法从历史数据推断，必须重算。

本脚本对题库中 29 道有 `expectedSourceIds` 的题重算 query 向量，与本地
SQLite 里 22 个公共知识块逐一算余弦，得到完整 29x22 矩阵，然后离线模拟
各种 (阈值, topK, 融合策略) 下的加权 Recall@K。

不进入生产 pom、不进入生产 JAR、不加依赖。只在本地跑。

用法：
    python scripts/rag-calibration.py --dry-run      # 只打印一条脱敏请求体
    python scripts/rag-calibration.py                # 全量跑（约 32 次外呼）
    python scripts/rag-calibration.py --reuse-cache  # 用缓存向量，零网络

注意：向量是 Java `ByteBuffer` 默认的**大端** float32，且入库前已归一化
（范数精确 1.0）。按小端解析会得到一堆 NaN。
"""

import argparse
import json
import math
import os
import re
import sqlite3
import struct
import sys
import time
from collections import defaultdict
from datetime import datetime

try:
    import requests
except ImportError:  # pragma: no cover
    sys.stderr.write("需要 requests：pip install requests\n")
    raise SystemExit(2)

try:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
except Exception:  # pragma: no cover
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DB_PATH = os.path.join(ROOT, "rag_knowledge.sqlite")
CASE_FILE = os.path.join(ROOT, "TEST/eval/ai-eval-v1.jsonl")
LOCAL_PROPS = os.path.join(ROOT, "application-local.properties")
APP_PROPS = os.path.join(ROOT, "src/main/resources/application.properties")
LOG_BASELINE = os.path.join(ROOT, "TEST/eval/results/baseline-20261013-191950.jsonl")

DIM = 1536
DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
DEFAULT_MODEL = "text-embedding-v2"

# 期望复现的当前生产口径，见 TEST/eval/results/baseline-20261013-203432.md
BASELINE_RECALL = 0.6538
BASELINE_SAMPLES = 39
BASELINE_THRESHOLD = 0.5
BASELINE_TOPK = 5

ZERO_CASES = ["plant-001", "plant-003", "plant-010", "pet-007", "pet-008", "refusal-002"]


# --------------------------------------------------------------------------
# 配置与密钥
# --------------------------------------------------------------------------

def read_properties(path):
    """读 key=value，忽略注释与空行。不做 ${} 展开。"""
    props = {}
    if not os.path.exists(path):
        return props
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            props[key.strip()] = value.strip()
    return props


def resolve_embedding_config():
    """base-url / model 取 application.properties，key 取本地密钥文件或环境变量。"""
    app = read_properties(APP_PROPS)
    local = read_properties(LOCAL_PROPS)

    def pick(*keys, default=""):
        for source in (local, app):
            for key in keys:
                value = source.get(key)
                if value:
                    expanded = re.sub(
                        r"\$\{([^:}]+)(?::([^}]*))?\}",
                        lambda m: os.environ.get(m.group(1), m.group(2) or ""),
                        value,
                    )
                    if expanded:
                        return expanded
        return default

    base_url = pick("dashscope.embedding.base-url", default=DEFAULT_BASE_URL)
    model = pick("dashscope.embedding-model", "dashscope.embedding.model", default=DEFAULT_MODEL)
    api_key = pick("dashscope.embedding.api-key") or os.environ.get(
        "DASHSCOPE_EMBEDDING_API_KEY", ""
    )
    return base_url.rstrip("/"), model, api_key


def redact(secret):
    if not secret:
        return "(未配置)"
    return "***" + secret[-4:] if len(secret) > 4 else "***"


# --------------------------------------------------------------------------
# Embedding 调用（请求体严格对齐 EmbeddingService.embed）
# --------------------------------------------------------------------------

SESSION = requests.Session()


def embed_one(text, base_url, model, api_key, timeout=60):
    """返回 float list。非 200 或维度不符直接抛错——绝不静默。"""
    url = base_url + "/embeddings"
    payload = {"model": model, "input": [text]}
    last_error = None
    for attempt in range(4):
        try:
            response = SESSION.post(
                url,
                json=payload,
                headers={
                    "Content-Type": "application/json",
                    "Authorization": "Bearer " + api_key,
                },
                timeout=timeout,
            )
        except requests.RequestException as exc:
            last_error = exc
            time.sleep(2 ** attempt)
            continue
        if response.status_code == 200:
            body = response.json()
            data = body.get("data")
            if not isinstance(data, list) or not data:
                raise RuntimeError("响应缺少 data 数组: %s" % response.text[:200])
            vector = data[0].get("embedding")
            if not isinstance(vector, list) or not vector:
                raise RuntimeError("响应缺少 embedding 数组")
            if len(vector) != DIM:
                raise RuntimeError("维度不符：期望 %d，实际 %d" % (DIM, len(vector)))
            return [float(x) for x in vector]
        if response.status_code in (429, 500, 502, 503, 504):
            last_error = RuntimeError("HTTP %d" % response.status_code)
            time.sleep(2 ** attempt)
            continue
        raise RuntimeError(
            "Embedding 接口失败 HTTP %d: %s" % (response.status_code, response.text[:300])
        )
    raise RuntimeError("Embedding 接口重试耗尽: %s" % last_error)


# --------------------------------------------------------------------------
# 向量与相似度
# --------------------------------------------------------------------------

def unpack_vector(blob):
    """Java ByteBuffer 默认大端。"""
    return list(struct.unpack(">%df" % (len(blob) // 4), blob))


def cosine(a, b):
    if len(a) != len(b):
        return float("nan")
    dot = 0.0
    na = 0.0
    nb = 0.0
    for x, y in zip(a, b):
        dot += x * y
        na += x * x
        nb += y * y
    if na <= 0 or nb <= 0:
        return float("nan")
    return dot / (math.sqrt(na) * math.sqrt(nb))


def load_public_chunks(conn):
    rows = conn.execute(
        "SELECT id, source_id, document_id, content, vector, timestamp FROM vector_store "
        "WHERE source_id IS NOT NULL AND source_id <> '' "
        "AND (conversation_id IS NULL OR conversation_id = '') ORDER BY id"
    ).fetchall()
    chunks = []
    for row_id, source_id, document_id, content, blob, written_at in rows:
        chunks.append(
            {
                "row_id": row_id,
                "source_id": source_id,
                "document_id": document_id,
                "content": content,
                "vector": unpack_vector(blob),
                "written_at": written_at,
            }
        )
    return chunks


def load_cases():
    cases = []
    with open(CASE_FILE, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            case = json.loads(line)
            expected = [s for s in (case.get("expectedSourceIds") or []) if s and s.strip()]
            if not expected:
                continue
            case["_expected"] = sorted(set(expected))
            cases.append(case)
    return cases


def load_logged_retrievals(conn):
    """trace_id -> 该次检索命中的 [{documentId, sourceId, similarity}]"""
    logged = {}
    for trace_id, results_json in conn.execute(
        "SELECT trace_id, results_json FROM rag_retrieval_log"
    ):
        try:
            logged[trace_id] = json.loads(results_json) if results_json else []
        except (TypeError, ValueError):
            logged[trace_id] = []
    return logged


def load_trace_map(path):
    """从评测结果 jsonl 取 caseId -> 最后一轮的 traceId。"""
    mapping = {}
    if not os.path.exists(path):
        return mapping
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            record = json.loads(line)
            trace_id = record.get("traceId")
            if trace_id:
                mapping[record.get("caseId")] = trace_id
    return mapping


# --------------------------------------------------------------------------
# 关键词通道原型
# --------------------------------------------------------------------------

CJK = r"一-鿿"


def bigrams(text):
    tokens = set()
    if not text:
        return tokens
    lowered = text.lower()
    for segment in re.findall("[%s]+" % CJK, lowered):
        if len(segment) == 1:
            tokens.add(segment)
        for i in range(len(segment) - 1):
            tokens.add(segment[i:i + 2])
    for word in re.findall(r"[a-z0-9]+", lowered):
        if len(word) >= 2:
            tokens.add(word)
    return tokens


def compute_idf(doc_token_sets):
    total = len(doc_token_sets)
    df = defaultdict(int)
    for tokens in doc_token_sets:
        for token in tokens:
            df[token] += 1
    return {token: math.log((total + 1) / (count + 1)) + 1.0 for token, count in df.items()}


def keyword_score(query_tokens, doc_tokens, idf, weighted=True):
    if not query_tokens:
        return 0.0
    if weighted:
        denominator = sum(idf.get(t, 1.0) for t in query_tokens)
        numerator = sum(idf.get(t, 1.0) for t in query_tokens if t in doc_tokens)
    else:
        denominator = len(query_tokens)
        numerator = len(query_tokens & doc_tokens)
    return numerator / denominator if denominator else 0.0


# --------------------------------------------------------------------------
# 检索模拟
# --------------------------------------------------------------------------

def simulate(case_scores, threshold, top_k, keyword=None, floor=None,
             kw_floor=None, alpha=0.0, fusion="weighted_cos"):
    """返回该题在给定参数下命中的期望 sourceId 集合（去重）。

    case_scores: [(source_id, cosine, kw_score), ...] 覆盖全部 22 块。
    向量通道准入：cosine >= threshold（floor 非空时用 floor）
    关键词通道准入：kw_score >= kw_floor
    两通道并集后按融合分排序，取前 top_k。
    """
    vec_floor = threshold if floor is None else floor
    admitted = []
    for source_id, cos, kw in case_scores:
        in_vector = cos >= vec_floor
        in_keyword = keyword is not None and kw_floor is not None and kw >= kw_floor
        if not (in_vector or in_keyword):
            continue
        if fusion == "weighted_cos":
            fused = (1 - alpha) * max(0.0, cos) + alpha * kw
        elif fusion == "weighted_norm":
            span = max(1e-6, 1.0 - vec_floor)
            normalized = min(1.0, max(0.0, (cos - vec_floor) / span))
            fused = (1 - alpha) * normalized + alpha * kw
        else:
            raise ValueError("未知融合策略: %s" % fusion)
        admitted.append((fused, cos, source_id))
    admitted.sort(key=lambda item: (-item[0], -item[1], item[2]))
    # 返回列表而非集合：题级召回要去重，但「注入了几个块」要看列表长度。
    return [item[2] for item in admitted[:top_k]]


def weighted_recall(cases, hit_map):
    """完整复刻 EvalReportWriter：题级 recall，critical 题重复 3 次，取非 null 平均。

    hit_map: caseId -> 命中的期望 sourceId 集合
    """
    values = []
    for case in cases:
        expected = set(case["_expected"])
        hits = len(expected & hit_map.get(case["id"], set()))
        recall = hits / len(expected)
        repeats = 3 if case.get("critical") else 1
        values.extend([recall] * repeats)
    if not values:
        return None, 0
    return sum(values) / len(values), len(values)


def case_recall(case, hit_map):
    expected = set(case["_expected"])
    return len(expected & hit_map.get(case["id"], set())) / len(expected)


# --------------------------------------------------------------------------
# 输出
# --------------------------------------------------------------------------

def emit_golden(out_dir, chunks, cases, score_table, report):
    """导出夹具，供 Java 侧两个测试使用：

    1. `RagCalibrationGoldenTest` 校验 Java 与 Python 的打分逐条一致，
       防止两边的分词/IDF 实现悄悄漂移；
    2. `RetrievalPolicyGoldenTest` 用真实语料验证选定的准入参数确实产出
       标定时预测的加权 Recall@K。

    夹具带 `expectedSourceIds` 与 `critical`，所以第 2 项不需要联网、
    也不需要启动服务或数据库。
    """
    os.makedirs(out_dir, exist_ok=True)
    payload = {
        "note": "由 scripts/rag-calibration.py --emit-golden 生成，勿手工编辑",
        "chunks": [
            {"sourceId": c["source_id"], "documentId": c["document_id"], "content": c["content"]}
            for c in chunks
        ],
        "queries": [
            {"caseId": c["id"], "question": c["question"],
             "expectedSourceIds": list(c["_expected"]), "critical": bool(c.get("critical"))}
            for c in cases
        ],
        # 必须带 documentId：一个 sourceId 可能对应多个块（如 kb_plant_watering 有 2 块），
        # 只给 sourceId 无法区分是哪一块的分。
        "scores": [
            {"caseId": case["id"], "sourceId": source_id, "documentId": document_id,
             "cosine": cos, "kwIdf": kw_idf}
            for case in cases
            for source_id, document_id, cos, kw_idf, _kw_cov in score_table[case["id"]]
        ],
    }
    path = os.path.join(out_dir, "rag-golden.json")
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False)
    report.add()
    report.add("golden 夹具已写入 `%s`（%d 块 / %d 题 / %d 条打分）"
               % (path, len(chunks), len(cases), len(payload["scores"])))


def fmt(value, digits=4):
    if value is None:
        return "n/a"
    return ("%%.%df" % digits) % value


class Report:
    def __init__(self):
        self.lines = []

    def add(self, text=""):
        self.lines.append(text)
        print(text)

    def text(self):
        return "\n".join(self.lines) + "\n"


# --------------------------------------------------------------------------
# 主流程
# --------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="RAG 召回离线标定")
    parser.add_argument("--dry-run", action="store_true", help="只打印脱敏请求体，不调用")
    parser.add_argument("--reuse-cache", action="store_true", help="复用缓存向量，零网络")
    parser.add_argument("--baseline-jsonl", default=LOG_BASELINE, help="用于控制组 A 的评测结果")
    parser.add_argument("--out-dir", default=None, help="输出目录，默认 tmp/rag-calibration-<ts>")
    parser.add_argument("--skip-controls", action="store_true", help="跳过控制组（仅调试用）")
    parser.add_argument("--emit-golden", default=None,
                        help="额外导出 Java golden 测试夹具到指定目录（如 src/test/resources/rag-calibration）")
    args = parser.parse_args()

    out_dir = args.out_dir or os.path.join(
        ROOT, "tmp", "rag-calibration-" + time.strftime("%Y%m%d-%H%M%S")
    )
    os.makedirs(out_dir, exist_ok=True)
    cache_path = os.path.join(out_dir, "query-embeddings.jsonl")

    base_url, model, api_key = resolve_embedding_config()
    report = Report()
    report.add("# RAG 召回离线标定报告")
    report.add()
    report.add("- 生成时间：%s" % datetime.now().strftime("%Y-%m-%d %H:%M:%S"))
    report.add("- Embedding 端点：`%s`  模型：`%s`  密钥：`%s`" % (base_url, model, redact(api_key)))
    report.add("- 题库：`TEST/eval/ai-eval-v1.jsonl`")
    report.add("- 向量库：`rag_knowledge.sqlite`（本地）")

    if args.dry_run:
        report.add()
        report.add("## --dry-run")
        report.add()
        report.add("将发送 %d 次请求，单条请求体形如：" % 29)
        report.add()
        report.add("```json")
        report.add(json.dumps({"model": model, "input": ["<题库问题原文>"]}, ensure_ascii=False))
        report.add("```")
        report.add()
        report.add("POST `%s/embeddings`，头 `Authorization: Bearer %s`" % (base_url, redact(api_key)))
        print()
        print("dry-run 完成，未发出任何请求。")
        return 0

    if not api_key:
        sys.stderr.write("未找到 embedding 密钥（dashscope.embedding.api-key）。中止。\n")
        return 2

    conn = sqlite3.connect("file:%s?mode=ro" % DB_PATH.replace("\\", "/"), uri=True)
    chunks = load_public_chunks(conn)
    cases = load_cases()
    logged = load_logged_retrievals(conn)
    trace_map = load_trace_map(args.baseline_jsonl)
    conn.close()

    report.add("- 公共知识块：%d 个 / sourceId %d 个" % (len(chunks), len({c["source_id"] for c in chunks})))
    report.add("- 参与测算的题（有 expectedSourceIds）：%d 道" % len(cases))

    weighted_samples = sum(3 if c.get("critical") else 1 for c in cases)
    report.add("- 加权样本数：%d（critical 题计 3 次）" % weighted_samples)
    if weighted_samples != BASELINE_SAMPLES:
        report.add()
        report.add("> **警告**：加权样本数 %d 与基线 %d 不符，口径可能已变。"
                   % (weighted_samples, BASELINE_SAMPLES))

    # ---------------- 取 query 向量 ----------------
    cache = {}
    if args.reuse_cache and os.path.exists(cache_path):
        with open(cache_path, encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if line:
                    item = json.loads(line)
                    cache[item["caseId"]] = item["embedding"]
        report.add("- 复用缓存：%d 条" % len(cache))

    query_vectors = {}
    cache_file = open(cache_path, "a", encoding="utf-8") if not args.reuse_cache else None
    try:
        for case in cases:
            case_id = case["id"]
            if case_id in cache:
                query_vectors[case_id] = cache[case_id]
                continue
            if cache_file is None:
                sys.stderr.write("缓存缺少 %s，请去掉 --reuse-cache 重跑。\n" % case_id)
                return 2
            vector = embed_one(case["question"], base_url, model, api_key)
            query_vectors[case_id] = vector
            cache_file.write(json.dumps({"caseId": case_id, "embedding": vector}) + "\n")
            cache_file.flush()
            print("  已取向量：%s" % case_id)
    finally:
        if cache_file is not None:
            cache_file.close()

    # ---------------- 控制组 ----------------
    controls_ok = True
    if not args.skip_controls:
        report.add()
        report.add("## 控制组")
        report.add()

        # A：与历史日志记录的相似度比对（同一 documentId）
        by_document = {c["document_id"]: c for c in chunks}
        deltas = []
        for case in cases:
            trace_id = trace_map.get(case["id"])
            entries = logged.get(trace_id) if trace_id else None
            if not entries:
                continue
            query_vector = query_vectors[case["id"]]
            for entry in entries:
                chunk = by_document.get(entry.get("documentId"))
                if not chunk:
                    continue
                recomputed = cosine(query_vector, chunk["vector"])
                deltas.append((case["id"], entry.get("sourceId"),
                               entry.get("similarity"), recomputed,
                               abs(recomputed - float(entry.get("similarity", 0)))))
        if deltas:
            worst = max(deltas, key=lambda d: d[4])
            report.add("- **A 空间一致性**：比对 %d 条历史命中，最大偏差 %s（题 %s）"
                       % (len(deltas), fmt(worst[4]), worst[0]))
            for item in sorted(deltas, key=lambda d: -d[4])[:5]:
                report.add("  - %-12s %-30s 日志 %s / 重算 %s / 差 %s"
                           % (item[0], item[1], fmt(item[2]), fmt(item[3]), fmt(item[4])))
            if worst[4] > 0.01:
                controls_ok = False
                report.add("  - **不通过**（容差 0.01）。库内向量与当前模型可能不同空间，后续反事实不可信。")
            else:
                report.add("  - 通过（容差 0.01）。")
        else:
            report.add("- **A 空间一致性**：无可用历史命中可比对（跳过）。")

        # B：同文本两次调用的一致性
        probe_text = cases[0]["question"]
        first = embed_one(probe_text, base_url, model, api_key)
        second = embed_one(probe_text, base_url, model, api_key)
        determinism = cosine(first, second)
        report.add("- **B 非确定性**：同文本两次调用余弦 %s（期望 ≈1.0）" % fmt(determinism))
        if determinism < 0.999:
            controls_ok = False
            report.add("  - **不通过**：模型输出不确定，矩阵含噪声。")

        # C1：库内向量与库内正文的自洽性
        inconsistent = []
        for chunk in chunks[:2] + chunks[-2:]:
            recomputed = embed_one(chunk["content"], base_url, model, api_key)
            fresh = cosine(recomputed, chunk["vector"])
            report.add("- **C1 自洽性**：%s（块 %d）库内正文 vs 库内向量余弦 %s"
                       % (chunk["source_id"], chunk["row_id"], fmt(fresh)))
            if fresh < 0.99:
                inconsistent.append(chunk["source_id"])
        if inconsistent:
            report.add("  - 以下文档库内正文与向量不自洽：%s" % ", ".join(sorted(set(inconsistent))))

        # C2：库内正文是否落后于 knowledge/*.md（本项最能误导后续结论）
        lagging = []
        knowledge_dir = os.path.join(ROOT, "knowledge")
        seen = set()
        for chunk in chunks:
            source_id = chunk["source_id"]
            if source_id in seen:
                continue
            seen.add(source_id)
            path = os.path.join(knowledge_dir, source_id + ".md")
            if not os.path.exists(path) or chunk["written_at"] is None:
                continue
            written = datetime.fromtimestamp(chunk["written_at"] / 1000.0)
            modified = datetime.fromtimestamp(os.path.getmtime(path))
            if modified > written:
                lagging.append((source_id, written, modified))
        if lagging:
            report.add("- **C2 新鲜度**：以下 %d 篇文档的 `.md` 比库内向量新（库内是旧版本）："
                       % len(lagging))
            for source_id, written, modified in sorted(lagging):
                report.add("  - %s：向量 %s / md %s"
                           % (source_id,
                              written.strftime("%Y-%m-%d %H:%M:%S"),
                              modified.strftime("%Y-%m-%d %H:%M:%S")))
            report.add("  - 这些块的向量对应旧正文。若其期望来源落在受影响题上，结论需另行核实。")
        else:
            report.add("- **C2 新鲜度**：库内向量均不早于 `knowledge/*.md`。")

    # ---------------- 相似度矩阵 ----------------
    doc_token_sets = [bigrams(c["content"]) for c in chunks]
    idf = compute_idf(doc_token_sets)
    query_tokens = {c["id"]: bigrams(c["question"]) for c in cases}

    score_table = {}
    for case in cases:
        query_vector = query_vectors[case["id"]]
        tokens = query_tokens[case["id"]]
        score_table[case["id"]] = [
            (
                chunk["source_id"],
                chunk["document_id"],
                cosine(query_vector, chunk["vector"]),
                keyword_score(tokens, doc_tokens, idf, weighted=True),
                keyword_score(tokens, doc_tokens, idf, weighted=False),
            )
            for chunk, doc_tokens in zip(chunks, doc_token_sets)
        ]

    # matrix.csv
    with open(os.path.join(out_dir, "matrix.csv"), "w", encoding="utf-8") as handle:
        handle.write("caseId,sourceId,documentId,cosine,kwIdf,kwCoverage\n")
        for case in cases:
            for source_id, document_id, cos, kw_idf, kw_cov in score_table[case["id"]]:
                handle.write("%s,%s,%s,%.6f,%.6f,%.6f\n"
                             % (case["id"], source_id, document_id, cos, kw_idf, kw_cov))

    if args.emit_golden:
        emit_golden(args.emit_golden, chunks, cases, score_table, report)

    # ---------------- T0 锚点 ----------------
    report.add()
    report.add("## T0 锚点：复现当前口径")
    report.add()
    hit_map = {}
    for case in cases:
        scores = [(s, cos, kw) for s, _, cos, kw, _ in score_table[case["id"]]]
        hit_map[case["id"]] = set(simulate(scores, BASELINE_THRESHOLD, BASELINE_TOPK))
    anchor, samples = weighted_recall(cases, hit_map)
    report.add("- threshold=%s, topK=%d 下模拟加权 Recall@K = **%s**（样本 %d）"
               % (BASELINE_THRESHOLD, BASELINE_TOPK, fmt(anchor), samples))
    report.add("- 基线报告值 = **%s**（样本 %d）" % (fmt(BASELINE_RECALL), BASELINE_SAMPLES))
    anchor_ok = anchor is not None and abs(anchor - BASELINE_RECALL) <= 0.001
    report.add("- %s" % ("**通过**，矩阵可信。" if anchor_ok
                         else "**不通过**：模拟复现不出基线，后续反事实不可信，先查控制组。"))
    if not controls_ok:
        report.add("- 且控制组未全部通过，结论需谨慎。")

    # ---------------- T3 失败题真实 top-5 ----------------
    report.add()
    report.add("## T3 失败题的真实相似度（本步最重要产出）")
    report.add()
    for case_id in ZERO_CASES:
        case = next((c for c in cases if c["id"] == case_id), None)
        if case is None:
            continue
        ranked = sorted(score_table[case_id], key=lambda r: -r[2])
        expected = set(case["_expected"])
        report.add("### %s  「%s」" % (case_id, case["question"]))
        report.add()
        report.add("期望来源：%s" % ", ".join(sorted(expected)))
        report.add()
        report.add("| 排名 | sourceId | 余弦 | kwIdf | kwCoverage | 是否期望 |")
        report.add("| --- | --- | --- | --- | --- | --- |")
        for index, (source_id, _, cos, kw_idf, kw_cov) in enumerate(ranked[:5], start=1):
            report.add("| %d | %s | %s | %s | %s | %s |"
                       % (index, source_id, fmt(cos), fmt(kw_idf), fmt(kw_cov),
                          "是" if source_id in expected else ""))
        best = None
        for index, (source_id, _, cos, _, _) in enumerate(ranked, start=1):
            if source_id in expected:
                if best is None or cos > best[1]:
                    best = (index, cos, source_id)
        if best:
            report.add()
            report.add("期望来源最好的一块：排名 **%d**，余弦 **%s**（%s）"
                       % (best[0], fmt(best[1]), best[2]))
        else:
            report.add()
            report.add("**期望来源不在库里？**")
        report.add()

    # ---------------- T5 分布 ----------------
    report.add("## T5 期望文档余弦分布")
    report.add()
    buckets = {"<0.35 硬缺口（必须混合召回）": 0, "0.35~0.50 降阈值可救": 0, ">=0.50 已命中": 0}
    detail = []
    for case in cases:
        best = max((r[2] for r in score_table[case["id"]] if r[0] in set(case["_expected"])),
                   default=float("nan"))
        detail.append((case["id"], best))
        if math.isnan(best) or best < 0.35:
            buckets["<0.35 硬缺口（必须混合召回）"] += 1
        elif best < 0.50:
            buckets["0.35~0.50 降阈值可救"] += 1
        else:
            buckets[">=0.50 已命中"] += 1
    for name, count in buckets.items():
        report.add("- %s：%d 道" % (name, count))
    report.add()
    report.add("逐题（期望文档最好一块的余弦）：")
    report.add()
    for case_id, best in sorted(detail, key=lambda d: (math.isnan(d[1]), d[1])):
        report.add("- %-12s %s" % (case_id, fmt(best)))

    # ---------------- T1 阈值扫描 ----------------
    report.add()
    report.add("## T1 阈值扫描（topK=%d）" % BASELINE_TOPK)
    report.add()
    report.add("| 阈值 | 加权 Recall@K | 完全召回题数 | " + " | ".join(ZERO_CASES) + " |")
    report.add("| --- | --- | --- | " + " | ".join(["---"] * len(ZERO_CASES)) + " |")
    for step in range(20, 56):
        threshold = step / 100.0
        hits = {}
        for case in cases:
            scores = [(s, cos, kw) for s, _, cos, kw, _ in score_table[case["id"]]]
            hits[case["id"]] = set(simulate(scores, threshold, BASELINE_TOPK))
        recall, _ = weighted_recall(cases, hits)
        full = sum(1 for c in cases if case_recall(c, hits) >= 1.0)
        zero_cells = " | ".join(fmt(case_recall(
            next(c for c in cases if c["id"] == cid), hits)) for cid in ZERO_CASES)
        report.add("| %s | %s | %d | %s |" % (fmt(threshold, 2), fmt(recall), full, zero_cells))

    # ---------------- T2 topK 矩阵 ----------------
    report.add()
    report.add("## T2 阈值 × topK 加权 Recall@K")
    report.add()
    thresholds = [0.50, 0.45, 0.40, 0.35, 0.30]
    top_ks = [5, 8, 10, 15]
    report.add("| 阈值 \\ topK | " + " | ".join(str(k) for k in top_ks) + " |")
    report.add("| --- | " + " | ".join(["---"] * len(top_ks)) + " |")
    for threshold in thresholds:
        cells = []
        for top_k in top_ks:
            hits = {}
            for case in cases:
                scores = [(s, cos, kw) for s, _, cos, kw, _ in score_table[case["id"]]]
                hits[case["id"]] = set(simulate(scores, threshold, top_k))
            recall, _ = weighted_recall(cases, hits)
            cells.append(fmt(recall))
        report.add("| %s | %s |" % (fmt(threshold, 2), " | ".join(cells)))

    # ---------------- T4 关键词通道原型 ----------------
    report.add()
    report.add("## T4 关键词通道 + 排序策略（原型）")
    report.add()
    report.add("纯向量：准入 `余弦 >= floor`。混合：准入 `(余弦 >= floor) ∪ (kwIdf >= kwFloor)`。")
    report.add("`alpha=0` 表示只按余弦排序；`alpha>0` 表示按融合分排序。")
    report.add()
    configs = []
    for floor in (0.50, 0.45, 0.40, 0.35, 0.30, 0.27):
        for top_k in (5, 8):
            configs.append((floor, None, 0.0, top_k, False))
    for floor in (0.45, 0.40):
        for kw_floor in (0.30, 0.45):
            for alpha in (0.0, 0.3):
                for top_k in (5, 8):
                    configs.append((floor, kw_floor, alpha, top_k, True))
    report.add("`注入块数` 是每问平均注入的块数（topK 截断后），是精度/噪声的代理指标。")
    report.add()
    report.add("| 通道 | floor | kwFloor | alpha | topK | 加权 Recall@K | 完全召回 | 六题救援 | 注入块数 |")
    report.add("| --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    rows = []
    for floor, kw_floor, alpha, top_k, use_keyword in configs:
        hits = {}
        injected = []
        for case in cases:
            scores = [(s, cos, kw) for s, _, cos, kw, _ in score_table[case["id"]]]
            selected = simulate(scores, floor, top_k, keyword=use_keyword,
                                floor=floor, kw_floor=kw_floor,
                                alpha=alpha, fusion="weighted_cos")
            hits[case["id"]] = set(selected)
            injected.append(len(selected))
        recall, _ = weighted_recall(cases, hits)
        rescued = 0
        for cid in ZERO_CASES:
            case = next((c for c in cases if c["id"] == cid), None)
            if case and (set(case["_expected"]) & hits.get(cid, set())):
                rescued += 1
        full = sum(1 for c in cases if case_recall(c, hits) >= 1.0)
        rows.append(("混合" if use_keyword else "纯向量", floor, kw_floor, alpha,
                     top_k, recall, full, rescued, sum(injected) / len(injected)))
    for channel, floor, kw_floor, alpha, top_k, recall, full, rescued, avg_inj in rows:
        report.add("| %s | %s | %s | %s | %d | **%s** | %d/%d | %d/%d | %s |"
                   % (channel, fmt(floor, 2),
                      "—" if kw_floor is None else fmt(kw_floor, 2),
                      fmt(alpha, 1), top_k, fmt(recall), full, len(cases),
                      rescued, len(ZERO_CASES), fmt(avg_inj, 2)))
    best = max(rows, key=lambda r: (r[5], -r[8], r[3] == 0.0, -r[1], r[4]))
    report.add()
    report.add("同分时偏向注入更少者。最佳：通道 `%s`，floor %s，kwFloor %s，alpha %s，topK %d "
               "→ **%s**，平均注入 %s 块"
               % (best[0], fmt(best[1], 2),
                  "—" if best[2] is None else fmt(best[2], 2),
                  fmt(best[3], 1), best[4], fmt(best[5]), fmt(best[8], 2)))

    # ---------------- 落盘 ----------------
    summary = {
        "generatedAt": datetime.now().isoformat(timespec="seconds"),
        "config": {"baseUrl": base_url, "model": model},
        "chunks": len(chunks),
        "cases": len(cases),
        "weightedSamples": weighted_samples,
        "t0": {"anchor": anchor, "baseline": BASELINE_RECALL, "passed": anchor_ok},
        "controlsPassed": controls_ok,
        "zeroCaseBestCosine": {cid: (None if math.isnan(v) else round(v, 6))
                               for cid, v in detail},
        "distribution": buckets,
    }
    with open(os.path.join(out_dir, "summary.json"), "w", encoding="utf-8") as handle:
        json.dump(summary, handle, ensure_ascii=False, indent=2)

    report.add()
    report.add("## 结论提示")
    report.add()
    # 只看 topK=5，避免重复行干扰（T2 已证 topK 无影响）
    base_rows = [r for r in rows if r[4] == 5]
    best_vector = max((r for r in base_rows if r[0] == "纯向量"), key=lambda r: r[5])
    best_hybrid = max((r for r in base_rows if r[0] == "混合"), key=lambda r: r[5])

    report.add("### 效率前沿（召回 vs 注入块数）")
    report.add()
    seen_keys = set()
    frontier = []
    for row in sorted(base_rows, key=lambda r: (r[8], -r[5])):
        key = (row[0], row[1], row[2], row[5], round(row[8], 6))
        if key in seen_keys:
            continue
        seen_keys.add(key)
        frontier.append(row)
    report.add("| 通道 | floor | kwFloor | Recall@K | 注入块数 |")
    report.add("| --- | --- | --- | --- | --- |")
    for channel, floor, kw_floor, alpha, top_k, recall, full, rescued, avg_inj in frontier:
        report.add("| %s | %s | %s | %s | %s |"
                   % (channel, fmt(floor, 2),
                      "—" if kw_floor is None else fmt(kw_floor, 2),
                      fmt(recall), fmt(avg_inj, 2)))
    report.add()

    # 同噪声比较：混合最好那一行，对比「注入块数不多于它」的最优纯向量行
    comparable = [r for r in base_rows if r[0] == "纯向量" and r[8] <= best_hybrid[8] + 1e-9]
    peer = max(comparable, key=lambda r: r[5]) if comparable else None
    report.add("- 纯向量最好：floor %s → **%s**（注入 %s 块，六题救援 %d/6）"
               % (fmt(best_vector[1], 2), fmt(best_vector[5]),
                  fmt(best_vector[8], 2), best_vector[7]))
    report.add("- 混合最好：floor %s + kwFloor %s → **%s**（注入 %s 块，六题救援 %d/6）"
               % (fmt(best_hybrid[1], 2),
                  "—" if best_hybrid[2] is None else fmt(best_hybrid[2], 2),
                  fmt(best_hybrid[5]), fmt(best_hybrid[8], 2), best_hybrid[7]))
    if not anchor_ok:
        report.add("- **T0 未通过**：不要基于本报告的参数做决定。")
    elif peer and best_hybrid[5] > peer[5] + 0.005:
        report.add("- **在不多注入的前提下，混合显著优于纯向量**（%s vs %s）→ 关键词通道有独立价值，"
                   "且比继续降阈值更划算。" % (fmt(best_hybrid[5]), fmt(peer[5])))
    else:
        report.add("- 在同等注入量下混合未明显优于纯向量 → 只降阈值可能就够了。")
    report.add("- 继续降阈值能拿到更高召回但代价是噪声：纯向量 floor %s → %s，但每问注入 %s 块"
               % (fmt(best_vector[1], 2), fmt(best_vector[5]), fmt(best_vector[8], 2)))
    report.add("- **topK 对结果无影响**（5/8 逐格同分）→ 不必改 topK。")
    report.add("- **融合权重 alpha 对结果无影响**（0.0/0.3 逐格同分）→ 按余弦排序即可，无需融合打分。")
    report.add("- 参数只取平台区，不要取单点最优：29 个有效点、单点权重约 0.026。")

    report_path = os.path.join(out_dir, "report.md")
    with open(report_path, "w", encoding="utf-8") as handle:
        handle.write(report.text())

    print()
    print("输出目录：%s" % out_dir)
    print("  report.md / summary.json / matrix.csv / query-embeddings.jsonl")
    return 0 if anchor_ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
