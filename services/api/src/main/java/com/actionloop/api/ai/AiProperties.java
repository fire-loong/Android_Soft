package com.actionloop.api.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** AI Provider 配置（指导书 14.x）。密钥只从环境注入，禁止提交仓库。 */
@Component
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

    /** deterministic | maas */
    private String provider = "deterministic";
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private String apiKey = "";
    private String model = "qwen-plus";
    private int timeoutSeconds = 20;
    private long retryBackoffMillis = 250;
    private double minConfidenceForReminder = 0.6;
    private String embeddingModel = "xop3qwen8bembedding";
    private int embeddingDim = 768;
    private boolean embeddingEnabled = true;
    private String rerankModel = "xop3qwen8breranker";
    private boolean rerankEnabled = true;
    private double semanticThreshold = 0.20;

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

    public long getRetryBackoffMillis() {
        return retryBackoffMillis;
    }

    public void setRetryBackoffMillis(long retryBackoffMillis) {
        this.retryBackoffMillis = retryBackoffMillis;
    }

    public double getMinConfidenceForReminder() {
        return minConfidenceForReminder;
    }

    public void setMinConfidenceForReminder(double minConfidenceForReminder) {
        this.minConfidenceForReminder = minConfidenceForReminder;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public int getEmbeddingDim() {
        return embeddingDim;
    }

    public void setEmbeddingDim(int embeddingDim) {
        this.embeddingDim = embeddingDim;
    }

    public boolean isEmbeddingEnabled() {
        return embeddingEnabled;
    }

    public void setEmbeddingEnabled(boolean embeddingEnabled) {
        this.embeddingEnabled = embeddingEnabled;
    }

    public String getRerankModel() {
        return rerankModel;
    }

    public void setRerankModel(String rerankModel) {
        this.rerankModel = rerankModel;
    }

    public boolean isRerankEnabled() {
        return rerankEnabled;
    }

    public void setRerankEnabled(boolean rerankEnabled) {
        this.rerankEnabled = rerankEnabled;
    }

    public double getSemanticThreshold() {
        return semanticThreshold;
    }

    public void setSemanticThreshold(double semanticThreshold) {
        this.semanticThreshold = semanticThreshold;
    }
}
