package com.example.wechat.bot.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "chat.audio-gen")
public class AudioGenProperties {

    private String voice = "zh-CN-XiaoxiaoNeural";
    private String rate = "+0%";
    private String volume = "+0%";
    private int timeoutSeconds = 60;
    private String edgeTtsPath = "edge-tts";

    public String getVoice() {
        return voice;
    }

    public void setVoice(String voice) {
        this.voice = voice;
    }

    public String getRate() {
        return rate;
    }

    public void setRate(String rate) {
        this.rate = rate;
    }

    public String getVolume() {
        return volume;
    }

    public void setVolume(String volume) {
        this.volume = volume;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public String getEdgeTtsPath() {
        return edgeTtsPath;
    }

    public void setEdgeTtsPath(String edgeTtsPath) {
        this.edgeTtsPath = edgeTtsPath;
    }
}
