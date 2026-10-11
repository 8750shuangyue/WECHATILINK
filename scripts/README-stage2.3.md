# 阶段 2.3 运行说明：真实知识导入与 40 题复测

> 本文档是 `阶段2.3执行方案` 的操作手册，覆盖知识导入、种子清理、受控观测开启和 40 题复测全流程。

## 前置条件

1. **JDK 21** 已安装，`java -version` 输出 21。
2. **MySQL** 已启动，`application.properties` 中数据库连接配置正确。
3. **API 密钥**已配置（`SPRING_AI_OPENAI_API_KEY` 或等价环境变量），Embedding 模型可用。
4. 被测服务已用生产 JAR 方式启动（不要用 `mvn spring-boot:run`，DevTools restart 会导致工具类转换失败）：

   ```powershell
   mvn -B clean package -DskipTests
   java -jar target/demo-0.0.1-SNAPSHOT.jar
   ```

5. 19 篇知识文档已就位于 `knowledge/` 目录。
6. `TEST/eval/ai-eval-v1.jsonl` 的 `expectedSourceIds` 已映射到真实 `sourceId`。

## 第一步：导入真实知识并清理种子

### 1.1 DryRun 校验（不写库）

```powershell
.\scripts\import-knowledge-stage2.3.ps1 `
    -BaseUrl http://localhost:8080 `
    -UserName admin `
    -Password "your-password" `
    -DryRunOnly
```

核对每篇文档的 `chunks` 在 1–3 之间。若某篇 chunks=0 或 chunks>5，检查文档内容是否过长或过短，必要时人工拆分段落。

### 1.2 正式入库 + 删除种子

```powershell
.\scripts\import-knowledge-stage2.3.ps1 `
    -BaseUrl http://localhost:8080 `
    -UserName admin `
    -Password "your-password"
```

脚本自动完成：
1. 登录并保存会话
2. 逐篇 dryRun 校验（失败则中止）
3. 逐篇正式入库（按 `sourceId` 整体替换，可安全重跑）
4. 删除测试种子 `post_1`、`post_2`
5. 列出公共知识库验证：应只剩 19 个 `kb_*` 来源，无 `post_N`

若种子已不存在，删除会报 NOT FOUND（黄色），不影响流程。

### 1.3 验证公共向量数量

脚本末尾输出 `Total public vectors`。预期值 = 19 篇文档块数之和（dryRun 阶段记录的各篇 chunks 相加）。若不一致，检查是否有导入失败或残留种子。

## 第二步：开启受控观测

在服务端设置以下环境变量后重启服务：

```powershell
$env:APP_RAG_OBSERVABILITY_ENABLED = "true"
$env:APP_RAG_OBSERVABILITY_ALLOWED_USERS = "eval-user"
java -jar target/demo-0.0.1-SNAPSHOT.jar
```

- `APP_RAG_OBSERVABILITY_ENABLED=true`：开启 `GET /api/internal/rag/retrievals/{traceId}` 接口。
- `APP_RAG_OBSERVABILITY_ALLOWED_USERS`：逗号分隔的白名单用户名，只有白名单用户可查询召回明细。
- 评测账号必须在白名单中，否则 `sourceRecall` 为空。

## 第三步：12 题冒烟测试

```powershell
$env:AI_EVAL_ENABLED = "true"
$env:AI_EVAL_BASE_URL = "http://localhost:8080"
$env:AI_EVAL_ORIGIN = "http://localhost:8080"
$env:AI_EVAL_USERNAME = "eval-user"
$env:AI_EVAL_PASSWORD = "replace-in-local-environment"
$env:AI_EVAL_CASE_FILE = "eval/ai-eval-smoke-12.jsonl"
$env:AI_EVAL_MAX_CASES = "12"
$env:AI_EVAL_MAX_TOKENS = "50000"
mvn -B -f TEST\pom.xml test
```

确认：
- 请求记录 = 18（12 题 × 1 轮 + 6 题无工具调用差异）
- 基础设施失败 = 0
- 工具选择准确率 = 100%

冒烟题的短语同义词覆盖缺口（如 plant-001、plant-002）不作为正式验收失败。

