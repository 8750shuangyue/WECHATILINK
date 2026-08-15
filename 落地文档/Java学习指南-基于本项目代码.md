# Java 学习指南 —— 以本项目代码为例

> 本指南用项目里的**真实代码**讲解 Java 语言特性与 Spring 框架用法。建议边看文档边打开对应文件对照阅读。

---

## 〇、推荐阅读顺序

1. `Application.java` —— 程序入口，认识 @SpringBootApplication
2. `web/PageController.java` —— 最简 Controller
3. `auth/AuthController.java` + `auth/AuthService.java` —— 一个完整的"请求→服务→数据库"链路
4. `agent/tools/BaseTool.java` + `agent/tools/WeatherTool.java` —— 接口/抽象类/多态的经典例子
5. `aicare/Result.java` —— 泛型
6. `chat/ChatMemoryService.java` —— 依赖注入、@Value、事件
7. `chat/config/SQLiteConfig.java` —— 配置类、多数据源
8. `agent/AgentService.java` —— 并发、线程池、大型业务类

---

## 一、Java 语言基础（在本项目中的体现）

### 1.1 包与类的组织

项目根包是 `com.example.demo`，按业务拆分子包（`auth`、`care`、`agent`...）。包名通常反写域名，类名大驼峰，方法/变量小驼峰。

```java
// auth/AuthService.java
package com.example.demo.auth;   // 包声明

@Service
public class AuthService {        // 类
    private final UserRepository userRepository;  // 字段

    public AuthService(UserRepository userRepository) {  // 构造方法
        this.userRepository = userRepository;
    }
}
```

### 1.2 接口（interface）

接口定义"能做什么"，不写实现。`ChatMemoryRepository` 是项目里的接口例子：

```java
// chat/repository/ChatMemoryRepository.java
public interface ChatMemoryRepository {
    List<ChatMessage> getMessages(String conversationId);
    void addMessage(String conversationId, ChatMessage message);
    void clear(String conversationId);
}
```

它有两个实现：`DatabaseChatMemoryRepository`（存 MySQL）和 `InMemoryChatMemoryRepository`（存内存）。这就是**面向接口编程**：调用方只依赖接口，换实现不用改调用代码。

### 1.3 抽象类（abstract class）

抽象类 = 部分实现 + 部分留给子类。`BaseTool` 是所有 Agent 工具的父类：

```java
// agent/tools/BaseTool.java
public abstract class BaseTool {
    public abstract String getName();                 // 抽象方法：子类必须实现
    public abstract ToolResult<?> execute(JSONObject params);

    protected ToolResult<?> safeExecute(JSONObject params) {  // 普通方法：所有子类共用
        try {
            return execute(params);
        } catch (Exception e) {
            return ToolResult.failure("工具执行失败");
        }
    }
}
```

子类（如 `WeatherTool`）用 `extends BaseTool` 继承，并实现抽象方法。`AgentService` 用一个 `Map<String, BaseTool>` 注册表管理所有工具——这就是**多态**：同一个父类类型，运行时执行的是各自子类的方法。

### 1.4 泛型（Generics）

泛型让"一个类适用多种类型"。项目的统一返回类 `Result<T>`：

```java
// aicare/Result.java
public class Result<T> {
    private Integer code;
    private String message;
    private T data;   // T 是类型参数，使用时才确定

    public static <T> Result<T> success(T data) {
        Result<T> r = new Result<>();
        r.setCode(200);
        r.setData(data);
        return r;
    }
}
```

使用时：`Result<List<InventoryItem>>`、`Result<String>`、`Result<Map<String, Object>>`——同一个类被"参数化"成不同返回类型。`ToolResult<?>` 里的 `?` 是通配符，表示"任意类型"。

### 1.5 record（Java 16+ 不可变数据类）

`AgentService` 内部用一个 record 打包"一次工具调用任务"：

```java
private record ToolCallTask(ToolCall toolCall, String toolName,
                            JSONObject arguments, BaseTool tool) {}
```

record 自动生成构造方法、`toString()`、`equals()`、getter（`task.toolName()` 这样取值），适合做不可变的临时数据载体。

### 1.6 枚举（enum）

有限个固定取值用枚举。例如 `inventory/entity/InventoryItem.ItemCategory`、护理记录类型 `CareRecord.TargetType`：

