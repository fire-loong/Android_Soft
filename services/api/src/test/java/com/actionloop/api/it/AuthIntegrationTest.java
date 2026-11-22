package com.actionloop.api.it;

import com.actionloop.api.domain.UserStatus;
import com.actionloop.api.repository.UserRepository;
import com.actionloop.api.test.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 认证集成：refresh 令牌重用检测 + 锁定账户后旧 access token 立即失效（JWT 过滤器复核 status）。
 */
class AuthIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void refreshTokenReuseIsDetectedAndRevokesSession() {
        var u = register();
        Resp first = req("POST", "/api/v1/auth/refresh", null, Map.of("refreshToken", u.get("refresh")), null);
        assertEquals(200, first.status, first.raw);

        Resp reuse = req("POST", "/api/v1/auth/refresh", null, Map.of("refreshToken", u.get("refresh")), null);
        assertEquals(401, reuse.status, "refresh reuse should be rejected: " + reuse.raw);
        assertEquals("AUTH_INVALID", errCode(reuse));
    }

    @Test
    void lockedUserAccessTokenAndRefreshAreRejected() {
        var u = register();

        userRepository.findById(UUID.fromString(u.get("userId"))).ifPresent(user -> {
            user.setStatus(UserStatus.LOCKED);
            userRepository.save(user);
        });

        Resp me = req("GET", "/api/v1/me", u.get("access"), null, null);
        assertEquals(401, me.status, "locked user access token should be rejected: " + me.raw);
        assertEquals("AUTH_INVALID", errCode(me));

        Resp refresh = req("POST", "/api/v1/auth/refresh", null, Map.of("refreshToken", u.get("refresh")), null);
        assertEquals(401, refresh.status, "locked user refresh should be rejected: " + refresh.raw);
        assertEquals("AUTH_INVALID", errCode(refresh));
    }
}
