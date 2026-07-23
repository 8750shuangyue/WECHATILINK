package com.example.wechat.bot.adapter.wechat;

import com.example.wechat.bot.common.config.WeChatProperties;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.listener.OnDisconnectListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class WeChatClientService {

    private static final Logger logger = LoggerFactory.getLogger(WeChatClientService.class);

    @Autowired
    private WeChatProperties weChatProperties;

    @Autowired
    private WeChatEventDispatcher eventDispatcher;

    private ILinkClient client;
    private String qrCodeContent;
    private boolean loggedIn = false;
    private final ScheduledExecutorService messagePoller = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean autoLoginTriggered = new AtomicBoolean(false);

    private static final long RECONNECT_BASE_MS = 5_000;
    private static final long RECONNECT_MAX_MS = 300_000;
    private static final int RECONNECT_MAX_ATTEMPTS = 20;

    private final ScheduledExecutorService reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "reconnect-worker");
        t.setDaemon(true);
        return t;
    });
    private volatile int reconnectAttempts = 0;
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private volatile boolean pollingStarted = false;

    @PostConstruct
    public void init() {
        WeChatProperties.ILink ilinkConfig = weChatProperties.getIlink();
        ILinkConfig config = ILinkConfig.builder()
                .connectTimeoutMs(ilinkConfig.getConnectTimeoutMs())
                .readTimeoutMs(ilinkConfig.getReadTimeoutMs())
                .writeTimeoutMs(ilinkConfig.getWriteTimeoutMs())
                .httpMaxRetries(ilinkConfig.getHttpMaxRetries())
                .retryBaseDelayMs(ilinkConfig.getRetryBaseDelayMs())
                .retryMaxDelayMs(ilinkConfig.getRetryMaxDelayMs())
                .heartbeatEnabled(ilinkConfig.isHeartbeatEnabled())
                .heartbeatIntervalMs(ilinkConfig.getHeartbeatIntervalMs())
                .channelVersion(ilinkConfig.getChannelVersion())
                .build();

        client = ILinkClient.builder()
                .config(config)
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext context) {
                        logger.info("登录成功，botId = {}", context.getBotId());
                        loggedIn = true;
                        reconnecting.set(false);
                        reconnectAttempts = 0;
                        eventDispatcher.dispatchLoginStatus("登录成功！botId: " + context.getBotId());
                        startMessagePolling();
                    }

                    @Override
                    public void onLoginFailure(Throwable throwable) {
                        logger.error("登录失败: {}", throwable.getMessage(), throwable);
                        eventDispatcher.dispatchLoginStatus("登录失败: " + throwable.getMessage());
                        // 重连中的登录失败（如二维码过期）→ 继续下一轮重试
                        if (reconnecting.get()) {
                            reconnectAttempts++;
                            scheduleReconnectAttempt();
                        }
                    }
                })
                .onMessage(new OnMessageListener() {
                    @Override
                    public void onMessages(List<WeixinMessage> messages) {
                        for (WeixinMessage msg : messages) {
                            logger.info("收到消息 fromUserId = {}", msg.getFrom_user_id());
                            eventDispatcher.dispatchMessage(msg);
                        }
                    }
                })
                .onDisconnect(new OnDisconnectListener() {
                    @Override
                    public void onDisconnect(Throwable throwable) {
                        logger.warn("SDK 连接断开: {}", throwable.getMessage());
                        loggedIn = false;
                        eventDispatcher.dispatchLoginStatus("连接断开: " + throwable.getMessage());
                        startReconnect();
                    }

                    @Override
                    public void onReconnectStart(int attempt) {
                        logger.info("SDK 自动重连尝试 {}...", attempt);
                    }

                    @Override
                    public void onReconnectSuccess() {
                        logger.info("SDK 自动重连成功");
                        loggedIn = true;
                        eventDispatcher.dispatchLoginStatus("重连成功");
                    }

                    @Override
                    public void onReconnectFailed(Throwable cause) {
                        logger.warn("SDK 自动重连失败: {}", cause.getMessage());
                        // onDisconnect 会跟随触发，由它启动自定义重连
                    }
                })
                .build();

        if (weChatProperties.getLogin().isAutoStart()) {
            triggerLoginAsync();
        }
    }

    public String startLogin() {
        try {
            qrCodeContent = client.executeLogin();
            logger.info("二维码内容已获取");
            logger.info("二维码内容: {}", qrCodeContent);
            eventDispatcher.dispatchQrCode(qrCodeContent);
            eventDispatcher.dispatchLoginStatus("请扫描二维码登录...");

            CompletableFuture.runAsync(() -> {
                try {
                    client.getLoginFuture().get();
                } catch (Exception e) {
                    logger.error("等待登录结果异常", e);
                }
            });

            return qrCodeContent;
        } catch (Exception e) {
            logger.error("启动登录失败", e);
            eventDispatcher.dispatchLoginStatus("启动登录失败: " + e.getMessage());
            throw new RuntimeException("启动登录失败", e);
        }
    }

    private void triggerLoginAsync() {
        if (!autoLoginTriggered.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                startLogin();
            } catch (Exception e) {
                logger.error("自动启动登录失败", e);
            }
        });
    }

    private void startMessagePolling() {
        if (pollingStarted) {
            return;
        }
        pollingStarted = true;
        messagePoller.scheduleAtFixedRate(() -> {
            try {
                if (!loggedIn) return;
                List<WeixinMessage> messages = client.getUpdates();
                if (!messages.isEmpty()) {
                    logger.info("拉取到 {} 条新消息", messages.size());
                }
            } catch (Exception e) {
                logger.error("拉取消息异常", e);
            }
        }, 1, 2, TimeUnit.SECONDS);
    }

    private void startReconnect() {
        if (!reconnecting.compareAndSet(false, true)) {
            logger.info("已在重连中，跳过");
            return;
        }
        reconnectAttempts = 0;
        logger.warn("开始自动重连流程");
        scheduleReconnectAttempt();
    }

    private void scheduleReconnectAttempt() {
        if (reconnectAttempts >= RECONNECT_MAX_ATTEMPTS) {
            logger.error("重连已达上限 ({} 次)，停止自动重连", RECONNECT_MAX_ATTEMPTS);
            eventDispatcher.dispatchLoginStatus("重连失败次数过多，请手动刷新页面重新登录");
            reconnecting.set(false);
            return;
        }

        long delay = calculateBackoff(reconnectAttempts);
        logger.info("计划 {}ms 后重连（第 {} 次）", delay, reconnectAttempts + 1);

        reconnectScheduler.schedule(() -> {
            try {
                attemptReconnect();
            } catch (Exception e) {
                logger.error("重连尝试异常", e);
                reconnectAttempts++;
                scheduleReconnectAttempt();
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private static long calculateBackoff(int attempt) {
        long delay = RECONNECT_BASE_MS * (1L << Math.min(attempt, 6));
        delay = Math.min(delay, RECONNECT_MAX_MS);
        delay += (long) (delay * (Math.random() - 0.5) * 0.5);
        return Math.max(delay, 1_000);
    }

    private void attemptReconnect() {
        logger.info("开始重连...");
        try {
            String newQrCode = client.executeLogin();
            qrCodeContent = newQrCode;
            logger.info("新二维码已获取");
            eventDispatcher.dispatchQrCode(newQrCode);
            eventDispatcher.dispatchLoginStatus("连接已断开，请扫描新二维码重新登录");

            // 登录结果由 onLoginSuccess / onLoginFailure 异步通知，reconnecting 保持 true 直到登录成功
        } catch (Exception e) {
            logger.error("重连获取二维码失败: {}", e.getMessage());
            reconnectAttempts++;
            scheduleReconnectAttempt();
        }
    }

    public void sendText(String userId, String text) {
        try {
            client.sendText(userId, text);
            logger.info("发送消息成功: userId={}, text={}", userId, text);
        } catch (Exception e) {
            logger.error("发送消息失败", e);
            throw new RuntimeException("发送消息失败", e);
        }
    }

    public void sendImage(String userId, byte[] imageBytes, String fileName, String description) {
        try {
            client.sendImage(userId, imageBytes, fileName, description);
            logger.info("发送图片成功: userId={}, fileName={}", userId, fileName);
        } catch (Exception e) {
            logger.error("发送图片失败", e);
            throw new RuntimeException("发送图片失败", e);
        }
    }

    public byte[] downloadImage(MessageItem item) {
        try {
            return client.downloadImageFromMessageItem(item);
        } catch (Exception e) {
            logger.error("下载图片失败", e);
            return null;
        }
    }

    /**
     * 下载消息中的文件内容
     *
     * @param item 消息项
     * @return 文件字节数组，失败返回 null
     */
    public byte[] downloadFile(MessageItem item) {
        try {
            return client.downloadFileFromMessageItem(item);
        } catch (Exception e) {
            logger.error("下载文件失败", e);
            return null;
        }
    }

    public void sendVoiceFile(String userId, byte[] audioBytes) {
        try {
            client.sendFile(userId, audioBytes, "语音回复.mp3", null);
            logger.info("发送语音成功: userId={}", userId);
        } catch (Exception e) {
            logger.error("发送语音失败", e);
            throw new RuntimeException("发送语音失败", e);
        }
    }

    public void startTyping(String userId) {
        try {
            client.startTyping(userId);
        } catch (Exception e) {
            logger.warn("启动输入态失败: {}", e.getMessage());
        }
    }

    public void stopTyping(String userId) {
        try {
            client.stopTyping(userId);
        } catch (Exception e) {
            logger.warn("停止输入态失败: {}", e.getMessage());
        }
    }

    public void sendTextWithTyping(String userId, String text) {
        try {
            client.sendTextWithTyping(userId, text, 800);
            logger.info("发送带输入态的消息成功: userId={}", userId);
        } catch (Exception e) {
            logger.error("发送带输入态的消息失败", e);
            throw new RuntimeException("发送消息失败", e);
        }
    }

    public boolean isLoggedIn() {
        return loggedIn;
    }

    public String getQrCodeContent() {
        return qrCodeContent;
    }

    @PreDestroy
    public void destroy() {
        messagePoller.shutdown();
        reconnectScheduler.shutdown();
        if (client != null) {
            client.close();
        }
    }
}
