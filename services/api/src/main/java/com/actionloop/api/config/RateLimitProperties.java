package com.actionloop.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 认证端点限流（指导书 15.3）：按客户端 IP 固定窗口计数，防注册轰炸与登录暴力破解。
 * 各端点阈值与窗口可通过环境变量覆盖；设为 0 表示关闭该端点限流。
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    private Endpoint register = new Endpoint(5, 600);
    private Endpoint login = new Endpoint(10, 60);
    private Endpoint refresh = new Endpoint(30, 60);

    public static class Endpoint {
        private int maxRequests;
        private int windowSeconds;
        public Endpoint() {}
        public Endpoint(int maxRequests, int windowSeconds) {
            this.maxRequests = maxRequests;
            this.windowSeconds = windowSeconds;
        }
        public int getMaxRequests() { return maxRequests; }
        public void setMaxRequests(int maxRequests) { this.maxRequests = maxRequests; }
        public int getWindowSeconds() { return windowSeconds; }
        public void setWindowSeconds(int windowSeconds) { this.windowSeconds = windowSeconds; }
    }

    public Endpoint getRegister() { return register; }
    public void setRegister(Endpoint register) { this.register = register; }
    public Endpoint getLogin() { return login; }
    public void setLogin(Endpoint login) { this.login = login; }
    public Endpoint getRefresh() { return refresh; }
    public void setRefresh(Endpoint refresh) { this.refresh = refresh; }
}
