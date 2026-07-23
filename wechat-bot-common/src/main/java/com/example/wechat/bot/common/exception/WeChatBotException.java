package com.example.wechat.bot.common.exception;

public class WeChatBotException extends RuntimeException {

    private final String code;

    public WeChatBotException(String message) {
        super(message);
        this.code = "UNKNOWN";
    }

    public WeChatBotException(String code, String message) {
        super(message);
        this.code = code;
    }

    public WeChatBotException(String message, Throwable cause) {
        super(message, cause);
        this.code = "UNKNOWN";
    }

    public WeChatBotException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static WeChatBotException loginFailed(String message) {
        return new WeChatBotException("LOGIN_FAILED", message);
    }

    public static WeChatBotException loginFailed(String message, Throwable cause) {
        return new WeChatBotException("LOGIN_FAILED", message, cause);
    }

    public static WeChatBotException sendFailed(String message) {
        return new WeChatBotException("SEND_FAILED", message);
    }

    public static WeChatBotException sendFailed(String message, Throwable cause) {
        return new WeChatBotException("SEND_FAILED", message, cause);
    }

    public static WeChatBotException chatApiFailed(String message) {
        return new WeChatBotException("CHAT_API_FAILED", message);
    }

    public static WeChatBotException chatApiFailed(String message, Throwable cause) {
        return new WeChatBotException("CHAT_API_FAILED", message, cause);
    }

    public static WeChatBotException configError(String message) {
        return new WeChatBotException("CONFIG_ERROR", message);
    }
}