```java
public enum TargetType { PET, PLANT }
```

枚举是类型安全的：方法参数声明为 `TargetType`，就不可能传入别的字符串。

### 1.7 Lambda 表达式 与 Stream

把"函数"当作值传递。`ToolCallingService` 里注册工具：

```java
// ai/ToolCallingService.java
this.toolExecutor = Executors.newFixedThreadPool(
        Math.min(Runtime.getRuntime().availableProcessors(), 4),
        r -> {                                  // lambda：线程工厂
            Thread t = new Thread(r, "tool-executor");
            t.setDaemon(true);
            return t;
        });
```

Stream 是对集合的"流水线"处理（过滤→转换→收集）：

```java
// ai/AiController.java
long successCount = toolResponse.getToolCallHistory().stream()
        .filter(ToolCallResult::isSuccess)      // 过滤：只留成功的
        .count();                                // 计数

// 把对象列表转成属性列表
List<String> names = list.stream()
        .map(item -> item.getName())
        .collect(Collectors.toList());
```

常见操作：`filter`（过滤）、`map`（转换）、`collect`（收集）、`count`（计数）、`sorted`（排序）。

### 1.8 Optional（优雅处理"可能没有值"）

避免 `if (x == null)` 满天飞：

```java
// chat/repository/DatabaseChatMemoryRepository.java
Optional<Conversation> existing = conversationRepository.findById(conversationId);
if (existing.isEmpty()) {
    conversationRepository.save(new Conversation(conversationId));
}
```

### 1.9 var（类型推断，Java 10+）

```java
var msg = messages.get(0);   // 编译器自动推断为 WeixinMessage
var p = await...;            // 局部变量类型可以省略
```

只能用于局部变量，不能用于字段和方法参数。

### 1.10 文本块（Text Block，Java 15+）

用 `"""` 写多行字符串，适合 SQL 和 JSON：

```java
// care/service/CareReminderService.java
List<Map<String, Object>> rows = jdbc.queryForList("""
        SELECT id, user_id, target_type, content, due_at
        FROM care_reminder
        WHERE status='PENDING' AND due_at <= NOW()
        ORDER BY due_at LIMIT 20
        """);
```

### 1.11 try-with-resources（自动关闭资源）

```java
// core/FileParserService.java
try (PDDocument document = Loader.loadPDF(content)) {   // 用完后自动 close
    String text = new PDFTextStripper().getText(document);
    return text;
}
```

实现 `AutoCloseable` 的资源（文件流、数据库连接、HTTP 客户端）放进 `try(...)`，结束时自动释放，不用手写 `finally close()`。

---

## 二、Spring 框架（本项目的地基）

### 2.1 IoC 容器与依赖注入（DI）

Spring 启动时扫描所有带 `@Component` 系注解的类，把它们变成"Bean"放进容器。Bean 之间的依赖**不自己 new**，而是让 Spring 通过构造方法注入：

```java
// chat/ChatMemoryService.java
@Service                       // 声明这是一个 Spring Bean
public class ChatMemoryService {
    private final ChatMemoryRepository repository;   // 接口类型
    private final ApplicationEventPublisher eventPublisher;

    // 构造方法注入：Spring 自动把容器里的 Bean 传进来
    public ChatMemoryService(ChatMemoryRepository repository,
                             ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }
}
```

**为什么用 final + 构造注入？** final 保证不可变；构造注入让依赖关系显式、好测试。这是 Spring 官方推荐的做法（项目里全部这么写）。

### 2.2 常用注解速查

