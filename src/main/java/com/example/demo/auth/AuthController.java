package com.example.demo.auth;

import com.example.demo.aicare.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;
    private final LoginAttemptService loginAttemptService;

    public AuthController(AuthService authService, LoginAttemptService loginAttemptService) {
        this.authService = authService;
        this.loginAttemptService = loginAttemptService;
    }

    @PostMapping("/register")
    public Result<Map<String, Object>> register(@RequestBody Map<String, String> params) {
        String userName = params.get("userName");
        String password = params.get("password");
        String email = params.get("email");
        String phone = params.get("phone");

        logger.info("Register request received");

        Map<String, Object> result = authService.register(userName, password, email, phone);
        if ((Integer) result.get("code") == 200) {
            return Result.success(result);
        } else {
            return Result.error((String) result.get("message"));
        }
    }

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody Map<String, String> params,
                                             HttpServletRequest request) {
        String userName = params.get("userName");
        String password = params.get("password");

        if (isBlank(userName) || isBlank(password)) {
            return Result.error(400, "用户名和密码不能为空");
        }

        String attemptKey = loginAttemptService.buildKey(userName, request.getRemoteAddr());
        if (loginAttemptService.isBlocked(attemptKey)) {
            return Result.error(429, "登录失败次数过多，请稍后再试");
        }

        logger.info("Login request received");

        Map<String, Object> result = authService.login(userName, password);
        if ((Integer) result.get("code") == 200) {
            HttpSession oldSession = request.getSession(false);
            if (oldSession != null) {
                oldSession.invalidate();
            }
            HttpSession session = request.getSession(true);
            session.setAttribute("user", userName);
            loginAttemptService.recordSuccess(attemptKey);
            return Result.success(result);
        } else {
            loginAttemptService.recordFailure(attemptKey);
            return Result.error((String) result.get("message"));
        }
    }

    @PostMapping("/logout")
    public Result<String> logout(HttpSession session) {
        session.invalidate();
        logger.info("User logged out");
        return Result.success("退出成功");
    }

    @GetMapping("/me")
    public Result<Map<String, Object>> me(HttpSession session) {
        String userName = (String) session.getAttribute("user");
        if (userName == null) {
            return Result.error("未登录");
        }
        return Result.success(Map.of("userName", userName));
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
