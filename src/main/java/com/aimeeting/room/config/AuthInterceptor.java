package com.aimeeting.room.config;

import com.alibaba.fastjson.JSON;
import com.aimeeting.room.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final TokenStore tokenStore;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        String token = request.getHeader("X-Token");
        if (token == null) token = request.getParameter("token");

        Long userId = tokenStore.getUserId(token);
        if (userId == null) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(JSON.toJSONString(ApiResponse.fail("未登录，请先登录")));
            return false;
        }
        // 把 userId 放入请求属性，方便 Controller 使用
        request.setAttribute("currentUserId", userId);
        return true;
    }
}