| 注解 | 用途 | 项目例子 |
|---|---|---|
| `@SpringBootApplication` | 入口类，组合了配置+扫描+自动配置 | `Application.java` |
| `@Component` | 通用 Bean | `ILinkMessageListener` |
| `@Service` | 业务服务 Bean | `AuthService`、`AgentService` |
| `@RestController` | 返回 JSON 的 Web 控制器 | 所有 `*Controller` |
| `@Configuration` | 配置类（定义 @Bean） | `SQLiteConfig`、`AsyncConfig` |
| `@Repository` | 数据访问 Bean（JPA 仓储） | `UserRepository` |
| `@Bean` | 在配置类里手动创建 Bean | `mysqlDataSource()` |
| `@Value("${...}")` | 读取配置文件的值 | `chat.memory.max-messages` |
| `@ConfigurationProperties` | 把一组配置映射成对象 | `DashScopeConfig` |
| `@Autowired` | 字段注入（尽量少用，用构造注入） | `ChatMemoryService` 里 `@Lazy` 字段 |
| `@Lazy` | 延迟注入（避免循环依赖） | `ChatMemoryService` 里的 `LlmService` |
| `@PostConstruct` | Bean 初始化后执行一次 | `VectorStoreService.init()` 加载索引 |
| `@PreDestroy` | Bean 销毁前执行（清理线程池等） | `AgentService.shutdown()` |
| `@EventListener` | 监听 Spring 事件 | `ChatMemoryMigration`、`MemoryEventListener` |
| `@Async` | 方法异步执行 | `MemoryEventListener` 事件处理 |
| `@Scheduled` | 定时任务 | `CareReminderService.sendDueReminders` |

### 2.3 配置类与 @Bean

`AsyncConfig` 用 `@Bean` 手工创建了两个线程池执行器：

```java
// chat/config/AsyncConfig.java
@Configuration
@EnableAsync
public class AsyncConfig {
    @Bean(name = "vectorTaskExecutor")
    public Executor vectorTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setThreadNamePrefix("vector-task-");
        executor.initialize();
        return executor;
    }
}
```

`@Bean` 方法返回什么类型，容器里就有什么 Bean。`@EnableAsync` 开启后，`@Async("vectorTaskExecutor")` 的方法就会在那个线程池里跑。

### 2.4 读取配置：@Value 与 @ConfigurationProperties

**@Value**：单条读取，带默认值：

```java
@Value("${chat.memory.max-messages:10}")   // 冒号后是默认值
private int maxMessages;
```

**@ConfigurationProperties**：批量映射成对象：

```java
// config/DashScopeConfig.java
@Configuration
@ConfigurationProperties(prefix = "dashscope")   // 对应 dashscope.* 前缀
public class DashScopeConfig {
    private String apiKey;
    private String baseUrl;
    private Vision vision = new Vision();   // 嵌套对象 → dashscope.vision.*
}
```

配置文件里的 `dashscope.vision.model=qwen-vl-plus` 会自动填进 `vision.model` 字段，省掉一堆 @Value。

### 2.5 Bean 生命周期

```java
// chat/VectorStoreService.java
@PostConstruct
public void init() { loadFromSQLite(); }   // 启动时加载向量索引
```

```java
// agent/AgentService.java
@PreDestroy
public void shutdown() { toolExecutor.shutdown(); }  // 关停时释放线程池
```

还有 `@EventListener(ApplicationReadyEvent.class)`：应用完全启动后执行一次，项目用它在启动时自动建表（`CareSchemaInitializer`）。

---

## 三、Spring Boot Web 开发

### 3.1 最简控制器

```java
// web/PageController.java
@Controller                       // 返回页面（非 JSON）
public class PageController {
    @GetMapping("/home")
    public String home() {
        return "forward:/home.html";   // 转发到静态页面
    }
}
```

```java
// auth/AuthController.java
@RestController                   // 返回 JSON（自动序列化）
@RequestMapping("/api/auth")      // 类级前缀
public class AuthController {
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody Map<String, String> params,
                                             HttpSession session) {
        ...
        return Result.success(result);
    }
}
```

### 3.2 参数绑定方式

| 注解 | 从哪取 | 例子 |
|---|---|---|
| `@PathVariable` | URL 路径 | `/api/care/targets/{type}` → `type` |
| `@RequestParam` | 查询参数/表单 | `?page=1&size=10` |
| `@RequestBody` | 请求体 JSON | 登录参数、发帖内容 |
| `@RequestPart` / MultipartFile | 文件上传 | `FileController` |
| `HttpSession` | 会话（登录态） | `session.getAttribute("user")` |

### 3.3 登录会话（本项目现状）

```java
// AuthController.java
@PostMapping("/login")
public Result<Map<String, Object>> login(@RequestBody Map<String, String> params,
                                         HttpSession session) {
    ...
    session.setAttribute("user", userName);   // 登录成功写入会话
}

@GetMapping("/me")
public Result<Map<String, Object>> me(HttpSession session) {
    String userName = (String) session.getAttribute("user");  // 读取
    ...
}
```

