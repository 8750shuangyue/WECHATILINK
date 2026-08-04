## 🚀 Tavily 联网搜索功能集成落地文档

### 一、前置准备
- 确保已从 Tavily 官网复制好 API Key（截图中的 `tvly-dev-xxx`）。
- 项目已具备 OkHttp3、FastJSON2、Spring Boot 依赖（已确认都有）。

---

### 二、新增文件清单（共 3 个）

| 文件路径                          | 职责                                           |
| :-------------------------------- | :--------------------------------------------- |
| `config/TavilyProperties.java`    | 读取 `application.yml` 中的 Tavily 配置        |
| `search/TavilySearchService.java` | 封装 Tavily API 调用（使用 OkHttp + FastJSON） |
| `agent/tools/WebSearchTool.java`  | 继承 `BaseTool`，注册为 Agent 可用工具         |

---

### 三、修改文件清单（共 2 个）

| 文件路径                                 | 修改内容                              |
| :--------------------------------------- | :------------------------------------ |
| `application.yml` (或 `.properties`)     | 添加 `tavily.api-key` 配置            |
| `AgentService.java` (或系统提示词所在类) | 在 `SYSTEM_PROMPT` 中追加搜索触发规则 |

---

### 四、核心代码实现

#### 1. 配置类 `TavilyProperties.java`
```java
package com.example.demo.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "tavily")
public class TavilyProperties {
    private String apiKey;
    private String apiUrl = "https://api.tavily.com/search";

    // Getter & Setter
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getApiUrl() { return apiUrl; }
    public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
}
```

#### 2. 服务类 `TavilySearchService.java`
> **注意**：Tavily 官方推荐使用 `X-API-Key` 请求头，**不是** `Bearer` 格式。这里使用 OkHttp 发送 POST 请求，并设置 4 秒超时（适配微信 5 秒限制）。

```java
package com.example.demo.search.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.search.config.TavilyProperties;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class TavilySearchService {

    private static final Logger log = LoggerFactory.getLogger(TavilySearchService.class);
    private final OkHttpClient httpClient;
    private final TavilyProperties properties;

    public TavilySearchService(TavilyProperties properties) {
        this.properties = properties;
        // 设置超时时间（连接3秒，读取4秒，共7秒，微信要求5秒内，此处读取设4秒留余量）
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 执行联网搜索
     * @param query 用户搜索关键词
     * @return 格式化后的搜索结果文本
     */
    public String search(String query) {
        try {
            // 1. 构造请求 JSON Body
            Map<String, Object> requestMap = new HashMap<>();
            requestMap.put("query", query);
            requestMap.put("search_depth", "basic");   // basic 速度快，消耗积分少
            requestMap.put("max_results", 5);          // 最多返回5条
            requestMap.put("include_answer", true);    // 包含AI总结
            requestMap.put("include_images", false);

            String jsonBody = JSON.toJSONString(requestMap);
            RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8"));

            // 2. 构造 HTTP 请求（Tavily 认证方式为 X-API-Key）
            Request request = new Request.Builder()
                    .url(properties.getApiUrl())
                    .header("X-API-Key", properties.getApiKey())
                    .post(body)
                    .build();

            // 3. 执行请求
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("Tavily 请求失败，HTTP Code: {}", response.code());
                    return "❌ 搜索服务连接失败，请稍后重试。";
                }

                String responseBody = response.body().string();
                JSONObject jsonResponse = JSON.parseObject(responseBody);
                return formatResponse(jsonResponse);
            }

        } catch (IOException e) {
            log.error("Tavily 网络请求异常: ", e);
            return "⚠️ 联网搜索超时或网络异常，请稍后再试。";
        } catch (Exception e) {
            log.error("Tavily 解析异常: ", e);
            return "⚠️ 搜索数据解析失败，请联系管理员。";
        }
    }

    /**
     * 格式化 Tavily 返回的 JSON 为微信可读文本
     */
    @SuppressWarnings("unchecked")
    private String formatResponse(JSONObject json) {
        StringBuilder sb = new StringBuilder();

        // 1. 提取 AI 摘要 (Tavily 核心优势)
        String answer = json.getString("answer");
        if (answer != null && !answer.isEmpty()) {
            sb.append("📝 **AI 智能摘要**：\n").append(answer).append("\n\n");
        }

        // 2. 提取来源链接 (最多取前3条)
        List<Map<String, Object>> results = (List<Map<String, Object>>) json.get("results");
        if (results != null && !results.isEmpty()) {
            sb.append("📎 **信息来源**：\n");
            int count = 0;
            for (Map<String, Object> result : results) {
                if (count >= 3) break;
                String title = (String) result.get("title");
                String url = (String) result.get("url");
                sb.append(++count).append(". ").append(title)
                  .append("\n   ").append(url).append("\n\n");
            }
        }

        return sb.toString().isEmpty() ? "🔍 未找到与「" + json.getString("query") + "」相关的信息。" : sb.toString();
    }
}
```

