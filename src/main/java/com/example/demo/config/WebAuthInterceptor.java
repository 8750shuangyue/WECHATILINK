package com.example.demo.config;

import com.alibaba.fastjson2.JSON;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.HashMap;
import java.util.Map;

/**
 * 接口鉴权拦截器（方案 A：Session + 拦截器）
 * - 拦截 /api/** 与 /uploads/**，除白名单外均要求登录
 * - 未登录统一返回 HTTP 401 + {"code":500,"message":"未登录"}
 * - 已登录时把 userName 放入 request attribute，供后续 Controller 复用
 */
@Component
public class WebAuthInterceptor implements HandlerInterceptor {

    /** 免登录接口白名单 */
    private static final String[] PUBLIC_API = {
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/me"
    };

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // CORS 预检请求直接放行
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        String uri = request.getRequestURI();
        for (String p : PUBLIC_API) {
            if (uri.equals(p)) {
                return true;
            }
        }

        HttpSession session = request.getSession(false);
        Object userName = (session != null) ? session.getAttribute("user") : null;
        if (userName != null) {
            request.setAttribute("userName", userName);
            return true;
        }

        // 未登录：401 + 与 Result 一致的 JSON 结构，前端两种判断方式均可识别
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = new HashMap<>();
        body.put("code", 500);
        body.put("message", "未登录");
        body.put("data", null);
        response.getWriter().write(JSON.toJSONString(body));
        return false;
    }
}