浏览器访问时自动带 Cookie（JSESSIONID），服务端据此识别"谁在登录"。注意：**本项目接口本身没有拦截器校验**，这是安全上的已知问题，也是你学"过滤器/拦截器/Spring Security"的切入点。

### 3.4 静态资源与 CORS

```java
// core/WebConfig.java
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")            // 访问路径
                .addResourceLocations("file:" + uploadPath + "/");  // 磁盘目录
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**").allowedOrigins("*") ...   // 跨域配置
    }
}
```

`src/main/resources/static/` 下的文件由 Spring Boot 自动当静态资源托管，所以 `index.html` 直接访问 `/` 就能看到。

---

## 四、数据访问（JPA / JDBC）

### 4.1 实体类（Entity）

实体 = 一张数据库表的 Java 映射：

```java
// chat/entity/Message.java
@Entity
@Table(name = "messages")                    // 对应表 messages
public class Message {
    @Id                                        // 主键
    @GeneratedValue(strategy = GenerationType.IDENTITY)  // 自增
    private Long id;

    @Column(name = "conversation_id", length = 255, nullable = false)
    private String conversationId;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;
}
```

字段名（小驼峰）默认映射成列名（snake_case）；不想默认映射就用 `@Column(name=...)` 指定。

### 4.2 Repository（数据访问层）

继承 `JpaRepository<实体, 主键类型>` 就自动获得增删改查：

```java
// chat/repository/mysql/MessageRepository.java
public interface MessageRepository extends JpaRepository<Message, Long> {
    // 方法名即查询：按 conversationId 排序查询
    List<Message> findByConversationIdOrderByTimestampAsc(String conversationId);

    long countByConversationId(String conversationId);

    // 复杂查询用 JPQL 注解
    @Query("SELECT m.role AS role, m.content AS content FROM Message m " +
           "WHERE m.conversationId = :conversationId ORDER BY m.timestamp ASC LIMIT :limit")
    List<MessageProjection> findMessagesForConversation(
            @Param("conversationId") String conversationId, @Param("limit") int limit);
}
```

**Spring Data 的方法名规则**：`findBy` + 字段名 + `OrderBy` + 字段 + `Asc/Desc`，Spring 自动生成 SQL。这是本项目大量使用的能力。

### 4.3 事务 @Transactional

数据库操作要"要么全成功、要么全回滚"：

```java
// chat/repository/DatabaseChatMemoryRepository.java
@Override
@Transactional
public void addMessage(String conversationId, ChatMessage message) {
    ensureConversationExists(conversationId);
    messageRepository.save(convertToMessage(conversationId, message));
}
```

方法里任何一步抛异常，前面的写操作都会回滚。

### 4.4 双数据源（本项目高级配置）

`SQLiteConfig` 用两个内部配置类分别定义 MySQL 和 SQLite 的 EntityManagerFactory：

```java
// chat/config/SQLiteConfig.java（节选）
@Configuration
@EnableJpaRepositories(
        basePackages = "com.example.demo.chat.repository.mysql",
        entityManagerFactoryRef = "entityManagerFactory",
        transactionManagerRef = "transactionManager")
public static class MySQLConfig {
    @Bean(name = "mysqlDataSource")
    @Primary
    public DataSource mysqlDataSource() {
        return DataSourceBuilder.create()
                .driverClassName("com.mysql.cj.jdbc.Driver")
                .url(mysqlUrl).username(mysqlUsername).password(mysqlPassword)
                .build();
    }
}
```

核心点：`@EnableJpaRepositories` 指定"哪些 Repository 用哪个 EntityManagerFactory"，`@Primary` 标记主数据源。理解这块需要先懂"DataSource → EntityManagerFactory → TransactionManager"三层。

### 4.5 原生 SQL：JdbcTemplate

没有实体映射的简单操作，直接用 `JdbcTemplate` 写 SQL：

```java
// care/service/CareReminderService.java
private final JdbcTemplate jdbc;

public int countPendingReminders(String userId) {
    Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM care_reminder WHERE user_id=? AND status='PENDING'",
            Integer.class, userId);
    return count == null ? 0 : count;
}
```

`?` 是占位符，参数按顺序传入——**防止 SQL 注入**的正确写法，千万别用字符串拼接。

---

## 五、JSON 与 HTTP 调用

