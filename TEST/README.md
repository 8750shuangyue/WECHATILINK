# AI 评测独立测试工程

该目录是一个独立的 Maven 黑盒测试工程，不挂入生产项目的 `pom.xml`，也不会进入生产 JAR。

## 设计边界

- 通过真实 HTTP 调用登录、普通流式聊天和工具模式接口。
- 默认不执行真实评测，避免误调用外部 API 和产生费用。
- 使用独立评测账号、独立数据库和只读工具。
- 不记录 Cookie、密码、API Key、Authorization 或真实用户隐私。
- 阶段 2.2 起，普通流式聊天会推送 SSE `rag-trace` 事件，评测侧可据此调用受控只读接口 `/api/internal/rag/retrievals/{traceId}` 取回该次问答命中的 `sourceId`；当题库 `expectedSourceIds` 非空且该次检索状态为 `available` 时，`sourceRecall` 可计算，否则保持为空。

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

离线模式会重新计算自动评分，并就地覆盖同名 JSONL、Markdown 和失败清单，不会调用被测服务。输出文件名沿用原始运行的 `baseline-YYYYMMDD-HHmmss` 时间戳，因此建议先把原始 JSONL 备份到 `eval/archive/` 或 `tmp/`，再用重算结果做规则迭代。

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
- RAG 来源召回：评测通过 SSE `rag-trace` 事件取 `traceId`，再查受控只读接口 `/api/internal/rag/retrievals/{traceId}` 得到命中来源；`sourceRecall` 仅在 `expectedSourceIds` 非空且该次检索状态为 `available` 时计算，取值等于命中的期望来源数除以期望来源数，`path: tools` 的用例不参与。

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

### 阶段 0.1 至 0.5 本地门禁与真实复测（2026-10-07）

P1 与阶段 0.1 至 0.5 已完成 RAG 用户 / 会话隔离、检索审计日志、召回指标数据基础、MySQL 对话归属隔离、对话向量去重、完整清空会话、摘要 / system 提示词统一和入口记忆契约固定。阶段 0.5 已推送到 `main` 提交 `77360f5`，GitHub Actions 构建成功。

当前本地门禁结果：生产项目全量测试 `64/64` 通过；独立 `TEST` 工程默认模式通过 `6` 个评分测试，`2` 个真实评测用例因 `AI_EVAL_ENABLED` 未设置而按预期跳过，构建成功。

随后在 `main` 提交 `8ac56f0` 的本地构建上启用了独立评测账号和真实 API 环境，完成 12 条冒烟题和 40 条正式题库复测。首轮使用 `mvn spring-boot:run` 时，DevTools restart classloader 导致天气工具类转换失败；改用最新代码构建的 `java -jar target/demo-0.0.1-SNAPSHOT.jar` 后，工具选择准确率和工具执行成功率均恢复为 `100.00%`。该问题属于本地 DevTools 运行方式，不是生产 JAR 业务回归。

12 条冒烟题结果：

- 冒烟运行：`eval/results/baseline-20261007-160105.md`
- 请求记录：18
- 质量评分样本：16
- 质量通过：14
- 质量通过率：87.50%
- 工具选择准确率：100.00%
- 工具执行成功率：100.00%
- 拒答边界得分：100.00%
- 基础设施失败：0

两条冒烟失败均为植物养护题的短语同义词覆盖缺口：`plant-001` 的回答包含“浇水问题”“通风”“散射光”等正确内容，`plant-002` 的回答包含“立即停水”“晾根”等正确内容，但自动规则没有覆盖对应表达。冒烟仅用于链路检查，上述两项没有作为正式验收失败处理。

40 条正式题库结果：

- 正式运行：`eval/results/baseline-20261007-161309.md`
- 失败清单：`eval/results/failures-20261007-161309.md`
- 请求记录：56
- 质量评分样本：52
- 质量通过：51
- 质量通过率：98.08%
- 关键事实覆盖率：100.00%
- 工具选择准确率：100.00%
- 工具执行成功率：100.00%
- 拒答边界得分：100.00%
- 基础设施失败：0
- 多轮记忆最终轮：4/4
- `tool-006`：3/3
- 总延迟均值：19.86 秒
- 总延迟 P95：64.13 秒

正式运行的唯一自动失败是 `pet-007` 的禁用短语误判：回答明确写有“不建议等到明天”，但仍被规则命中“等到明天”。该回答事实分为 `1.0000`，内容判断为不安全等待，不属于业务错误。

后续验收顺序：

1. 备份生产 JAR、配置和数据库，并按可回滚流程部署阶段 0.1 至 0.5。
2. 上线后验证 HTTPS、登录、普通聊天、工具模式、会话记忆和知识库检索。

### 阶段 2.3 真实知识导入与召回复测（2026-10-13，本地）

