package com.actionloop.api.config;

import com.actionloop.api.web.AssistWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** 协助 WebSocket 端点（指导书 13.5）。origin 白名单由环境注入，Android 原生客户端无 Origin。 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final AssistWebSocketHandler handler;
    private final AssistWebSocketHandler.AuthHandshakeInterceptor interceptor;
    private final String[] allowedOrigins;

    public WebSocketConfig(AssistWebSocketHandler handler,
                           AssistWebSocketHandler.AuthHandshakeInterceptor interceptor,
                           @Value("${app.websocket.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
                           String allowedOrigins) {
        this.handler = handler;
        this.interceptor = interceptor;
        this.allowedOrigins = allowedOrigins.split(",");
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/assist/{sessionId}")
                .addInterceptors(interceptor)
                .setAllowedOriginPatterns(allowedOrigins);
    }
}
