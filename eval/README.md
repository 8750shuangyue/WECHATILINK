# Sekai PetPlant AI 评测运行说明

> 本目录是「AI 评测测试搭建需求单」的落地实现，独立于生产代码运行，仅通过 `-Pai-eval` Maven Profile 触发。

## 一、目录结构

```text
eval/
├── ai-eval-smoke-12.jsonl   # 12 条冒烟用例（先验证运行器本身）
├── ai-eval-v1.jsonl          # 40 条正式基线用例（第一版指标基线）
├── schema.json               # JSONL 单条用例字段规范（JSON Schema）
├── README.md                 # 本文
└── results/                  # 运行结果输出目录
    ├── baseline-*.jsonl      # 原始逐条结果（不覆盖历史批次）
    ├── baseline-*.md         # Markdown 汇总报告
    └── failures-*.md         # Top 失败问题清单
```

评测 Java 代码位于 `src/test/java/com/example/demo/eval/`：

| 文件 | 职责 |
| --- | --- |
| `AiEvalRunnerTest.java` | 运行入口：启动真实应用、登录、逐条执行、评分、写报告 |
| `EvalCase.java` | 单条用例模型（与 JSONL 字段一一对应） |
| `EvalResult.java` | 单条结果模型（含自动/人工评分、工具、检索记录） |
| `EvalScorer.java` | 自动评分：关键事实、禁用结论、工具选择、召回、拒答 |
| `EvalHttpClient.java` | 真实 HTTP 客户端：登录、SSE 流式、工具模式、Cookie/Origin 处理 |
| `EvalReportWriter.java` | 结果落盘与 Markdown 报告生成 |

## 二、前置条件

- JDK 21、Maven 3.9+（项目自带 `mvnw`）
- MySQL 8.x，且已创建**独立评测数据库**（如 `ilink_eval`，不要使用正式库）
- 各 API Key 通过**环境变量**注入，不写入任何仓库文件
- 命令行工具 ffmpeg（可选，仅语音输入评测需要，本版不测）

## 三、集成步骤（将本包放入 WECHATILINK 仓库）

1. 把 `src/test/java/com/example/demo/eval/` 复制到项目 `src/test/java/com/example/demo/eval/`；
2. 把 `eval/` 目录复制到仓库根目录（与 `pom.xml` 同级）；
3. 在 `pom.xml` 增加 `ai-eval` Profile（见顶层 `README.md`，或按需求单第四节）；
4. 创建评测数据库与评测账号，**不得**使用正式用户数据。

## 四、环境准备

### 4.1 创建评测数据库

```sql
CREATE DATABASE ilink_eval DEFAULT CHARACTER SET utf8mb4;
```

运行评测时用 `-Dspring.datasource.url` 指向评测库。JPA `ddl-auto=update` 会自动建表。

### 4.2 创建评测账号

在评测库中注册/创建专用账号（如 `eval_runner`），并通过如下方式注入：

```powershell
$env:AI_EVAL_USERNAME = "eval_runner"
$env:AI_EVAL_PASSWORD = "你的评测账号密码"
```

测试人员各自使用自己的凭证，禁止共用。

### 4.3 注入 API Key（环境变量）

```powershell
$env:DASHSCOPE_API_KEY = "sk-..."
$env:DASHSCOPE_EMBEDDING_API_KEY = "sk-..."
$env:SENIVERSE_API_KEY = "..."          # 植物安全/行业知识（按项目实际配置项）
$env:AMAP_API_KEY = "..."               # 高德地图附近服务
$env:BAIDU_SEARCH_API_KEY = "..."       # 联网搜索
$env:MYSQL_PASSWORD = "评测库密码"
```

> 禁止把 Key 写入脚本/文档/JSONL 结果；本运行器不输出 Authorization 与 Cookie。

## 五、运行命令

### 5.1 冒烟评测（先跑通运行器）

```powershell
mvn -Pai-eval -Dai.eval.enabled=true `
  -Dai.eval.username=$env:AI_EVAL_USERNAME `
  -Dai.eval.password=$env:AI_EVAL_PASSWORD `
  -Dai.eval.case-file=eval/ai-eval-smoke-12.jsonl `
  "-Dspring.datasource.url=jdbc:mysql://localhost:3306/ilink_eval?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai" `
  test