2026-10-13 在本地 MySQL 与 `main` 提交 `493447d` 的构建上完成阶段 2.3 真实知识导入：通过受控导入接口导入 20 篇知识文档共 22 个向量块（`DOCS=20`、`VECTORS=22`，无测试种子残留），并按导入清单把题库 29 行 `expectedSourceIds` 换成真实 `sourceId`。评测侧同时补齐来源召回取数口径、报告汇总行和失败清单明细；离线重算会直接覆盖原运行文件名，不再产生同名副本。

- 冒烟运行：`eval/results/baseline-20261013-191647.md`
- 正式运行：`eval/results/baseline-20261013-191950.md`
- 失败清单：`eval/results/failures-20261013-191950.md`
- 请求记录：56
- 质量评分样本：52
- 质量通过：49
- 质量通过率：94.23%
- 关键事实覆盖率：98.08%
- 工具选择准确率：100.00%
- 工具执行成功率：100.00%
- 拒答边界得分：100.00%
- RAG 来源召回率 (Recall@K)：65.38%（39 样本）
- 禁用结论命中数：1 / 52
- 基础设施失败：0
- 总延迟均值：13.53 秒
- 总延迟 P95：49.70 秒

三条自动失败中，`plant-001`、`plant-002` 为关键事实覆盖不足，`plant-005` 为禁用结论命中；12 条冒烟题因 `expectedSourceIds` 为空，该行的召回率显示为 `N/A`，仅作链路检查。来源召回为 `0` 的样本集中在 `plant-001`、`plant-003`、`plant-010`、`pet-007`、`pet-008`、`refusal-002`，需要人工复核是检索未命中还是期望来源标注过窄。本地导入只作用于本地库，生产环境已另行重做，见下一节。

### 阶段 2.3 真实知识导入与召回复测（2026-10-13，生产）

2026-10-13 在生产 `https://sekaipetplant.com` 上按同一顺序重做。生产 JAR 为 `1d12cb9`，已含导入与观测接口，未重新部署。发布前公共知识库为 `post_1`、`post_2` 两个测试种子，对话向量 `126`。

- 冒烟运行：`eval/results/baseline-20261013-202753.md`
- 正式运行：`eval/results/baseline-20261013-203432.md`
- 失败清单：`eval/results/failures-20261013-203432.md`
- 请求记录：56
- 质量评分样本：52
- 质量通过：51
- 质量通过率：98.08%
- 关键事实覆盖率：100.00%
- 工具选择准确率：100.00%
- 工具执行成功率：100.00%
- 拒答边界得分：100.00%
- 多轮记忆最终轮：4/4
- RAG 来源召回率 (Recall@K)：65.38%（39 样本）
- 禁用结论命中数：1 / 52
- 基础设施失败：0
- 总延迟均值：12.80 秒
- 总延迟 P95：45.22 秒

导入侧：20 篇逐篇 `dryRun` 后正式入库，`sourceId` 回显、块数与字数与 `knowledge/README.md` 第一节表格逐项吻合；删除测试种子后 `/api/kb/list` 为 `DOCS=20`、`VECTORS=22`。评测账号为专建的 `codexeval_stage23`，评测结束后已连同其会话、消息、工具日志和对话向量删除；`APP_RAG_OBSERVABILITY_ENABLED` 已还原为关闭。

生产唯一自动失败为 `pet-006`（命中禁用结论）。该题事实分为 `1.0000`，回答明确写出「没有所有狗都适用的固定频率，需要个体化评估，并在兽医指导下制定方案」，内容判断为安全；其小标题原样复述了提问中的「为什么不能照搬别人的剂量」，属于短语规则命中提问措辞的假阳性，与冒烟运行中记录过的 `pet-007`、`plant-002` 同类。

零召回样本为 `plant-001`、`plant-003`、`plant-010`、`pet-007`、`pet-008`、`refusal-002`，与本地运行完全相同；其中 `pet-007`、`pet-008`、`refusal-002` 为 `critical` 题各执行三次，九次全部为 `0`。可复现说明该现象是系统性的，人工复核应按「检索未命中」或「期望来源标注过窄」两个方向排查，而不是当作偶发抖动。

> 生产与本地用同一份知识库、同一份题库，质量通过率相差 3 条（98.08% 对 94.23%），失败用例集合不同。温度 `0.3` 下模型输出本身有波动，单次跑分不宜过度解读，比较时应以多次运行的稳定区间为准。

> 生产导入后，`knowledge/kb_pet_bite_wound.md` 与 `knowledge/kb_plant_soil_iron.md` 又做了正文口径修正，并已于同日重新导入对齐，生产与仓库当前内容一致。**本节评测指标为改稿前的口径**，见 `knowledge/README.md` 第十节。
