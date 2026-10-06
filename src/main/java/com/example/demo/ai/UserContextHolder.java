package com.example.demo.ai;

public class UserContextHolder {

    private static final ThreadLocal<String> USER_HOLDER = new ThreadLocal<>();

    public static void setUserId(String userId) {
        USER_HOLDER.set(userId);
    }

    public static String getUserId() {
        return USER_HOLDER.get();
    }

    public static void clear() {
        USER_HOLDER.remove();
    }
}