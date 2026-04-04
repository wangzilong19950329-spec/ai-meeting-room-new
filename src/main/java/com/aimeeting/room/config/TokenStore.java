package com.aimeeting.room.config;

import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 简单内存 Token 存储（重启后失效，生产环境可替换为 Redis）
 */
@Component
public class TokenStore {

    // token -> userId
    private final Map<String, Long> tokenMap = new ConcurrentHashMap<>();
    // userId -> token
    private final Map<Long, String> userTokenMap = new ConcurrentHashMap<>();

    public String createToken(Long userId) {
        // 踢掉旧 token
        String oldToken = userTokenMap.get(userId);
        if (oldToken != null) tokenMap.remove(oldToken);

        String token = UUID.randomUUID().toString().replace("-", "");
        tokenMap.put(token, userId);
        userTokenMap.put(userId, token);
        return token;
    }

    public Long getUserId(String token) {
        if (token == null) return null;
        return tokenMap.get(token);
    }

    public void removeToken(String token) {
        Long userId = tokenMap.remove(token);
        if (userId != null) userTokenMap.remove(userId);
    }
}
