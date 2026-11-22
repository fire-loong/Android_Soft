package com.actionloop.api.web.dto;

import java.util.Map;

public record UploadTargetView(
        String method,
        String url,
        Map<String, String> headers,
        boolean proxied
) {
}
