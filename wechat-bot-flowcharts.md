 # 微信机器人项目架构流程图
 
 ## 图一：宏观框架图
 
 ```mermaid
 flowchart TB
     subgraph outer["微信机器人 · 宏观框架"]
         direction TB
 
         A["Web管理控制台<br/>扫码登录 / 状态查看"]
         B["微信<br/>客户端"]
         C["iLink SDK 适配层<br/>消息收发 / 登录"]
         D["消息路由 &amp; 意图识别<br/>MessageHandlerService"]
         E["阿里云 DashScope LLM<br/>通义千问 Qwen"]
         F["通义万相 Wanx<br/>图片生成"]
         G["Edge-TTS<br/>语音合成"]
         H["微信聊天框内<br/>实时交互输出"]
 
         A -->|扫码| B
         B -->|发送消息| C
         C --> D
         D -->|文本对话| E
         D -->|图片生成| F
         D -->|朗读回复| G
         E -->|文本/语音指令| D
         F -->|图片字节| C
         G -->|MP3音频| C
         C -->|回复消息| H
     end
 ```
 
 ## 图二：详细链路图（含实现方式与技术栈）
 
 ```mermaid
 flowchart TB
     subgraph startup["1. 系统启动阶段"]
         direction LR
         S1["dotenv-java 加载 .env<br/>→ 环境变量注入 System.setProperty"]
         S2["SpringApplication.run()<br/>Spring Boot 4.1.0 Init"]
         S3["Bean 初始化<br/>所有 @Service / @Component<br/>@PostConstruct"]
         S1 --> S2 --> S3
     end
 
     subgraph login["2. 微信登录阶段"]
         direction TB
         L1["Web控制台 index.html<br/>用户点击「获取二维码」<br/>→ POST /api/wechat/login"]
         L2["WeChatBotController<br/>→ WeChatBotService.startLogin()<br/>→ WeChatClientService.startLogin()"]
         L3["iLink SDK<br/>ILinkClient.executeLogin()<br/>→ 返回二维码内容"]
         L4["QRCodeUtil (ZXing)<br/>qrcode 内容 → base64 PNG<br/>→ 返回前端展示"]
         L5["用户微信扫码<br/>→ iLink SDK 回调 onLoginSuccess<br/>→ botId 获取"]
         L6["WeChatEventDispatcher<br/>dispatchLoginStatus()<br/>→ Web前端轮询状态更新"]
         L7["startMessagePolling()<br/>ScheduledExecutorService<br/>每 2s 拉取消息"]
 
         L1 --> L2 --> L3 --> L4
         L4 -.-> L5
         L5 --> L6
         L6 --> L7
     end
 
     subgraph receive["3. 消息接收阶段"]
         direction LR
         R1["iLink SDK onMessage 回调<br/>或 getUpdates() 轮询<br/>(每2s线程池拉取)"]
         R2["WeChatEventDispatcher<br/>dispatchMessage()<br/>→ 广播给所有监听器<br/>实现: CopyOnWriteArrayList"]
         R3["WeChatBotService<br/>handleMessage() 监听器<br/>→ 下载图片(如有)<br/>→ 调用MessageHandlerService"]
         R1 --> R2 --> R3
     end
 
     subgraph route["4. 消息路由 &amp; 意图识别"]
         direction TB
         H1["MessageHandlerService<br/>handleMessage()"]
         H2["extractTextContent()<br/>遍历 item_list<br/>→ 提取 text_item / voice_item"]
         H3{是否包含图片?<br/>imageBytes != null}
         H4{文本匹配:<br/>图片生成请求?}
         H41["自然语言匹配<br/>Pattern 正则:<br/>生成/画/绘/创作..."]
         H42["精确前缀匹配<br/>/生成 猫"]
         H5{纯文本消息}
 
         H1 --> H2
         H2 --> H4
         H4 -->|"是: 生成/画..."| H41
         H4 -->|"是: /生成"| H42
         H4 -->|否| H3
         H3 -->|是| H5
         H3 -->|否| H5
     end
 
     subgraph vision["5a. 图片识别分支"]
         direction TB
         V1["VisionService.analyze()<br/>图片 → Base64 编码"]
         V2["构建多模态请求体<br/>system + user(image_url+text)"]
         V3["POST /chat/completions<br/>→ DashScope<br/>Model: qwen-vl-max"]
         V4["解析 Response<br/>→ VisionResponse.choices[0]"]
         V5["返回识别文本"]
 
         V1 --> V2 --> V3 --> V4 --> V5
     end
 
     subgraph imagegen["5b. 图片生成分支"]
         direction TB
         I1["ImageGenerationService.generate()<br/>提取 prompt 描述"]
         I2["submitTask()<br/>POST /api/v1/services/...<br/>→ DashScope Wanx API<br/>Header: X-DashScope-Async: enable<br/>Model: wanx-v1"]
         I3["获取 taskId<br/>SubmitResponse.output.taskId"]
         I4["pollTaskResult()<br/>GET /api/v1/tasks/{taskId}<br/>每 2s 轮询<br/>max timeout=60s"]
         I5{任务状态?}
         I6["SUCCEEDED<br/>→ downloadImage()<br/>→ HTTP下载URL图片→byte[]"]
         I7["FAILED / 超时<br/>→ return null"]
 
         I1 --> I2 --> I3 --> I4 --> I5
         I5 -->|SUCCEEDED| I6
         I5 -->|FAILED| I7
         I5 -->|RUNNING| I4
     end
 
     subgraph chat["5c. 文本对话 &amp; 语音路由分支"]
         direction TB
         C1["ChatCompletionService<br/>structuredChat()<br/>自定义 system prompt:<br/>返回 JSON {reply, format}"]
         C2["构建 ChatCompletionRequest<br/>model: qwen-plus<br/>messages: [system, user]<br/>temperature: 0.7"]
         C3["POST /chat/completions<br/>→ DashScope<br/>Authorization: Bearer {key}<br/>RestClient"]
         C4["解析 Response<br/>→ ChatChoice.message.content"]
         C5["手动 JSON 反序列化<br/>ObjectMapper.readTree()<br/>→ root.reply, root.format"]
         C6{"format == voice?"}
         C7["AudioGenerationService<br/>generate()<br/>→ edge-tts 进程"]
         C71["构建 edge-tts 命令<br/>edge-tts / python3 -m edge_tts<br/>-t text --voice zh-CN-XiaoxiaoNeural<br/>--rate +0% --volume +0%<br/>--write-media tmp.mp3"]
         C72["ProcessBuilder 启动子进程<br/>waitFor(timeout=30s)"]
         C73["读取生成的 MP3 文件<br/>→ byte[]"]
         C74["清理临时文件"]
         C8["返回 MessageResponse<br/>type = TEXT / VOICE / IMAGE"]
 
         C1 --> C2 --> C3 --> C4 --> C5 --> C6
         C6 -->|是| C7
         C7 --> C71 --> C72 --> C73 --> C74 --> C8
         C6 -->|否| C8
     end
 
     subgraph send["6. 消息回复阶段"]
         direction LR
         E1["WeChatBotService<br/>sendResponse()<br/>→ switch(response.type)"]
         E2{"回复类型?"}
         E3["TEXT<br/>→ client.sendText()"]
         E4["IMAGE<br/>→ client.sendImage()"]
         E5["VOICE<br/>→ client.sendVoiceFile()<br/>(调用 sendFile 发送 MP3)"]
         E6["iLink SDK → 微信客户端<br/>聊天框内实时显示"]
 
         E1 --> E2
         E2 -->|TEXT| E3
         E2 -->|IMAGE| E4
         E2 -->|VOICE| E5
         E3 --> E6
         E4 --> E6
         E5 --> E6
     end
 
     subgraph monitor["7. 监控与运维"]
         direction LR
         M1["ChatApiStatsService<br/>AtomicLong 计数器<br/>successCount / failureCount"]
         M2["ChatApiStatus<br/>→ successCount, failureCount<br/>lastSuccessAt, lastFailureAt<br/>lastErrorMessage, lastCallOk"]
         M3["Web 前端轮询<br/>GET /api/wechat/status<br/>每 2s → 显示登录 + API 状态"]
         M4["Logback 日志<br/>com.example.wechat: INFO<br/>com.github.wechat.ilink.sdk: DEBUG"]
 
         M1 --> M2 --> M3
     end
 
     %% 全局连线
     S3 --> L1
     L7 --> R1
     H41 -->|extractImagePrompt| imagegen
     H42 -->|prompt| imagegen
     H3 -->|"图片 + 文字"| vision
     H5 -->|文本| chat
     vision --> C8
     imagegen --> C8
     C8 --> E1
     E6 -.->|继续对话| R1
 
     %% 样式
     classDef system fill:#e1f5fe,stroke:#0288d1,stroke-width:2px
     classDef process fill:#f3e5f5,stroke:#7b1fa2,stroke-width:2px
     classDef decision fill:#fff3e0,stroke:#f57c00,stroke-width:2px
     classDef external fill:#e8f5e9,stroke:#388e3c,stroke-width:2px
     classDef datastore fill:#fce4ec,stroke:#c62828,stroke-width:2px
     classDef monitor fill:#f5f5f5,stroke:#616161,stroke-width:1px
     class S2,S3 system
     class L2,L3,L4,R1,R2,R3,C2,C4,C5,V1,V2,V4,I1,I2,I4,I6,C71,C72,C73,M1 process
     class H3,H4,H5,I5,C6,E2 decision
     class E,F,G external
     class M2 datastore
 ```
 
 ---
 
 ### 所用技术栈汇总
 
 | 层级 | 技术 |
 |------|------|
 | 语言 & 运行时 | Java 21, Spring Boot 4.1.0 |
 | 构建工具 | Maven (多模块) |
 | 微信协议接入 | wechat-ilink-sdk v2.3.3 (微信硬解码协议 iLink SDK) |
 | LLM/对话 | 阿里云 DashScope API, Model: qwen-plus (通义千问) |
 | 视觉识别 | DashScope, Model: qwen-vl-max (通义千问视觉版) |
 | 图片生成 | DashScope Wanx API, Model: wanx-v1 (通义万相), 异步任务+轮询 |
 | 语音合成 | edge-tts (Edge TTS 命令行 / Python 模块) |
 | QR 码生成 | ZXing (com.google.zxing) |
 | 环境变量 | dotenv-java (io.github.cdimascio.dotenv-java) |
 | HTTP 客户端 | Spring RestClient (Spring Web) |
 | JSON 处理 | Jackson (ObjectMapper) |
 | 配置管理 | Spring @ConfigurationProperties |
 | 日志 | Logback + SLF4J |
 | 前端 | 原生 HTML/CSS/JS (控制台页面) |
 | Web 框架 | Spring MVC (@RestController) |
 | 线程调度 | ScheduledExecutorService (消息轮询) |
 | 并发工具 | CompletableFuture, AtomicLong, AtomicBoolean, CopyOnWriteArrayList |
