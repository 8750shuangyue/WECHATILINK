package com.example.demo.tts;

import com.example.demo.config.XunfeiTtsConfig;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.*;
import okio.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Service
public class XfTtsService {

    private static final Logger logger = LoggerFactory.getLogger(XfTtsService.class);

    private static final String TTS_HOST = "tts-api.xfyun.cn";
    private static final String TTS_PATH = "/v2/tts";
    private static final String TTS_SCHEME = "wss";

    private final OkHttpClient okHttpClient;
    private final XunfeiTtsConfig config;

    public XfTtsService(XunfeiTtsConfig config) {
        this.config = config;
        this.okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 将文本合成MP3音频
     * @param text 待合成的文本
     * @return MP3音频字节数组
     * @throws Exception 合成失败时抛出异常
     */
    public byte[] synthesizeToMp3(String text) throws Exception {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("文本内容不能为空");
        }

        String websocketUrl = buildWebSocketUrl();
        logger.info("TTS WebSocket URL: {}", websocketUrl);

        String requestBody = buildRequestBody(text);
        logger.debug("TTS request body length: {}", requestBody.length());

        CountDownLatch completionLatch = new CountDownLatch(1);
        ByteArrayCollector audioCollector = new ByteArrayCollector();
        ExceptionHolder exceptionHolder = new ExceptionHolder();

        Request request = new Request.Builder()
                .url(websocketUrl)
                .build();

        WebSocket webSocket = okHttpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                logger.info("TTS WebSocket connection opened");
                webSocket.send(requestBody);
                logger.debug("TTS request sent");
            }

