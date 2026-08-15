# Sekai PetPlant · AI 宠物/植物护理平台

一个以 **AI Agent 为核心**的一站式宠物/植物智能护理 Web 平台：覆盖养护咨询、AI 识图诊断、护理提醒、商城库存、社区分享、每日简报等全链路场景。

> 本项目已移除微信个人号（ILink）与公众号接入，专注于 Web 端。

## 在线地址

- 演示环境：<http://101.37.254.73:8080>（阿里云 · 生产部署）

## 技术栈

| 分类 | 选型 |
| --- | --- |
| 语言/框架 | Java 21 · Spring Boot 3.5 · Spring MVC · Spring AI |
| 数据层 | MySQL 8（业务数据）· SQLite（RAG 向量知识库）· Spring Data JPA + JDBC |
| 大模型 | DeepSeek V4-Pro（对话）· 阿里云百炼（Embedding / qwen-vl 视觉 / qwen-image 生图） |
| 外部服务 | 讯飞 TTS · 高德地图 · 心知天气 · 百度搜索 · WebPush 浏览器推送 |
| 构建部署 | Maven · GitHub Actions CI · 阿里云 ECS + systemd |

## 核心功能

### 1. AI Agent 智能助手（核心亮点）

- **Agent 编排**：`AgentService` 统一调度，支持多轮对话、意图路由（`MessageRouter`）、上下文记忆注入与工具循环执行，120 秒超时保护
- **工具调用体系**：两套并行
  - 自研 `BaseTool` 抽象：8 个（文件分析、图像分析/编辑/生成、TTS、网页搜索等）
  - Spring AI `@Tool` 方法：68 个（护理问答、健康检查、天气、地图、库存、商城、社区等业务工具）
- **流式对话**：SSE 打字机式输出（`POST /api/ai/chat/stream`）
- **记忆与 RAG**：对话历史持久化 + 语义摘要；文档上传自动分块 → Embedding 向量化 → SQLite 向量库检索增强回答

### 2. 智能护理

- 宠物/植物档案管理、成长记录、护理记录
- AI 识图诊断（拍照识别植物/宠物疾病，`/api/care/identify`）
- 护理提醒：到期自动 WebPush 浏览器推送（`/reminders`）
- 附近宠物医院/服务检索（高德地图）

### 3. 知识库后台

- `/kb` 页面上传文档 → 自动分块 → 向量入库 → 对话时自动检索
- 支持知识条目列表查看与删除

### 4. 业务功能

- 商城与库存管理（商品发布、库存盘点）
- 社区与时间线（发帖、评论、点赞、成长记录）
- 每日简报：每天 8 点自动生成并 WebPush 推送
- 图像生成/编辑、语音合成、天气查询、地图导航、网页搜索

### 5. 账号与安全

- 注册 / 登录 / 登出，Session 会话
- `WebAuthInterceptor` 统一鉴权：`/api/**`、`/uploads/**` 需登录，未登录返回 401
- 数据隔离：宠物/植物档案、护理记录、提醒均按 `user_id` 归属校验

## 项目结构

```text
src/main/java/com/example/demo/
├── agent/            # Agent 编排、工具基类与 8 个 BaseTool
├── ai/               # 对话接口、流式 SSE、Spring AI 工具（68 个 @Tool）
├── care/             # 智能护理：档案、记录、提醒、识图诊断、附近服务
├── chat/             # 对话记忆、RAG 向量库、会话管理
├── kb/               # 知识库后台（上传/分块/向量化）
├── community/        # 社区：帖子、评论、点赞
├── timeline/         # 成长时间线
├── ebusiness/        # 商城
├── inventory/        # 库存管理
├── briefing/         # 每日简报生成与推送
├── push/             # WebPush 浏览器推送
├── auth/             # 注册登录
├── config/           # 鉴权拦截器等配置
├── router/           # 意图路由
├── weather/          # 心知天气
├── service/          # 高德地图等外部服务
├── tts/              # 讯飞语音合成
├── vision/           # 阿里云视觉分析
├── imagegen/         # 阿里云图像生成
└── web/              # 页面路由（16 个静态页面）
```