```

### 5.2 正式基线评测

```powershell
mvn -Pai-eval -Dai.eval.enabled=true `
  -Dai.eval.username=$env:AI_EVAL_USERNAME `
  -Dai.eval.password=$env:AI_EVAL_PASSWORD `
  -Dai.eval.case-file=eval/ai-eval-v1.jsonl `
  -Dai.eval.output=eval/results `
  "-Dspring.datasource.url=jdbc:mysql://localhost:3306/ilink_eval?..." `
  test
```

### 5.3 可调参数

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `ai.eval.enabled` | 无 | 必须为 `true` 才触发评测；普通 `mvn test` 不触发 |
| `ai.eval.case-file` | `eval/ai-eval-v1.jsonl` | 题库路径 |
| `ai.eval.output` | `eval/results` | 结果输出目录 |
| `ai.eval.username` / `ai.eval.password` | `eval_runner` / 空 | 评测账号 |
| `ai.eval.model` | `configured-model` | 记录到报告，需与运行时模型一致 |
| `ai.eval.temperature` | `0.3` | 模型温度（固定以保证可复现） |
| `ai.eval.topK` | `5` | RAG 召回 K |
| `ai.eval.similarity-threshold` | `0.5` | RAG 相似度阈值 |
| `ai.eval.cost-per-1k-tokens` | `0.002` | 估算成本单价（元/1k token） |

## 六、结果输出

每次运行生成（不覆盖历史批次）：

- `baseline-YYYYMMDD-HHmmss.jsonl`：逐条原始结果（含耗时、Token、工具调用、召回、自动评分）；
- `baseline-YYYYMMDD-HHmmss.md`：汇总报告（运行信息、环境参数、总体/分类指标、延迟 P95、Token 成本、Top 10 失败、人工复核占位、与上版对比占位）；
- `failures-YYYYMMDD-HHmmss.md`：失败问题清单。

## 七、题库维护指南

1. 新增/修改题目：遵循 `schema.json` 字段规范，`id` 全局唯一；
2. **RAG 题**的 `expectedSourceIds` 是占位 ID（如 `kb-doc:plant-winter-care`），首次运行前必须：
   - 在知识库 `/kb` 上传对应文档；
   - 查询该文档实际 `sourceId`，回填到题库后重跑；
3. **工具题**只允许使用只读工具（天气/附近服务/搜索/植物安全/宠物护理/症状分级），禁止写类工具；
4. 多轮用例 `question` 为第一轮，`multiTurn` 为后续轮次；
5. 修改题目后递增 `version`。

## 八、已知适配点（第一版如实记录，不回避）

运行器按通用约束实现，首次跑通后需按真实接口微调以下三点：

1. **登录接口**：默认按 `POST /api/auth/login` + JSON `{username,password}` + Set-Cookie 会话实现；若实际为表单登录或返回 Token，需在 `EvalHttpClient.tryLogin()` 适配；
2. **SSE 内容字段**：默认提取 `data:` 帧中 `content/text/delta` 字段并拼接；若流式帧结构不同，在 `appendPayload()` 适配；
3. **工具响应结构**：`/api/ai/chat-with-tools` 的工具名默认识别 `name/toolName/tool` 字段；若实际结构不同，在 `extract()` 适配，并将结果如实记录为 `parseFailed`。

以上差异会体现在报告的"基线能力缺口"中，不会通过 Sql 或绕过方式掩盖。

## 九、成本与重试保护

- 单次最大用例数 `100`、最大 Token 预算 `500k`，超限自动停止并生成部分报告；
- 默认串行执行；网络异常最多重试 2 次，模型输出质量差**不重试**；
- 关键用例（`critical=true`）自动执行 3 次并逐次记录，用于稳定性分析。

## 十、验收清单（对照需求单第十四/十九章）

- [ ] 普通 `mvn test` 不触发外部 API；
- [ ] `mvn -Pai-eval` 能独立启动评测；
- [ ] 能完成登录、Session 保持、真实 HTTP 调用；
- [ ] 能正确拼接 SSE 完整回答并记录首字延迟；
- [ ] 能解析工具响应中的工具调用明细；
- [ ] RAG 题能记录来源 ID 与相似度；
- [ ] 冒烟 12 条、正式 40 条全部执行；
- [ ] 生成原始 JSONL、Markdown 报告、失败清单；
- [ ] 关键用例三次重复执行；
- [ ] 已核对题库中 RAG `expectedSourceIds` 与知识库实档一致；
- [ ] 未提交任何密钥、Cookie 与真实用户数据。