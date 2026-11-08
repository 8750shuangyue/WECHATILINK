package com.example.demo.ai;

import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;

/** 一个已注册的 @Tool 方法。包内可见，由 ToolRegistry 与 ToolCallingService 共用。 */
final class ToolInfo {

    final Object target;
    final Method method;
    final Tool annotation;

    ToolInfo(Object target, Method method, Tool annotation) {
        this.target = target;
        this.method = method;
        this.annotation = annotation;
    }
}
