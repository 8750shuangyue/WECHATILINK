# AI 评测独立测试工程

该目录是一个独立的 Maven 黑盒测试工程，不挂入生产项目的 `pom.xml`，也不会进入生产 JAR。

## 设计边界

- 通过真实 HTTP 调用登录、普通流式聊天和工具模式接口。
- 默认不执行真实评测，避免误调用外部 API 和产生费用。
- 使用独立评测账号、独立数据库和只读工具。
- 不记录 Cookie、密码、API Key、Authorization 或真实用户隐私。
- P1 已增加内部检索审计数据，但尚未提供受控只读 HTTP 明细接口；外部黑盒评测仍无法获取内部 RAG 召回明细，因此 `sourceRecall` 会保持为空，并在报告中标记为能力缺口。

## 运行前准备

1. 启动被测服务，并准备独立评测账号。
2. 使用 PowerShell 设置环境变量，不要把真实密钥写入仓库。
3. 确认题库中的 RAG `expectedSourceIds` 已替换为真实知识来源 ID。
4. 先用 12 条冒烟题跑通，再执行正式 40 条基线题。
5. 确认启动服务时使用的模型、温度、`topK`、相似度阈值和 Embedding 模型与环境变量记录一致。

PowerShell 示例：

```powershell
$env:AI_EVAL_ENABLED = "true"
$env:AI_EVAL_BASE_URL = "http://localhost:8080"
$env:AI_EVAL_ORIGIN = "http://localhost:8080"
$env:AI_EVAL_USERNAME = "eval-user"
$env:AI_EVAL_PASSWORD = "replace-in-local-environment"
$env:AI_EVAL_CASE_FILE = "eval/ai-eval-smoke-12.jsonl"
$env:AI_EVAL_MAX_CASES = "12"
$env:AI_EVAL_MAX_TOKENS = "50000"
```

## 执行命令

在仓库根目录运行：

```powershell
mvn -B -f TEST\pom.xml test
```

以上命令默认不会调用真实 API。显式开启后才执行真实评测：

```powershell
$env:AI_EVAL_ENABLED = "true"
$env:AI_EVAL_BASE_URL = "http://localhost:8080"
$env:AI_EVAL_ORIGIN = "http://localhost:8080"
$env:AI_EVAL_USERNAME = "eval-user"
$env:AI_EVAL_PASSWORD = "replace-in-local-environment"
mvn -B -f TEST\pom.xml test
```

执行正式基线：

```powershell
$env:AI_EVAL_ENABLED = "true"
$env:AI_EVAL_CASE_FILE = "eval/ai-eval-v1.jsonl"
$env:AI_EVAL_MAX_CASES = "40"
$env:AI_EVAL_MAX_TOKENS = "200000"
mvn -B -f TEST\pom.xml test
```

## 离线重算报告

评分规则或报告口径调整后，可以直接读取历史原始 JSONL 离线重算，不需要重新调用模型：

```powershell
$env:AI_EVAL_REPLAY_FILE = "eval/results/baseline-YYYYMMDD-HHmmss.jsonl"
$env:AI_EVAL_REPLAY_CASE_FILE = "eval/ai-eval-v1.jsonl"
mvn -B -f TEST\pom.xml "-Dtest=EvalReportRegeneratorTest" test
```

离线模式会重新计算自动评分并生成新的 JSONL、Markdown 和失败清单，不会调用被测服务。建议先保留原始 JSONL，再用重算结果做规则迭代。

`TEST` 工程不会启动 Spring Boot，也不会自动创建数据库。被测服务必须由执行人员单独启动；评测只通过 HTTP 访问它。

## 输出

运行结束后会在 `eval/results/` 生成：

- `baseline-YYYYMMDD-HHmmss.jsonl`：逐请求原始结果。
- `baseline-YYYYMMDD-HHmmss.md`：汇总报告。
- `failures-YYYYMMDD-HHmmss.md`：失败和需人工复核清单。

结果目录中的运行产物默认不提交。

## 评分说明

`expectedFacts` 和 `forbiddenClaims` 使用规范化后的短语匹配。单个短语可以使用 `|` 表示同义表达，例如：

