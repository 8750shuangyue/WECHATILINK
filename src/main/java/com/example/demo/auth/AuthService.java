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

@Service
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    public Map<String, Object> register(String userName, String password, String email, String phone) {
        Map<String, Object> result = new HashMap<>();
        
        if (userRepository.existsByUserName(userName)) {
            result.put("code", 500);
            result.put("message", "用户名已存在");
            return result;
        }

        String encodedPassword = passwordEncoder.encode(password);
        User user = new User(userName, encodedPassword, email, phone);
        userRepository.save(user);

        logger.info("User registered: {}", userName);
        
        result.put("code", 200);
        result.put("message", "注册成功");
        return result;
    }

    public Map<String, Object> login(String userName, String password) {
        Map<String, Object> result = new HashMap<>();
        
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

        logger.info("User logged in: {}", userName);
        
        result.put("code", 200);
        result.put("message", "登录成功");
        result.put("data", Map.of("userName", userName));
        return result;
    }

    public User getUserByName(String userName) {
        return userRepository.findByUserName(userName).orElse(null);
    }
}