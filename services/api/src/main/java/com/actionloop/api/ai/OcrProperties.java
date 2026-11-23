package com.actionloop.api.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 云端 OCR 配置。默认模型不等于自动上传，每次识别仍须请求级单独授权。 */
@Component
@ConfigurationProperties(prefix = "app.ocr")
public class OcrProperties {

    private static final Set<String> SUPPORTED_MODELS = Set.of(
            "xoppaddleocrv16", "xopdeepseekocr", "xophunyuanocr");

    private String provider = "maas";
    private String baseUrl = "https://maas-api.cn-huabei-1.xf-yun.com/v2";
    private String apiKey = "";
    private String model = "xoppaddleocrv16";
    private List<String> allowedModels = new ArrayList<>(List.of(
            "xoppaddleocrv16", "xopdeepseekocr", "xophunyuanocr"));
    private int timeoutSeconds = 45;
    private long retryBackoffMillis = 250;

    /** 长截图分段：仅在“高/宽比与绝对高度都达到阈值”时切片，普通图片保持单次识别原样。 */
    private boolean segmentEnabled = true;
    private int longImageMinHeight = 1600;
    private double longImageAspectRatio = 2.0;
    private int segmentHeight = 1400;
    private int segmentOverlap = 180;
    private int maxSegments = 12;
    private long maxImagePixels = 60_000_000L;

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

    public List<String> getAllowedModels() {
        return allowedModels;
    }

    public void setAllowedModels(List<String> allowedModels) {
        this.allowedModels = allowedModels;
    }

    /** 配置只能缩小固定模型集合，不能把通用 LLM 或其他模型扩张进 OCR 白名单。 */
    public boolean isModelAllowed(String model) {
        return SUPPORTED_MODELS.contains(model) && allowedModels.contains(model);
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

    public boolean isSegmentEnabled() {
        return segmentEnabled;
    }

    public void setSegmentEnabled(boolean segmentEnabled) {
        this.segmentEnabled = segmentEnabled;
    }

    public int getLongImageMinHeight() {
        return longImageMinHeight;
    }

    public void setLongImageMinHeight(int longImageMinHeight) {
        this.longImageMinHeight = longImageMinHeight;
    }

    public double getLongImageAspectRatio() {
        return longImageAspectRatio;
    }

    public void setLongImageAspectRatio(double longImageAspectRatio) {
        this.longImageAspectRatio = longImageAspectRatio;
    }

    public int getSegmentHeight() {
        return segmentHeight;
    }

    public void setSegmentHeight(int segmentHeight) {
        this.segmentHeight = segmentHeight;
    }

    public int getSegmentOverlap() {
        return segmentOverlap;
    }

    public void setSegmentOverlap(int segmentOverlap) {
        this.segmentOverlap = segmentOverlap;
    }

    public int getMaxSegments() {
        return maxSegments;
    }

    public void setMaxSegments(int maxSegments) {
        this.maxSegments = maxSegments;
    }

    public long getMaxImagePixels() {
        return maxImagePixels;
    }

    public void setMaxImagePixels(long maxImagePixels) {
        this.maxImagePixels = maxImagePixels;
    }
}
