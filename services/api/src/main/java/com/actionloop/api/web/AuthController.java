package com.actionloop.api.web;

import com.actionloop.api.security.UserPrincipal;
import com.actionloop.api.service.AuthService;
import com.actionloop.api.web.dto.LoginRequest;
import com.actionloop.api.web.dto.MeResponse;
import com.actionloop.api.web.dto.RefreshRequest;
import com.actionloop.api.web.dto.RegisterRequest;
import com.actionloop.api.web.dto.SessionResponse;
import com.actionloop.shared.web.ApiResponse;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账户与设备会话端点（指导书 13.x）：注册/登录/刷新轮换/注销 + 当前用户。
 * 除 /auth/* 外均需 access token；错误一律走统一 ApiError 格式。
 */
@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    private String reqId() {
        String id = MDC.get(RequestIdFilter.MDC_KEY);
        return id != null ? id : "req_unknown";
    }

    @PostMapping("/auth/register")
    public ApiResponse<SessionResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.of(authService.register(request), reqId());
    }

    @PostMapping("/auth/login")
    public ApiResponse<SessionResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.of(authService.login(request), reqId());
    }

    @PostMapping("/auth/refresh")
    public ApiResponse<SessionResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.of(authService.refresh(request), reqId());
    }

    @PostMapping("/auth/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal UserPrincipal me) {
        authService.logout(me);
        return ApiResponse.of(null, reqId());
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me(@AuthenticationPrincipal UserPrincipal me) {
        return ApiResponse.of(authService.me(me), reqId());
    }
}
