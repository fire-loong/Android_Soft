package com.actionloop.api.config;

import com.actionloop.api.security.JwtAuthenticationFilter;
import com.actionloop.api.security.RateLimitFilter;
import com.actionloop.api.web.IdempotencyFilter;
import com.actionloop.api.web.RequestIdFilter;
import com.actionloop.shared.error.ApiError;
import com.actionloop.shared.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * 安全基线（阶段 1）：无状态；健康/契约/ping 放行，其余需认证（阶段 2 接入 JWT 过滤器）。
 * 认证失败/越权均返回统一错误格式（指导书 13.4），不泄露对象是否存在于其他空间。
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PUBLIC = {
            "/actuator/health", "/actuator/health/**", "/actuator/info",
            "/api/v1/system/ping",
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            // WebSocket 在握手阶段用 access_token + 会话参与者校验，不经过 Bearer 过滤器
            "/ws/**",
            // 证据下载走短期签名 token（指导书 11.6），不依赖 Bearer，便于新标签页打开
            "/api/v1/assets/*/download"
    };

    /** 接口文档默认不公开（指导书 15.x）；仅当显式开启时才加入放行列表。 */
    private static final String[] API_DOCS = {
            "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper,
                                                   JwtAuthenticationFilter jwtAuthenticationFilter,
                                                   IdempotencyFilter idempotencyFilter,
                                                   RateLimitFilter rateLimitFilter,
                                                   CorsProperties corsProperties,
                                                   @Value("${app.api-docs.public:false}") boolean apiDocsPublic) throws Exception {
        String[] publicPaths = apiDocsPublic
                ? Stream.concat(Arrays.stream(PUBLIC), Arrays.stream(API_DOCS)).toArray(String[]::new)
                : PUBLIC;
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource(corsProperties)))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(publicPaths).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(rateLimitFilter, JwtAuthenticationFilter.class)
                .addFilterAfter(idempotencyFilter, JwtAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> writeError(res, objectMapper, ErrorCode.AUTH_INVALID, "未认证或登录已过期"))
                        .accessDeniedHandler((req, res, e) -> writeError(res, objectMapper, ErrorCode.PERMISSION_DENIED, "无权执行该操作")));
        return http.build();
    }

    /** #17 CORS 白名单：仅放行配置来源，携带凭证，禁止全放行（指导书 15.x）。 */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProperties props) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(props.resolvedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization", "Content-Disposition", "X-Request-Id", "Retry-After"));
        config.setAllowCredentials(props.isAllowCredentials());
        config.setMaxAge(props.getMaxAgeSeconds());
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /** Argon2id 密码哈希（指导书 5.x）。 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    private void writeError(HttpServletResponse res, ObjectMapper mapper, ErrorCode code, String message) throws java.io.IOException {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId == null) {
            requestId = res.getHeader(RequestIdFilter.HEADER);
        }
        res.setStatus(code.httpStatus());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        mapper.writeValue(res.getWriter(), ApiError.of(code, message, requestId, null));
    }
}
