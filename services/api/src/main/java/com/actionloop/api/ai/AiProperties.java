package com.actionloop.api.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** AI Provider 配置（指导书 14.x）。密钥只从环境注入，禁止提交仓库。 */
@Component
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

    /** deterministic | qwen */
    private String provider = "deterministic";
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private String apiKey = "";
    private String model = "qwen-plus";
    private int timeoutSeconds = 20;
    private double minConfidenceForReminder = 0.6;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public double getMinConfidenceForReminder() {
        return minConfidenceForReminder;
    }

    public void setMinConfidenceForReminder(double minConfidenceForReminder) {
        this.minConfidenceForReminder = minConfidenceForReminder;
    }
}
