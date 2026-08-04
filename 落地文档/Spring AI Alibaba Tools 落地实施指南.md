# Spring AI Alibaba Tools 落地实施指南

## 1. 核心概念：什么是 Tools？

Tools 是 Agent 用来执行操作的组件。它通过定义好的输入和输出，让模型能够与外部系统交互。主要分为两类：

- **信息检索**：从外部源（如数据库、Web 服务）获取信息，增强模型的知识（例如：查询当前天气）。
- **执行操作**：在软件系统中执行动作（例如：发送邮件、创建数据库记录、设置闹钟）。

## 2. 环境准备：从零接入 Spring AI

如果项目尚未集成 Spring AI，需先完成以下基础配置：

### 2.1 配置 Maven 仓库与依赖

Spring AI 相关包主要在 Spring Milestones 仓库中，需在 `pom.xml` 中配置：

```xml
<repositories>
    <repository>
        <id>spring-milestones</id>
        <name>Spring Milestones</name>
        <url>https://repo.spring.io/milestone</url>
        <snapshots>
            <enabled>false</enabled>
        </snapshots>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.alibaba.cloud.ai</groupId>
        <artifactId>spring-ai-alibaba-starter</artifactId>
        <version>1.0.0-M2</version>
    </dependency>
</dependencies>
```

### 2.2 配置大模型 API Key

在 `application.yml` 中配置通义千问（DashScope）的 API Key：

```yaml
spring:
  ai:
    dashscope:
      api-key: ${AI_DASHSCOPE_API_KEY}
```

## 3. 工具定义与创建

官方推荐最简洁的**声明式**写法，使用 `@Tool` 注解标记方法，Spring AI 会自动处理。

### 3.1 声明式工具示例

```java
@Component
public class MyTools {

    // 示例 1：信息检索工具
    @Tool(description = "获取当前的系统日期和时间")
    public String getCurrentDateTime() {
        return LocalDateTime.now().toString();
    }

    // 示例 2：执行操作工具
    @Tool(description = "在控制台设置一个提醒闹钟")
    public String setAlarm(@ToolParam(description = "提醒的具体时间，格式为 HH:mm") String time) {
        System.out.println("⏰ 闹钟已设置，将在 " + time + " 提醒你！");
        return "闹钟设置成功";
    }
}
```

> **提示**：`description` 非常重要，AI 模型依靠它来判断何时调用该工具。

### 3.2 创建方式对比

| 方式 | 描述 | 适用场景 |
| :--- | :--- | :--- |
| **声明式 (`@Tool`)** | 使用注解标记方法，自动处理 | 代码简洁，适合大多数常规业务逻辑 |
| **编程式 (`FunctionToolCallback`)** | 通过代码构建 `ToolCallback` | 需要动态定义工具或处理复杂逻辑时 |

## 4. Agent 集成方式

定义好工具后，需在构建 Agent（如 `ReactAgent`）时注入。提供三种注入方式：

### 方式 1：直接传入工具实例（最简单）

```java
ReactAgent agent = ReactAgent.builder()
        .chatClient(chatClient)
        .tools(new MyTools())
        .build();
```

### 方式 2：通过 Spring 容器注入（推荐）

```java
@Autowired
private MyTools myTools;

ReactAgent agent = ReactAgent.builder()
        .chatClient(chatClient)
        .tools(myTools)
        .build();
```

### 方式 3：使用 Provider（适合工具较多时）

```java
ReactAgent agent = ReactAgent.builder()
        .chatClient(chatClient)
        .toolCallbacks(new MethodToolCallbackProvider(new MyTools()))
        .build();
```

## 6. 运行与测试

配置完成后，Agent 会自动判断是否需要调用工具：

- **用户问**：“现在几点了？”
    - **Agent 内部**：识别到需要时间 -> 自动调用 `getCurrentDateTime()` -> 将返回的时间拼接到回复中。
- **用户说**：“帮我设个 10:30 的闹钟”
    - **Agent 内部**：识别到动作 -> 自动调用 `setAlarm("10:30")` -> 回复用户“闹钟已设置”。

### 连通性测试建议

在正式开发 Agent 前，建议先通过简单接口验证环境：

```java
@RestController
public class ChatController {
    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @GetMapping("/chat")
    public String chat(@RequestParam String message) {
        return chatClientBuilder.build().prompt(message).call().content();
    }
}
```

访问 `http://localhost:8080/chat?message=你好`，若大模型正常回复，说明 Spring AI 环境已就绪。