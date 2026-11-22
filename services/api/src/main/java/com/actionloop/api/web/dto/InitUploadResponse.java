package com.actionloop.api.web.dto;

import java.util.UUID;

/**
 * 初始化上传响应。existing 非空表示同空间同 sha256 已存在（去重命中），无需再传字节；
 * 否则返回 upload 目标地址，客户端 PUT 字节后调用 complete。
 */
public record InitUploadResponse(
        UUID sessionId,
        UploadTargetView upload,
        Long expiresInSeconds,
        AssetView existing
) {
}