### 5.1 fastjson2 操作 JSON

```java
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONArray;

JSONObject body = new JSONObject();
body.put("model", "deepseek-v4-pro");
body.put("messages", messages);            // 嵌套 JSONArray

String json = JSON.toJSONString(body);      // 对象 → JSON 字符串
JSONObject resp = JSON.parseObject(text);   // 字符串 → 对象
JSONArray choices = resp.getJSONArray("choices");
String content = choices.getJSONObject(0).getString("content");
```

### 5.2 用 Java HttpClient 调第三方 API

```java
// vision/VisionService.java（节选）
HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl + "/chat/completions"))
        .header("Authorization", "Bearer " + apiKey)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(jsonRequest))
        .build();

HttpResponse<String> response = httpClient.send(request,
        HttpResponse.BodyHandlers.ofString());
if (response.statusCode() != 200) {
    throw new IOException("请求失败: " + response.body());
}
```

这是 JDK 自带的 HTTP 客户端，三步：**构造请求 → 发送 → 处理响应**。项目里同时存在 JDK HttpClient、HttpClient5、OkHttp 三种，学习时掌握一种即可。

### 5.3 文件上传解析

```java
// core/FileController.java
@PostMapping("/process")
public ResponseEntity<Map<String, Object>> processFile(
        @RequestParam("file") MultipartFile file, ...) {
    // MultipartFile 就是上传的文件对象
    String content = fileParserService.parseFile(file);
}
```

上传大小在 `application.properties` 里配置：`spring.servlet.multipart.max-file-size=10MB`。

---

## 六、异步、并发与定时任务

### 6.1 线程池（Thread Pool）

频繁创建线程很贵，所以用线程池复用线程：

```java
// agent/AgentService.java
private static final ExecutorService toolExecutor = Executors.newFixedThreadPool(
        Math.min(Runtime.getRuntime().availableProcessors(), 4));
```

`newFixedThreadPool(n)` 创建固定 n 个线程的池。用 `executor.submit(task)` 提交任务，任务排队执行。

### 6.2 CompletableFuture（异步 + 等待结果）

Agent 要**并发执行多个工具**再汇总结果：

```java
// agent/AgentService.java（简化）
Map<String, CompletableFuture<ToolResult<?>>> futureMap = new LinkedHashMap<>();
for (ToolCallTask task : tasks) {
    futureMap.put(task.toolCall().getId(),
            CompletableFuture.supplyAsync(() -> executeTool(task.tool(), task.args()),
                    toolExecutor));                       // 在线程池里异步执行
}

for (Map.Entry<String, CompletableFuture<ToolResult<?>>> e : futureMap.entrySet()) {
    ToolResult<?> result = e.getValue().get(15, TimeUnit.SECONDS);  // 最多等 15 秒
}
```

`supplyAsync(任务, 线程池)` 异步执行并返回 future；`future.get(超时)` 阻塞等待结果。这就是"多个工具同时跑、都跑完再继续"。

### 6.3 @Async 注解异步

```java
// chat/listener/MemoryEventListener.java
@EventListener
@Async("vectorTaskExecutor")     // 在指定线程池异步执行，不阻塞主流程
public void handleVectorSaveEvent(VectorSaveEvent event) {
    vectorStoreService.saveMessage(...);
}
```

发送事件后主线程立刻返回，向量入库在后台做。

### 6.4 事件驱动（观察者模式）

**发布**：

```java
// chat/ChatMemoryService.java
eventPublisher.publishEvent(new VectorSaveEvent(conversationId, user, reply));
```

**监听**：

```java
// chat/listener/MemoryEventListener.java
@EventListener
public void handleVectorSaveEvent(VectorSaveEvent event) { ... }
```

发布者和监听者互不依赖，新增监听不用改发布代码——松耦合的经典写法。

### 6.5 定时任务 @Scheduled

```java
// care/service/CareReminderService.java
@Scheduled(fixedDelay = 60000, initialDelay = 30000)   // 启动 30 秒后，每 60 秒执行
public void sendDueReminders() { ... }

// briefing/BriefingService.java
@Scheduled(cron = "0 0 8 * * ?")    // cron 表达式：每天 8 点
public void scheduledBriefing() { ... }
```

