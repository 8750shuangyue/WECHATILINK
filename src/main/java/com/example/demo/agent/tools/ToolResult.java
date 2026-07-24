package com.example.demo.agent.tools;

import com.alibaba.fastjson2.JSON;

import java.io.Serializable;

public class ToolResult<T> implements Serializable {

    private boolean success;
    private String message;
    private T data;

    private ToolResult() {
    }

    public static <T> ToolResult<T> success(T data) {
        ToolResult<T> result = new ToolResult<>();
        result.success = true;
        result.data = data;
        return result;
    }

    public static <T> ToolResult<T> success(String message, T data) {
        ToolResult<T> result = new ToolResult<>();
        result.success = true;
        result.message = message;
        result.data = data;
        return result;
    }

    public static <T> ToolResult<T> failure(String message) {
        ToolResult<T> result = new ToolResult<>();
        result.success = false;
        result.message = message;
        return result;
    }

    public static <T> ToolResult<T> failure(String message, T data) {
        ToolResult<T> result = new ToolResult<>();
        result.success = false;
        result.message = message;
        result.data = data;
        return result;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public String toJsonString() {
        return JSON.toJSONString(this);
    }

    @Override
    public String toString() {
        return toJsonString();
    }
}