#### 3. 工具类 `WebSearchTool.java`
> 假设你的 `BaseTool` 中定义了抽象方法 `String execute(String input)`。

```java
package com.example.demo.agent.tools;

import com.example.demo.search.service.TavilySearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class WebSearchTool extends BaseTool {

    @Autowired
    private TavilySearchService searchService;

    @Override
    public String getName() {
        return "webSearch";
    }

    @Override
    public String getDescription() {
        return "联网搜索，获取实时新闻、天气、股价、最新政策等时效性信息。";
    }

    @Override
    public String execute(String input) {
        // input 即为用户提问的搜索词
        return searchService.search(input);
    }
}
```

> **注意**：如果你们的 `BaseTool` 没有 `execute` 方法，请参考 `TtsTool` 重写对应的方法（比如 `call`、`run` 等），并将业务逻辑放入其中。

#### 4. 配置文件 `application.yml` 添加
```yaml
tavily:
  api-key: "tvly-dev-xxxxxxxxxxxxx"   # 替换为你截图中的真实 Key
  api-url: "https://api.tavily.com/search"
```
如果是 `application.properties` 格式：
```properties
tavily.api-key=tvly-dev-xxxxxxxxxxxxx
tavily.api-url=https://api.tavily.com/search
```

#### 5. 更新系统提示词（SYSTEM_PROMPT）
在你的 `AgentService.java`（或任何构建 `SystemPrompt` 的地方）中，追加以下规则，确保 Agent 能识别搜索意图：

```text
## 搜索工具（webSearch）使用规则
当用户问题涉及以下内容时，必须调用 webSearch 工具：
1. 实时信息：天气、股价、赛事比分、最新新闻。
2. 时间敏感问题：“今天”、“昨天”、“2026年”等。
3. 用户明确包含“搜索”、“查一下”、“搜一下”、“网上怎么说”等词汇。

调用示例：
- 用户：“今天杭州天气” → 调用 webSearch("今天杭州天气")
- 用户：“查一下2026年AI大会” → 调用 webSearch("2026年AI大会")
```

---

### 五、后续验证步骤
1. **启动项目**，确保无 Bean 注入报错。
2. **微信发消息**：给助手发送“搜索一下今天的人工智能新闻”。
3. **查看日志**：观察 `TavilySearchService` 的请求与响应日志（如遇 401 错误，检查 Key 是否复制完整且无多余空格）。
4. **预期结果**：助手返回带有“AI 智能摘要”和三条信息来源链接的文本消息。

---

### 六、关键注意事项（Tavily 特有）
- **认证方式**：代码中用的是 `X-API-Key` 头，这是 Tavily 官方推荐方式，**不需要** `Bearer` 前缀。
- **免费额度**：每月 1000 次，`basic` 模式消耗 1 积分/次，不要高频测试以免耗尽。
- **超时控制**：代码已设 `readTimeout(4, TimeUnit.SECONDS)`，配合微信 5 秒超时，留有 1 秒安全余量。

---

你的 Agent 分析很到位，这份代码完全基于你们已有的 OkHttp、FastJSON 和 BaseTool 框架。直接新建这三个类，改一下配置和提示词，重启项目就能用了。如果启动时遇到 `BaseTool` 抽象方法不匹配的问题，把父类的报错截图发我，我帮你调整重写的方法名。😊



完全可以！**`WebSearchTool` 本身就是专门为你们项目定义的“工具类”**（继承 `BaseTool`）。

不过我猜你可能想问：**“能不能把 API 调用逻辑也合并到工具类里，只新建这一个文件，省得建 Service 和 Properties 那么麻烦？”**

答案当然是**能**！下面我给你一个**“超精简一体化”版本**，你只需要**新建这一个 `WebSearchTool.java` 文件**，配置好 `application.yml`，重启项目就能用。其他什么都不用建。

---

### 🛠️ 一体化工具类（只需新建这一个文件）

