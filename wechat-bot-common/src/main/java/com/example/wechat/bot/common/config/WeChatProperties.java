package com.example.wechat.bot.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "wechat")
public class WeChatProperties {

    private Login login = new Login();
    private ILink ilink = new ILink();

    public Login getLogin() {
        return login;
    }

    public void setLogin(Login login) {
        this.login = login;
    }

    public ILink getIlink() {
        return ilink;
    }

    public void setIlink(ILink ilink) {
        this.ilink = ilink;
    }

    public static class Login {
        private boolean autoStart = false;

        public boolean isAutoStart() {
            return autoStart;
        }

        public void setAutoStart(boolean autoStart) {
            this.autoStart = autoStart;
        }
    }

    public static class ILink {
        private int connectTimeoutMs = 35000;
        private int readTimeoutMs = 35000;
        private int writeTimeoutMs = 35000;
        private int httpMaxRetries = 3;
        private int retryBaseDelayMs = 1000;
        private int retryMaxDelayMs = 10000;
        private boolean heartbeatEnabled = true;
        private int heartbeatIntervalMs = 30000;
        private String channelVersion = "1.0.0";

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }

        public int getWriteTimeoutMs() {
            return writeTimeoutMs;
        }

        public void setWriteTimeoutMs(int writeTimeoutMs) {
            this.writeTimeoutMs = writeTimeoutMs;
        }

        public int getHttpMaxRetries() {
            return httpMaxRetries;
        }

        public void setHttpMaxRetries(int httpMaxRetries) {
            this.httpMaxRetries = httpMaxRetries;
        }

        public int getRetryBaseDelayMs() {
            return retryBaseDelayMs;
        }

        public void setRetryBaseDelayMs(int retryBaseDelayMs) {
            this.retryBaseDelayMs = retryBaseDelayMs;
        }

        public int getRetryMaxDelayMs() {
            return retryMaxDelayMs;
        }

        public void setRetryMaxDelayMs(int retryMaxDelayMs) {
            this.retryMaxDelayMs = retryMaxDelayMs;
        }

        public boolean isHeartbeatEnabled() {
            return heartbeatEnabled;
        }

        public void setHeartbeatEnabled(boolean heartbeatEnabled) {
            this.heartbeatEnabled = heartbeatEnabled;
        }

        public int getHeartbeatIntervalMs() {
            return heartbeatIntervalMs;
        }

        public void setHeartbeatIntervalMs(int heartbeatIntervalMs) {
            this.heartbeatIntervalMs = heartbeatIntervalMs;
        }

        public String getChannelVersion() {
            return channelVersion;
        }

        public void setChannelVersion(String channelVersion) {
            this.channelVersion = channelVersion;
        }
    }
}
