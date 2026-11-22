package com.actionloop.api.security;

import com.actionloop.api.domain.Device;
import com.actionloop.api.domain.DeviceStatus;
import com.actionloop.api.domain.User;
import com.actionloop.api.domain.UserStatus;
import com.actionloop.api.repository.DeviceRepository;
import com.actionloop.api.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * 从 Authorization: Bearer 解析 access token，校验设备仍处于 ACTIVE 后写入 SecurityContext。
 * 设备撤销（refresh_family 失效 / logout）后 access 立即失效，满足“权限撤销立即停止”（指导书）。
 * 令牌无效或过期时不抛异常，放行后由统一入口点返回 401 AUTH_INVALID。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final DeviceRepository deviceRepository;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, DeviceRepository deviceRepository,
                                   UserRepository userRepository) {
        this.jwtService = jwtService;
        this.deviceRepository = deviceRepository;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }
        try {
            Claims c = jwtService.parseAccessToken(header.substring(7));
            UUID userId = UUID.fromString(c.getSubject());
            UUID deviceId = UUID.fromString(c.get("deviceId", String.class));
            String email = c.get("email", String.class);

            Device device = deviceRepository.findById(deviceId).orElse(null);
            if (device == null || device.getStatus() != DeviceStatus.ACTIVE || !userId.equals(device.getUserId())) {
                SecurityContextHolder.clearContext();
                chain.doFilter(request, response);
                return;
            }
            // 账户状态每请求复核：锁定/注销即使设备仍 ACTIVE 也拒绝，使撤销立即生效（指导书 15.3）。
            User user = userRepository.findById(userId).orElse(null);
            if (user == null || user.getStatus() != UserStatus.ACTIVE) {
                SecurityContextHolder.clearContext();
                chain.doFilter(request, response);
                return;
            }

            UserPrincipal principal = new UserPrincipal(userId, deviceId, email);
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(principal, null, List.of());
            SecurityContextHolder.getContext().setAuthentication(auth);
        } catch (JwtException | IllegalArgumentException e) {
            SecurityContextHolder.clearContext();
        }
        chain.doFilter(request, response);
    }
}
