# 本地运行：API 与密钥清单

> 整理日期：2026-09-29
>
> 适用对象：克隆本仓库、准备用自己的 API Key 在本地跑起来的同事。
>
> 安全说明：本文只记录配置键、环境变量名和服务商入口，不记录任何真实密钥。

---

## 一、最短路径（5 步跑起来）

1. 安装 **JDK 21**、**Maven 3.9+**、**MySQL 8**；`ffmpeg` 可选（语音输入转码用）。
2. 建库：`CREATE DATABASE ilink_chat DEFAULT CHARACTER SET utf8mb4;`（表结构启动时自动创建）。
3. 在**项目根目录**把 `application-local.properties.example` 复制为 `application-local.properties`，填入自己的密钥。
4. 运行：`./mvnw spring-boot:run`，或 `mvn -B -DskipTests package` 后 `java -jar target/demo-0.0.1-SNAPSHOT.jar`。
5. 打开 <http://localhost:8080>，注册账号即可使用。

> ⚠️ 易踩的坑：`application-local.properties` 是**按当前工作目录**加载的（配置项 `spring.config.import=optional:file:./application-local.properties`）。
> 请务必以**项目根目录**作为运行/IDE 的工作目录，否则该文件不会被加载，数据库密码为空，启动时报
> `Access denied for user 'root'@'localhost' (using password: NO)`。

---

## 二、密钥总览

“必需”指：不填会导致对应功能不可用；应用本身仍能启动（数据库除外，见下）。

| 配置键 | 环境变量 | 服务商 | 用途 | 必需性 |
|---|---|---|---|---|
| `spring.datasource.password` | `MYSQL_PASSWORD` | 本地 MySQL | 业务库连接 | 必需 |
| `dashscope.api-key` | `DASHSCOPE_API_KEY` | DeepSeek 开放平台 | 主对话模型（Agent 对话、流式对话） | AI 功能必需 |
| `dashscope.embedding.api-key` | `DASHSCOPE_EMBEDDING_API_KEY` | 阿里云百炼（DashScope） | Embedding / 视觉识别 / 图像生成 / 语音识别 | AI 功能必需 |
| `weather.api.api-key` | `SENIVERSE_API_KEY` | 心知天气 | 天气查询、天气预警工具 | 可选 |
| `amap.api-key` | `AMAP_API_KEY` | 高德开放平台 | 附近宠物医院 / 园艺店查询与导航 | 可选 |
| `xunfei.tts.app-id` | `XUNFEI_TTS_APP_ID` | 讯飞开放平台 | 语音合成 | 可选 |
| `xunfei.tts.api-key` | `XUNFEI_TTS_API_KEY` | 讯飞开放平台 | 语音合成 | 可选 |
| `xunfei.tts.api-secret` | `XUNFEI_TTS_API_SECRET` | 讯飞开放平台 | 语音合成 | 可选 |
| `baidu.search.api-key` | `BAIDU_SEARCH_API_KEY` | 百度（搜索接口） | Agent 联网搜索工具 | 可选 |
| `webpush.vapid.public-key` | `WEBPUSH_VAPID_PUBLIC_KEY` | 无需账号，本地生成 | 浏览器推送 | 可选 |
| `webpush.vapid.private-key` | `WEBPUSH_VAPID_PRIVATE_KEY` | 无需账号，本地生成 | 浏览器推送 | 可选 |

### 可选部署变量（不是本地运行最低要求）

| 环境变量 | 默认值 | 用途 |
|---|---|---|
| `APP_CORS_ALLOWED_ORIGINS` | `http://localhost:8080,http://127.0.0.1:8080` | 显式 CORS 来源白名单；本地直接使用默认值即可，部署域名后改为真实 HTTPS 来源 |
| `SESSION_COOKIE_SECURE` | `false` | 是否给 Session Cookie 设置 `Secure`；仅在 HTTPS 环境设为 `true` |
| `RAG_DB_PATH` | `jdbc:sqlite:rag_knowledge.sqlite` | SQLite 向量库 JDBC 地址；只在需要调整数据库文件位置时设置 |
| `APP_DATA_GOVERNANCE_ENABLED` | `false` | 阶段 3.2 自动治理总开关；首次生产部署保持关闭，完成备份和盘点后再开启 |

说明：

- `spring.ai.openai.api-key` 复用 `DASHSCOPE_API_KEY`，不需要单独申请。
- `dashscope.vision.api-key`、`dashscope.image.api-key` 复用 Embedding Key，同样不需要单独申请。
- `xunfei.tts.app-id` 在 `application.properties` 里有一个默认值，那是原作者的 App ID。
  **同事请用自己的 App ID 覆盖**，否则调用会失败或计入别人的额度。
- 上表的部署变量都有本地默认值，**不填不会阻止本地启动**；它们主要用于域名上线、HTTPS、数据目录调整和向量治理启停。

---

## 三、需要申请的 API（逐个说明）

### 1. 对话模型（DeepSeek）

- 服务商入口：<https://platform.deepseek.com>
- 对应配置：`dashscope.api-key`（环境变量 `DASHSCOPE_API_KEY`）
- 说明：虽然配置键前缀是 `dashscope`，但 `dashscope.base-url` 指向 `https://api.deepseek.com/v1`，
  所以这里要填的是 **DeepSeek 的 API Key**，不是百炼的。
- 影响功能：AI 对话、工具模式、流式对话、护理问答等所有大模型调用。

### 2. 阿里云百炼 DashScope（Embedding / 视觉 / 生图 / 语音识别）