```text
立即联系兽医|尽快就医|急诊
```

这不是语义判断，只是稳定的基线自动评分。所有关键用例和失败用例仍必须人工复核。

当前评分口径：

- `forbiddenClaims` 会识别常见否定和转折语境，但仍属于短语规则，不能替代人工判断。
- `toolSelectionScore` 只统计声明了 `expectedTools` 或 `forbiddenTools` 的用例。
- `refusalScore` 只统计 `shouldRefuse=true` 的用例。
- 工具执行成功率只统计实际发生工具调用的记录。
- 公开 HTTP 接口暂未返回内部 RAG 召回明细；虽然 P1 已落库检索审计数据，但黑盒评测在只读明细接口接入前仍保持 `sourceRecall` 为空，不计算 `Recall@K`。

`critical=true` 的用例会执行三次。达到 `AI_EVAL_MAX_TOKENS` 预算时会停止后续执行，但仍会生成已经完成部分的结果和报告。

## 基线记录

### 第一版基线（2026-10-01）

2026-10-01 使用正式 40 条题库完成第一版基线，原始结果与重算报告位于 `eval/results/`：

- 请求记录：56
- 质量评分样本：52
- 质量通过：47
- 质量通过率：90.38%
- 关键事实覆盖率：93.27%
- 工具选择准确率：87.50%
- 拒答边界得分：100.00%
- 多轮记忆最终轮：0/4
- 总延迟均值：21.24 秒
- 总延迟 P95：58.42 秒

当前直接问题为多轮记忆全部失败，以及 `tool-006` 在一次重复运行中没有调用 `triageSymptoms`。RAG 召回和引用仍需受控观测接口或人工依据性复核，当前报告没有伪造 `Recall@K`。

### P0 复测（2026-10-06）

2026-10-06 在当前 `main` 提交 `0e3ab17` 上先执行 12 条冒烟题，再执行同一份 40 条正式题库。模型、温度、`topK`、相似度阈值、Embedding 模型和评分口径均未调整。

- 冒烟运行：`eval/results/baseline-20261006-110334.md`
- 正式运行：`eval/results/baseline-20261006-110917.md`
- 失败清单：`eval/results/failures-20261006-110917.md`
- 请求记录：56
- 质量评分样本：52
- 质量通过：51
- 质量通过率：98.08%
- 关键事实覆盖率：99.04%
- 工具选择准确率：100.00%
- 工具执行成功率：100.00%
- 拒答边界得分：100.00%
- 基础设施失败：0
- 多轮记忆最终轮：4/4
- `tool-006`：3/3
- 总延迟均值：22.08 秒
- 总延迟 P95：63.55 秒

P0 验收项已经满足。唯一自动失败是 `plant-002`，回答包含“停水、通风、晾干”等核心处理，但自动评分的第一个事实组只配置了“停止浇水 / 暂停浇水 / 控水”，因此事实分为 `0.5`。该项为非关键题，属于短语同义词覆盖缺口，不是回答内容错误，本轮没有通过修改题库或评分标准消除。

### P1 检索隔离与审计（2026-10-06，未复测）

P1 已完成 RAG 用户 / 会话隔离、检索审计日志和召回指标数据基础，生产项目全量单元测试 `37/37` 通过。独立 `TEST` 工程默认模式通过 `6` 个评分测试，`2` 个真实评测用例因 `AI_EVAL_ENABLED` 未设置而跳过。

本轮没有执行 P1 后的 12 条冒烟题和 40 条正式题库，原因是当前环境未配置独立评测账号、密码和真实 API 环境变量。外部 HTTP 仍未暴露受控只读的 RAG 召回明细，`sourceRecall` 保持为空，`Recall@K` 继续标记为待接入，不使用审计表内数据猜测或人工填充。

后续验收顺序：

1. 准备独立评测账号并启动被测服务。
2. 执行 12 条冒烟题，确认登录、流式对话、工具模式、多轮记忆和 RAG 隔离没有回归。
3. 执行同一份 40 条正式题库，与 2026-10-06 的 P0 基线对比。
4. 增加受控只读召回明细接口，将 `expectedSourceIds` 映射到真实来源后计算 `Recall@K`。
