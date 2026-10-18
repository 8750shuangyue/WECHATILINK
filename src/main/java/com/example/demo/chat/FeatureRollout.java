package com.example.demo.chat;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 按用户名单做灰度：空名单表示功能开启后全量生效。
 */
public final class FeatureRollout {

    private FeatureRollout() {
    }

    public static boolean includes(String userId, String canaryUsers) {
        Set<String> allowed = parse(canaryUsers);
        if (allowed.isEmpty()) {
            return true;
        }
        return userId != null && !userId.isBlank() && allowed.contains(userId.trim());
    }

    public static Set<String> parse(String canaryUsers) {
        if (canaryUsers == null || canaryUsers.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(canaryUsers.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
