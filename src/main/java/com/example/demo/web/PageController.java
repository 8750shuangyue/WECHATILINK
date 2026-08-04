package com.example.demo.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/login")
    public String login() {
        return "forward:/login.html";
    }

    @GetMapping("/register")
    public String register() {
        return "forward:/register.html";
    }

    @GetMapping("/home")
    public String home() {
        return "forward:/home.html";
    }

    @GetMapping("/care")
    public String care() {
        return "forward:/care.html";
    }

    @GetMapping("/chat")
    public String chat() {
        return "forward:/chat.html";
    }

    @GetMapping("/shop")
    public String shop() {
        return "forward:/shop.html";
    }

    @GetMapping("/shop-publish")
    public String shopPublish() {
        return "forward:/shop-publish.html";
    }
}