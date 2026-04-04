package com.aimeeting.room.controller;

import com.aimeeting.room.common.ApiResponse;
import com.aimeeting.room.config.TokenStore;
import com.aimeeting.room.dao.AiUserMapper;
import com.aimeeting.room.dto.LoginRequest;
import com.aimeeting.room.entity.AiUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AiUserMapper userMapper;
    private final TokenStore tokenStore;
    private final BCryptPasswordEncoder passwordEncoder;

    /** 登录 */
    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest req) {
        AiUser user = userMapper.selectByUsername(req.getUsername());
        if (user == null || !passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            return ApiResponse.fail("用户名或密码错误");
        }
        String token = tokenStore.createToken(user.getId());
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("userId", user.getId());
        data.put("nickname", user.getNickname());
        data.put("username", user.getUsername());
        log.info("User logged in: {}", user.getUsername());
        return ApiResponse.ok(data);
    }

    /** 注册 */
    @PostMapping("/register")
    public ApiResponse<Void> register(@Valid @RequestBody LoginRequest req) {
        if (userMapper.selectByUsername(req.getUsername()) != null) {
            return ApiResponse.fail("用户名已存在");
        }
        AiUser user = new AiUser();
        user.setUsername(req.getUsername());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setNickname(req.getUsername());
        userMapper.insert(user);
        return ApiResponse.ok(null);
    }

    /** 获取当前用户信息 */
    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        AiUser user = userMapper.selectById(userId);
        if (user == null) return ApiResponse.fail("用户不存在");
        Map<String, Object> data = new HashMap<>();
        data.put("userId", user.getId());
        data.put("username", user.getUsername());
        data.put("nickname", user.getNickname());
        return ApiResponse.ok(data);
    }

    /** 退出登录 */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        String token = request.getHeader("X-Token");
        if (token != null) tokenStore.removeToken(token);
        return ApiResponse.ok(null);
    }
}
