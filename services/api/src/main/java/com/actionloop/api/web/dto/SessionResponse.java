package com.actionloop.api.web.dto;

/** 登录/注册/刷新返回的会话：access 短期，refresh 可轮换；expiresIn 单位秒。 */
public record SessionResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UserResponse user
) {
    public static SessionResponse of(String accessToken, String refreshToken, long expiresIn, UserResponse user) {
        return new SessionResponse(accessToken, refreshToken, "Bearer", expiresIn, user);
    }
}
