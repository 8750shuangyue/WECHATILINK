package com.example.demo.auth;

import com.example.demo.aicare.Result;
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

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public Result<Map<String, Object>> register(@RequestBody Map<String, String> params) {
        String userName = params.get("userName");
        String password = params.get("password");
        String email = params.get("email");
        String phone = params.get("phone");

        logger.info("Register request: {}", userName);

        Map<String, Object> result = authService.register(userName, password, email, phone);
        if ((Integer) result.get("code") == 200) {
            return Result.success(result);
        } else {
            return Result.error((String) result.get("message"));
        }
    }

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody Map<String, String> params, HttpSession session) {
        String userName = params.get("userName");
        String password = params.get("password");

        logger.info("Login request: {}", userName);

        Map<String, Object> result = authService.login(userName, password);
        if ((Integer) result.get("code") == 200) {
            session.setAttribute("user", userName);
            return Result.success(result);
        } else {
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
}