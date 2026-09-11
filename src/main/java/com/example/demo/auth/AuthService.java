package com.example.demo.auth;

import com.example.demo.chat.entity.User;
import com.example.demo.chat.repository.mysql.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

@Service
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,20}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^[0-9+\\-\\s]{5,30}$");

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    public Map<String, Object> register(String userName, String password, String email, String phone) {
        Map<String, Object> result = new HashMap<>();

        String validationMessage = validateRegistration(userName, password, email, phone);
        if (validationMessage != null) {
            result.put("code", 400);
            result.put("message", validationMessage);
            return result;
        }

        userName = userName.trim();
        if (userRepository.existsByUserName(userName)) {
            result.put("code", 500);
            result.put("message", "用户名已存在");
            return result;
        }

        String encodedPassword = passwordEncoder.encode(password);
        User user = new User(userName, encodedPassword, email, phone);
        userRepository.save(user);

        logger.info("User registered");
        
        result.put("code", 200);
        result.put("message", "注册成功");
        return result;
    }

    public Map<String, Object> login(String userName, String password) {
        Map<String, Object> result = new HashMap<>();

        if (userName == null || userName.trim().isEmpty() || password == null || password.isEmpty()) {
            result.put("code", 400);
            result.put("message", "用户名或密码错误");
            return result;
        }

        userName = userName.trim();
        
        var optionalUser = userRepository.findByUserName(userName);
        if (optionalUser.isEmpty()) {
            result.put("code", 500);
            result.put("message", "用户名或密码错误");
            return result;
        }

        User user = optionalUser.get();
        if (!passwordEncoder.matches(password, user.getPassword())) {
            result.put("code", 500);
            result.put("message", "用户名或密码错误");
            return result;
        }

        user.setLastLoginTime(LocalDateTime.now());
        userRepository.save(user);

        logger.info("User login succeeded");
        
        result.put("code", 200);
        result.put("message", "登录成功");
        result.put("data", Map.of("userName", userName));
        return result;
    }

    public User getUserByName(String userName) {
        return userRepository.findByUserName(userName).orElse(null);
    }

    private String validateRegistration(String userName, String password, String email, String phone) {
        if (userName == null || !USERNAME_PATTERN.matcher(userName.trim()).matches()) {
            return "用户名需为3-20位字母、数字或下划线";
        }
        if (password == null || password.length() < 8) {
            return "密码至少需要8位";
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            return "密码过长";
        }
        if (email != null && !email.trim().isEmpty() && !EMAIL_PATTERN.matcher(email.trim()).matches()) {
            return "邮箱格式不正确";
        }
        if (phone != null && !phone.trim().isEmpty() && !PHONE_PATTERN.matcher(phone.trim()).matches()) {
            return "手机号格式不正确";
        }
        return null;
    }
}
