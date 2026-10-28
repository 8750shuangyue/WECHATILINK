package com.example.demo.kb;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识库写入白名单。默认关闭：未显式开启时任何用户都不能上传/删除公共知识库。
 */
@Component
public class KbWriteAccessGuard {

    private final boolean writesEnabled;
    private final Set<String> adminUsers;

    public KbWriteAccessGuard(@Value("${app.kb.writes-enabled:false}") boolean writesEnabled,
                              @Value("${app.kb.admin-users:}") String adminUsers) {
        this.writesEnabled = writesEnabled;
        this.adminUsers = Arrays.stream(adminUsers.split(","))
                .map(String::trim)
                .filter(user -> !user.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean canWrite(String userName) {
        return writesEnabled && userName != null && adminUsers.contains(userName);
    }
}