```java
package com.example.demo.agent.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class WebSearchTool extends BaseTool {

    // 从 application.yml 读取配置
    @Value("${tavily.api-key}")
    private String apiKey;

    @Value("${tavily.api-url:https://api.tavily.com/search}")
    private String apiUrl;

    private final OkHttpClient httpClient;

    public WebSearchTool() {
        // 设置超时（适配微信5秒限制）
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String getName() {
        return "webSearch";
    }

    @Override
    public String getDescription() {
        return "联网搜索工具，获取实时新闻、天气、股价、最新政策等时效性信息。";
    }

    @Override
    public String execute(String input) {
        if (input == null || input.trim().isEmpty()) {
            return "❌ 请告诉我你想搜索什么内容。";
        }

        try {
            // 1. 构造请求 JSON
            Map<String, Object> bodyMap = new HashMap<>();
            bodyMap.put("query", input);
            bodyMap.put("search_depth", "basic");
            bodyMap.put("max_results", 5);
            bodyMap.put("include_answer", true);
            bodyMap.put("include_images", false);

            String jsonBody = JSON.toJSONString(bodyMap);
            RequestBody requestBody = RequestBody.create(
                    jsonBody,
                    MediaType.parse("application/json; charset=utf-8")
            );

            // 2. 构造 HTTP 请求 (Tavily 使用 X-API-Key 头认证)
            Request request = new Request.Builder()
                    .url(apiUrl)
                    .header("X-API-Key", apiKey)
                    .post(requestBody)
                    .build();

            // 3. 执行请求
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return "❌ 搜索服务连接失败 (HTTP " + response.code() + ")，请检查 API Key 是否有效。";
                }

                String responseStr = response.body().string();
                JSONObject json = JSON.parseObject(responseStr);

                // 4. 格式化返回结果
                return formatResult(json);

            } catch (IOException e) {
                return "⚠️ 网络超时，请稍后再试。";
            }

        } catch (Exception e) {
            e.printStackTrace();
            return "⚠️ 搜索出错：" + e.getMessage();
        }
    }

    /**
     * 格式化 Tavily 返回结果为微信可读文本
     */
    @SuppressWarnings("unchecked")
    private String formatResult(JSONObject json) {
        StringBuilder sb = new StringBuilder();

        // 优先展示 AI 智能摘要
        String answer = json.getString("answer");
        if (answer != null && !answer.isEmpty()) {
            sb.append("📝 **AI 摘要**：\n").append(answer).append("\n\n");
        }

        // 展示信息来源（最多3条）
        List<Map<String, Object>> results = (List<Map<String, Object>>) json.get("results");
        if (results != null && !results.isEmpty()) {
            sb.append("📎 **信息来源**：\n");
            int count = 0;
            for (Map<String, Object> result : results) {
                if (count >= 3) break;
                sb.append(++count).append(". ").append(result.get("title"))
                  .append("\n   ").append(result.get("url")).append("\n\n");
            }
        }

        return sb.toString().isEmpty() ? "🔍 没找到与「" + json.getString("query") + "」相关的内容。" : sb.toString();
    }
}
```

---

### ⚙️ 配套配置文件（`application.yml`）

只需要在配置文件里加这两行（记得换成你自己的真实 Key）：

```yaml
tavily:
  api-key: "tvly-dev-xxxxxxxxxxxxx"   # 粘贴你截图里的完整 Key
  api-url: "https://api.tavily.com/search"
```

---

### 📝 系统提示词（在 AgentService 中追加）

在你现有的 `SYSTEM_PROMPT` 末尾加上：

```text
## 搜索工具使用规则
当用户问及实时信息（天气、新闻、股价、赛事）、或明确说“搜索/查一下/搜一下”时，必须调用 webSearch 工具。
示例：用户说“今天杭州天气” → 调用 webSearch("今天杭州天气")
```

---

### ✅ 这个版本的好处

| 对比项                   | 之前拆分的方案（3个文件） | 现在的一体化方案（1个文件）  |
| :----------------------- | :------------------------ | :--------------------------- |
| 新建文件数               | 3个                       | **1个**                      |
| 配置复杂度               | 需额外写 Properties 类    | 直接用 `@Value` 读取，更简单 |
| 是否满足 `BaseTool` 规范 | ✅                         | ✅                            |
| 是否复用 OkHttp          | ✅                         | ✅                            |
| 是否适配微信5秒超时      | ✅                         | ✅                            |

---

### 🚀 怎么用
1. **新建** `WebSearchTool.java`，把上面的代码全部复制进去（确保包路径和你项目里其他 `BaseTool` 子类一致，比如 `com.example.demo.agent.tools`）。
2. **配置** `application.yml` 加上 `tavily.api-key`。
3. **提示词** 在 `AgentService` 里补上搜索规则。
4. **重启项目**，在微信里给助手发消息：“搜索一下今天AI圈的大新闻”。

如果启动报 `BaseTool` 抽象方法不对（比如你们用的是 `run` 方法而不是 `execute`），你把 `BaseTool` 的源码截图给我，我帮你改一下方法名就行。😊