多个定时任务共用 Spring 调度线程池，本项目已配置 `spring.task.scheduling.pool.size=3` 避免互相阻塞。

---

## 七、Lombok 与日志

### 7.1 Lombok（省样板代码）

```java
// ai/AiController.java
@Slf4j                     // 自动生成 log 对象
@RequiredArgsConstructor   // 自动生成"所有 final 字段"的构造方法
@RestController
public class AiController {
    private final SpringAiChatService springAiChatService;  // 自动进构造方法

    public void demo() {
        log.info("记录日志");   // 不用自己声明 Logger
    }
}
```

还有 `@Data`（实体类的 getter/setter/toString）、`@Builder`（建造者模式，见 `WeatherResponse.builder()`）等。

### 7.2 日志（slf4j）

```java
log.info("User registered: {}", userName);   // {} 是占位符，别用字符串拼接
log.warn("解析失败: {}", e.getMessage());
log.error("保存失败", e);                    // 异常要传整个对象
```

**原则**：用 `{}` 占位符（懒加载、性能好）；错误日志带上异常对象；不要 `System.out.println`。

---

## 八、AI 集成（Spring AI 工具调用）

项目用 Spring AI 的 `@Tool` 注解把 Java 方法暴露给大模型调用：

```java
// ai/SpringAiTools.java
@Tool(name = "getWeather", description = "查询指定城市的实时天气信息")
public WeatherResponse getWeather(
        @ToolParam(description = "城市名称，如：北京、上海、杭州") String city) {
    return weatherService.getWeatherByCity(city);
}
```

`ToolCallingService` 用**反射**扫描这些注解方法：

```java
for (Method method : toolObject.getClass().getDeclaredMethods()) {
    Tool toolAnnotation = method.getAnnotation(Tool.class);
    if (toolAnnotation != null) {
        toolRegistry.put(toolName, new ToolInfo(toolObject, method, toolAnnotation));
    }
}
```

反射（`getAnnotation`、`invoke`）是 Java 高级特性，也是理解"框架如何发现你的代码"的钥匙——Spring 的注解机制本质就是反射。

---

## 九、项目里的设计模式

| 模式 | 项目体现 |
|---|---|
| 工厂方法 | `Result.success()` / `ToolResult.success()` 静态工厂 |
| 抽象模板 | `BaseTool.safeExecute()` 模板方法 |
| 注册表 | `AgentService.toolRegistry` 的 `Map<String, BaseTool>` |
| 策略 | 同一接口不同实现（记忆仓储 MySQL/内存） |
| 观察者 | Spring 事件（VectorSaveEvent / SummaryUpdateEvent） |
| DTO | `ChatMessage`、`WeatherResponse` 等传输对象 |
| 单例 | Spring Bean 默认单例 |

---

## 十、学习路线建议（基于本项目）

1. **先把 2.2 的注解表过一遍**，在代码里找每个注解出现的地方
2. 亲手改一个小功能：比如给 `AuthService` 加一个"修改密码"方法（Controller → Service → Repository 全链路）
3. 写一个自己的 REST 接口 + 实体 + Repository，体会 Spring Data 方法名查询
4. 学会看日志和报错栈：本项目就是最好的练习题（Access denied、400 错误都是经典案例）
5. 进阶方向：拦截器/Spring Security（本项目缺鉴权）、Stream 精进、多线程（AgentService 是绝佳教材）、单元测试（项目目前只有 contextLoads）

---

## 相关文件速查

| 想学什么 | 打开哪个文件 |
|---|---|
| 程序入口/自动配置 | `Application.java` |
| REST 接口 | `AuthController.java`、`CareController.java` |
| 依赖注入 | `ChatMemoryService.java` |
| 接口多态 | `BaseTool.java` + `WeatherTool.java` |
| 泛型 | `Result.java` |
| JPA 实体/仓储 | `Message.java` + `MessageRepository.java` |
| 原生 SQL | `CareReminderService.java` |
| 并发/线程池 | `AgentService.java` |
| 事件驱动 | `ChatMemoryService.java` + `MemoryEventListener.java` |
| 定时任务 | `CareReminderService.java`、`BriefingService.java` |
| 反射/注解 | `ToolCallingService.java` |
| 配置绑定 | `DashScopeConfig.java` |
| HTTP 调用 | `VisionService.java`、`EmbeddingService.java` |