## 快速开始（本地）

### 环境要求

- JDK 21、Maven 3.9+（或使用 `./mvnw`）
- MySQL 8.x（数据库名 `ilink_chat`，启动时自动建表）
- ffmpeg（音频转换，可选）

### 1. 初始化数据库

```sql
CREATE DATABASE ilink_chat DEFAULT CHARACTER SET utf8mb4;
```

### 2. 配置密钥

仓库中的 `application.properties` 使用环境变量占位符。本地开发建议在项目根目录新建 `application-local.properties`（已被 gitignore，不会提交）：

```properties
spring.datasource.password=你的MySQL密码
dashscope.api-key=sk-xxx
dashscope.embedding.api-key=sk-xxx
webpush.vapid.private-key=xxx
```

也可直接注入环境变量（见下方表格）。

### 3. 启动

```bash
./mvnw spring-boot:run
```

启动后访问 <http://localhost:8080>，注册账号即可使用。

## 环境变量

| 变量 | 用途 |
| --- | --- |
| `DASHSCOPE_API_KEY` | DeepSeek V4-Pro 对话模型 |
| `DASHSCOPE_EMBEDDING_API_KEY` | 阿里云百炼 Embedding / 视觉 / 生图 |
| `MYSQL_PASSWORD` | MySQL 密码 |
| `SENIVERSE_API_KEY` | 心知天气 |
| `AMAP_API_KEY` | 高德地图 |
| `XUNFEI_TTS_API_KEY` / `XUNFEI_TTS_API_SECRET` | 讯飞语音合成 |
| `BAIDU_SEARCH_API_KEY` | 百度搜索 |
| `WEBPUSH_VAPID_PRIVATE_KEY` | WebPush 推送私钥（公钥在前端） |
| `RAG_DB_PATH` | SQLite 向量库路径（默认 `rag_knowledge.sqlite`） |

## 生产部署（阿里云）

```bash
# 打包
mvn -B clean package -DskipTests

# 上传并替换（systemd 服务名 ilink）
scp target/demo-0.0.1-SNAPSHOT.jar root@<服务器>:/opt/ilink/demo-0.0.1-SNAPSHOT.jar.new
ssh root@<服务器> "systemctl stop ilink && mv /opt/ilink/demo-0.0.1-SNAPSHOT.jar.new /opt/ilink/demo-0.0.1-SNAPSHOT.jar && chown admin:admin /opt/ilink/demo-0.0.1-SNAPSHOT.jar && systemctl start ilink"
```

服务端密钥放在 `/opt/ilink/application-local.properties`（不随 jar 提交）。CI 已配置 GitHub Actions，push 到 `main` 自动执行 Maven 打包校验。

## API 概览

| 模块 | 端点 | 说明 |
| --- | --- | --- |
| 鉴权 | `/api/auth/register` `/api/auth/login` `/api/auth/me` `/api/auth/logout` | 账号体系（除登录注册外均需会话） |
| 对话 | `/api/ai/chat` `/api/ai/chat/stream` `/api/ai/chat-with-tools` | 普通 / 流式 / 工具对话 |
| 工具 | `/api/ai/tools/registered` | 查看已注册工具 |
| 数据观测 | `/api/stats/overview` `/api/stats/tool-ranking` `/api/stats/trend` | 平台概览 / 工具调用排行 / 趋势 |
| 护理 | `/api/care/identify` `/api/care/targets/**` `/api/care/records/**` `/api/care/qa` | 识图诊断、档案、记录、问答 |
| 知识库 | `/api/kb/upload` `/api/kb/list` `/api/kb/{sourceId}` | 上传 / 列表 / 删除知识条目 |
| 页面 | `/login` `/register` `/home` `/chat` `/care` `/reminders` `/briefing` `/kb` `/stats` `/shop` `/community` `/timeline` `/inventory` `/disease` | 前端页面 |

## 文档

更多设计文档（架构图、Agent 核心能力、工具调用体系、记忆与 RAG 设计、Java 学习指南）见 `落地文档/` 目录。
