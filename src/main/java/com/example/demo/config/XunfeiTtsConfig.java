package com.example.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "xunfei.tts")
public class XunfeiTtsConfig {

    private String appId;
    private String apiKey;
    private String apiSecret;
    private String vcn = "xiaoyan";
    private String aue = "lame";
    private int sampleRate = 16000;
    private int speed = 50;
    private int volume = 50;
    private int pitch = 50;

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiSecret() {
        return apiSecret;
    }

    public void setApiSecret(String apiSecret) {
        this.apiSecret = apiSecret;
    }

    public String getVcn() {
        return vcn;
    }

    public void setVcn(String vcn) {
        this.vcn = vcn;
    }

    public String getAue() {
        return aue;
    }

    public void setAue(String aue) {
        this.aue = aue;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public void setSampleRate(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    public int getSpeed() {
        return speed;
    }

    public void setSpeed(int speed) {
        this.speed = speed;
    }

    public int getVolume() {
        return volume;
    }

    public void setVolume(int volume) {
        this.volume = volume;
    }

    public int getPitch() {
        return pitch;
    }

    public void setPitch(int pitch) {
        this.pitch = pitch;
    }
}