            @Override
            public void onMessage(WebSocket webSocket, String textMessage) {
                logger.debug("Received text message: {}", textMessage.length() > 200 ? textMessage.substring(0, 200) + "..." : textMessage);
                try {
                    JSONObject json = JSON.parseObject(textMessage);
                    int code = json.getIntValue("code");
                    if (code != 0) {
                        String message = json.getString("message");
                        String sid = json.getString("sid");
                        exceptionHolder.setException(new RuntimeException(
                                String.format("TTS合成失败: code=%d, message=%s, sid=%s", code, message, sid)));
                        webSocket.close(1000, "Error");
                        completionLatch.countDown();
                        return;
                    }

                    JSONObject data = json.getJSONObject("data");
                    if (data != null) {
                        String audioBase64 = data.getString("audio");
                        if (audioBase64 != null && !audioBase64.isEmpty()) {
                            byte[] audioBytes = Base64.getDecoder().decode(audioBase64);
                            logger.debug("Decoded audio chunk, size: {} bytes", audioBytes.length);
                            audioCollector.add(audioBytes);
                        }

                        int status = data.getIntValue("status");
                        if (status == 2) {
                            logger.info("TTS synthesis completed");
                            webSocket.close(1000, "Completed");
                            completionLatch.countDown();
                        }
                    }
                } catch (Exception e) {
                    logger.error("Failed to parse text message", e);
                    exceptionHolder.setException(e);
                    webSocket.close(1000, "Parse error");
                    completionLatch.countDown();
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                logger.warn("Unexpected binary message received, size: {} bytes", bytes.size());
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                logger.error("TTS WebSocket failure", t);
                exceptionHolder.setException(new RuntimeException("WebSocket连接失败: " + t.getMessage(), t));
                completionLatch.countDown();
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                logger.info("TTS WebSocket closed: code={}, reason={}", code, reason);
            }
        });

        boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
        if (!completed) {
            logger.error("TTS synthesis timeout");
            webSocket.close(1000, "Timeout");
            throw new RuntimeException("TTS合成超时");
        }

        if (exceptionHolder.getException() != null) {
            throw exceptionHolder.getException();
        }

        byte[] audioData = audioCollector.toByteArray();
        logger.info("TTS synthesis completed, audio size: {} bytes, format: {}", audioData.length, config.getAue());

        if (audioData.length == 0) {
            throw new RuntimeException("TTS合成返回空音频");
        }

        if ("lame".equals(config.getAue())) {
            String hexHeader = bytesToHex(audioData, Math.min(10, audioData.length));
            logger.info("MP3 file header (hex): {}", hexHeader);
            
            boolean isValidMp3 = isValidMp3Header(audioData);
            if (!isValidMp3) {
                logger.warn("Generated file may not be a valid MP3. First 200 bytes as string: {}", 
                    new String(audioData, StandardCharsets.UTF_8).substring(0, Math.min(200, audioData.length)));
            }
        }

        if ("raw".equals(config.getAue())) {
            byte[] wavData = addWavHeader(audioData, config.getSampleRate());
            logger.info("WAV file generated, size: {} bytes", wavData.length);
            return wavData;
        }

        return audioData;
    }

    private boolean isValidMp3Header(byte[] data) {
        if (data.length < 4) {
            return false;
        }
        
        if ((data[0] & 0xFF) == 0x49 && (data[1] & 0xFF) == 0x44 && 
            (data[2] & 0xFF) == 0x33) {
            return true;
        }
        
        if ((data[0] & 0xFF) == 0xFF && ((data[1] & 0xF0) == 0xF0)) {
            return true;
        }
        
        return false;
    }

    private String bytesToHex(byte[] bytes, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length && i < bytes.length; i++) {
            sb.append(String.format("%02X ", bytes[i] & 0xFF));
        }
        return sb.toString().trim();
    }

    /**
     * 构建WebSocket连接URL，包含鉴权参数
     * 鉴权算法：
     * 1. 生成RFC1123格式日期
     * 2. 构建canonical string: host + "\n" + date + "\n" + "GET /v2/tts HTTP/1.1"
     * 3. HMAC-SHA256签名，密钥为apiSecret
     * 4. Base64编码签名
     * 5. 构建authorization_origin并Base64编码
     * 6. 拼接最终URL
     */
    private String buildWebSocketUrl() throws Exception {
        String date = generateRfc1123Date();
        String canonicalString = String.format("host: %s\ndate: %s\nGET %s HTTP/1.1", TTS_HOST, date, TTS_PATH);

        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(config.getApiSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKeySpec);
        byte[] signatureBytes = mac.doFinal(canonicalString.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getEncoder().encodeToString(signatureBytes);

        String authorizationOrigin = String.format(
                "api_key=\"%s\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"%s\"",
                config.getApiKey(), signature);
        String authorization = Base64.getEncoder().encodeToString(authorizationOrigin.getBytes(StandardCharsets.UTF_8));

        String encodedDate = java.net.URLEncoder.encode(date, StandardCharsets.UTF_8);

        return String.format("%s://%s%s?authorization=%s&date=%s&host=%s",
                TTS_SCHEME, TTS_HOST, TTS_PATH, authorization, encodedDate, TTS_HOST);
    }

    /**
     * 生成RFC1123格式的日期字符串
     * 格式：EEE, dd MMM yyyy HH:mm:ss z
     * 时区：GMT
     */
    private String generateRfc1123Date() {
        DateTimeFormatter formatter = DateTimeFormatter.RFC_1123_DATE_TIME;
        return ZonedDateTime.now(ZoneId.of("GMT")).format(formatter);
    }

    /**
     * 构建TTS请求体JSON
     */
    private String buildRequestBody(String text) {
        JSONObject common = new JSONObject();
        common.put("app_id", config.getAppId());

        JSONObject business = new JSONObject();
        business.put("aue", config.getAue());
        business.put("vcn", config.getVcn());
        if ("lame".equals(config.getAue())) {
            business.put("sfl", 1);
            business.put("auf", "audio/L16;rate=" + config.getSampleRate());
        } else if ("raw".equals(config.getAue())) {
            business.put("auf", "audio/L16;rate=" + config.getSampleRate());
        }
        business.put("speed", config.getSpeed());
        business.put("volume", config.getVolume());
        business.put("pitch", config.getPitch());
        business.put("tte", "UTF8");

        String base64Text = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        JSONObject data = new JSONObject();
        data.put("status", 2);
        data.put("text", base64Text);

        JSONObject request = new JSONObject();
        request.put("common", common);
        request.put("business", business);
        request.put("data", data);

        return request.toString();
    }

    /**
     * 为原始PCM数据添加WAV文件头
     * WAV格式参数：单声道、16位、指定采样率
     */
    private byte[] addWavHeader(byte[] pcmData, int sampleRate) {
        int channels = 1;
        int bitsPerSample = 16;
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int dataSize = pcmData.length;
        int fileSize = 36 + dataSize;

        try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
            baos.write(new byte[]{'R', 'I', 'F', 'F'});
            baos.write(intToLittleEndian(fileSize));
            baos.write(new byte[]{'W', 'A', 'V', 'E'});
            baos.write(new byte[]{'f', 'm', 't', ' '});
            baos.write(intToLittleEndian(16));
            baos.write(shortToLittleEndian((short) 1));
            baos.write(shortToLittleEndian((short) channels));
            baos.write(intToLittleEndian(sampleRate));
            baos.write(intToLittleEndian(byteRate));
            baos.write(shortToLittleEndian((short) blockAlign));
            baos.write(shortToLittleEndian((short) bitsPerSample));
            baos.write(new byte[]{'d', 'a', 't', 'a'});
            baos.write(intToLittleEndian(dataSize));
            baos.write(pcmData);
            return baos.toByteArray();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to generate WAV header", e);
        }
    }

    private byte[] intToLittleEndian(int value) {
        byte[] result = new byte[4];
        result[0] = (byte) (value & 0xFF);
        result[1] = (byte) ((value >> 8) & 0xFF);
        result[2] = (byte) ((value >> 16) & 0xFF);
        result[3] = (byte) ((value >> 24) & 0xFF);
        return result;
    }

    private byte[] shortToLittleEndian(short value) {
        byte[] result = new byte[2];
        result[0] = (byte) (value & 0xFF);
        result[1] = (byte) ((value >> 8) & 0xFF);
        return result;
    }

    /**
     * 字节数组收集器，用于收集WebSocket返回的音频数据片段
     */
    private static class ByteArrayCollector {
        private final java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();

        public void add(byte[] bytes) {
            try {
                baos.write(bytes);
            } catch (java.io.IOException e) {
                throw new RuntimeException("Failed to write bytes", e);
            }
        }

        public byte[] toByteArray() {
            return baos.toByteArray();
        }
    }

    /**
     * 异常持有者，用于在WebSocket回调中传递异常
     */
    private static class ExceptionHolder {
        private volatile Exception exception;

        public Exception getException() {
            return exception;
        }

        public void setException(Exception exception) {
            this.exception = exception;
        }
    }
}
