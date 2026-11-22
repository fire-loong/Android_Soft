package com.actionloop.api.service.storage;

import java.util.Map;

/** 客户端应如何上传字节到对象存储。 */
public record UploadTarget(
        String method,
        String url,
        Map<String, String> headers,
        /** true 表示字节先经过本 API 代理写入（local）；false 表示直连对象存储（s3 预签名）。 */
        boolean proxied
) {
    public static UploadTarget proxied(String url) {
        return new UploadTarget("PUT", url, Map.of(), true);
    }
    public static UploadTarget direct(String method, String url, Map<String, String> headers) {
        return new UploadTarget(method, url, headers, false);
    }
}