## 第四步：40 题正式复测

```powershell
$env:AI_EVAL_ENABLED = "true"
$env:AI_EVAL_BASE_URL = "http://localhost:8080"
$env:AI_EVAL_ORIGIN = "http://localhost:8080"
$env:AI_EVAL_USERNAME = "eval-user"
$env:AI_EVAL_PASSWORD = "replace-in-local-environment"
$env:AI_EVAL_CASE_FILE = "eval/ai-eval-v1.jsonl"
$env:AI_EVAL_MAX_CASES = "40"
$env:AI_EVAL_MAX_TOKENS = "200000"
mvn -B -f TEST\pom.xml test
```

### 输出产物

运行结束后在 `TEST/eval/results/` 生成：

| 文件 | 说明 |
|------|------|
| `baseline-YYYYMMDD-HHmmss.jsonl` | 逐请求原始结果（含 traceId、retrieval 记录） |
| `baseline-YYYYMMDD-HHmmss.md` | 汇总报告（含 sourceRecall / Recall@K） |
| `failures-YYYYMMDD-HHmmss.md` | 失败和需人工复核清单 |

### 关键指标

| 指标 | 说明 | 验收标准 |
|------|------|----------|
| 质量通过率 | expectedFacts + forbiddenClaims 自动评分 | ≥ 98% |
| 关键事实覆盖率 | expectedFacts 命中率 | ≥ 99% |
| 工具选择准确率 | expectedTools/forbiddenTools | = 100% |
| 工具执行成功率 | 实际工具调用成功比例 | = 100% |
| 拒答边界得分 | shouldRefuse=true 的拒答率 | = 100% |
| 多轮记忆最终轮 | multiTurn 题最终轮正确 | 4/4 |
| **sourceRecall (Recall@K)** | expectedSourceIds 命中比例 | 首次产出基线值 |

`sourceRecall` 计算逻辑（`EvalScorer.java:116`）：
- 仅对 `expectedSourceIds` 非空的题计算
- 通过 `rag-trace` SSE 事件获取 `traceId`
- 调用 `GET /api/internal/rag/retrievals/{traceId}` 获取召回的 `sourceId` 列表
- `Recall@K = |expectedSourceIds ∩ retrievedSourceIds| / |expectedSourceIds|`
- `path: tools` 的题 `retrievalStatus` 恒为 `not_applicable`，不计入召回

## 第五步：离线重算（可选）

评分规则调整后，无需重新调用模型：

```powershell
$env:AI_EVAL_REPLAY_FILE = "eval/results/baseline-YYYYMMDD-HHmmss.jsonl"
$env:AI_EVAL_REPLAY_CASE_FILE = "eval/ai-eval-v1.jsonl"
mvn -B -f TEST\pom.xml "-Dtest=EvalReportRegeneratorTest" test
```

离线模式重新计算自动评分并生成新报告，不调用被测服务。建议保留原始 JSONL 用于规则迭代。

## 配置版本记录

复测时记录以下配置版本，确保可复现：

| 配置项 | 值 |
|--------|-----|
| topK | 5 |
| similarity-threshold | 0.5 |
| CHUNK_LENGTH | 500 |
| Embedding 模型 | （记录实际使用的模型） |
| 题库文件 | `ai-eval-v1.jsonl`（记录文件哈希） |
| rag-observability.enabled | true |
| rag-observability.allowed-users | eval-user |

## 验收检查清单

- [ ] 公共知识库只剩 19 个 `kb_*` 来源，无 `post_1`、`post_2`
- [ ] 公共向量数量 = 各文档块数之和
- [ ] `expectedSourceIds` 已映射到真实来源（`path: stream` 题不再全为空数组）
- [ ] `sourceRecall` 有非空数值产出（非 null）
- [ ] 质量通过率 ≥ 98%，事实覆盖 ≥ 99%，工具成功率 = 100%
- [ ] 多轮记忆 4/4
- [ ] 评测报告无「生产接口未返回内部检索明细」过时表述
- [ ] 原始 JSONL 已保存，可离线重算