- 服务商入口：<https://bailian.console.aliyun.com>
- 对应配置：`dashscope.embedding.api-key`（环境变量 `DASHSCOPE_EMBEDDING_API_KEY`）
- 一把 Key 覆盖四类能力：
  - Embedding：知识库向量化与检索（`text-embedding-v2`）
  - 视觉：图片识别诊断（`qwen-vl-plus`）
  - 图像生成 / 编辑（`qwen-image-2.0`）
  - 语音识别 ASR（`fun-asr-realtime-2026-02-28`）
- 注意：ASR **必须用百炼 Key**，DeepSeek 的 Key 不能用于语音识别。

### 3. 心知天气（Seniverse）

- 服务商入口：<https://www.seniverse.com>
- 对应配置：`weather.api.api-key`（环境变量 `SENIVERSE_API_KEY`）
- 影响功能：天气查询工具、天气预警、按天气生成的护理建议。

### 4. 高德地图

- 服务商入口：<https://lbs.amap.com>
- 对应配置：`amap.api-key`（环境变量 `AMAP_API_KEY`）
- 影响功能：附近宠物医院 / 植物医院 / 园艺店查询，结果附带高德导航链接。

### 5. 讯飞开放平台（语音合成 TTS）

- 服务商入口：<https://www.xfyun.cn>
- 对应配置：`xunfei.tts.app-id`、`xunfei.tts.api-key`、`xunfei.tts.api-secret`
  （环境变量 `XUNFEI_TTS_APP_ID`、`XUNFEI_TTS_API_KEY`、`XUNFEI_TTS_API_SECRET`）
- 影响功能：文本转语音，聊天中的语音播报。
- 注意：三个值要在**同一个应用**下成对配置，缺一不可。

### 6. 百度搜索接口

- 对应配置：`baidu.search.api-key`（环境变量 `BAIDU_SEARCH_API_KEY`）
- 代码中请求地址：`https://api.baidu.com/api/snc/v1/search`，鉴权头为 `x-api-key`。
- 影响功能：Agent 联网搜索工具（`webSearch`、`professionalSearch`）。
- 注意：该接口来源比较特殊，**具体申请入口请找原作者确认**；不填只是搜索工具不可用。

### 7. WebPush 推送密钥（不需要申请账号）

- 本地生成一对 VAPID 密钥即可：

```bash
npx web-push generate-vapid-keys
```

- 对应配置：`webpush.vapid.public-key`、`webpush.vapid.private-key`
  （环境变量 `WEBPUSH_VAPID_PUBLIC_KEY`、`WEBPUSH_VAPID_PRIVATE_KEY`）
- 影响功能：护理提醒到期的浏览器推送、每日简报推送。
- 注意：公私钥必须成对；更换私钥后公钥也要换，浏览器已有订阅需重新订阅。

---

## 四、没有密钥时会怎样

- 应用**仍能正常启动**，登录、档案、库存、社区等纯业务功能可用。
- 用到外部服务的功能会报错或不可用（AI 对话、识图、生图、语音、天气、地图、搜索、推送）。
- 也就是说：**只填 MySQL 密码就能先把项目跑起来**，其余 Key 按需再补。

> 数据库是硬依赖：`spring.datasource.password` 不对会直接启动失败（JPA 需要建连做 DDL）。

---

## 五、两种注入方式（二选一）

### 方式 A：本地文件（推荐给个人开发）

```properties
# 项目根目录的 application-local.properties（已被 .gitignore 忽略）
spring.datasource.password=你的MySQL密码
dashscope.api-key=你的DeepSeekKey
dashscope.embedding.api-key=你的百炼Key
weather.api.api-key=
amap.api-key=
xunfei.tts.app-id=
xunfei.tts.api-key=
xunfei.tts.api-secret=
baidu.search.api-key=
webpush.vapid.public-key=
webpush.vapid.private-key=
```

### 方式 B：环境变量（推荐给部署 / CI）

参考 `.env.example` 中的变量名，在 shell、IDE 运行配置或容器环境中注入即可，无需本地文件。

> 提醒：Spring Boot **不会**自动加载 `.env` 文件，它只是变量名清单；请通过真实的环境变量注入。

---

## 六、启动前自检

```powershell
# 1. 确认本地配置没有被 Git 跟踪（应无输出）
git status --short -- application-local.properties

# 2. 扫描受版本控制的配置里是否混入真实密钥
.\scripts\check-sensitive-config.ps1
```

- [ ] MySQL 已启动，`ilink_chat` 库已创建
- [ ] `application-local.properties` 位于项目根目录且已被忽略
- [ ] 运行 / IDE 的工作目录是项目根目录
- [ ] `http://localhost:8080` 可以打开并注册账号
- [ ] 需要哪个外部功能，再补对应的 Key

---

## 七、常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| `Access denied for user 'root'@'localhost' (using password: NO)` | 工作目录不对，本地配置未加载 | 把工作目录设为项目根目录，或改用环境变量 |
| AI 对话报鉴权错误 | `dashscope.api-key` 填成了百炼 Key | 该配置需填 DeepSeek Key |
| 语音识别报错 | 用了 DeepSeek Key | ASR 需用 `dashscope.embedding.api-key`（百炼 Key） |
| 图像生成 / 识图不可用 | Embedding Key 未配置或额度不足 | 检查百炼控制台额度与模型开通情况 |
| TTS 报错 | 三个讯飞配置缺项，或用了默认 App ID | 用自己的 App ID + Key + Secret 覆盖 |
| `8080` 端口被占用 | 已有实例在跑 | 先停掉旧进程再启动 |
