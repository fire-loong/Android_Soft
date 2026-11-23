package com.actionloop.shared.error;

/**
 * 统一错误码，与指导书第 13.4 节一一对应。客户端按 code 做确定性处理，禁止依赖 HTTP 文案。
 */
public enum ErrorCode {
    VALIDATION_FAILED(400),
    AUTH_EXPIRED(401),
    AUTH_INVALID(401),
    PERMISSION_DENIED(403),
    ASSIST_SCOPE_DENIED(403),
    RESOURCE_NOT_FOUND(404),
    VERSION_CONFLICT(409),
    DUPLICATE_OPERATION(409),
    INVALID_STATE_TRANSITION(409),
    INVITE_EXPIRED(410),
    SOURCE_EXPIRED(410),
    SESSION_EXPIRED(410),
    QUOTA_EXCEEDED(403),
    FILE_TOO_LARGE(413),
    UNSUPPORTED_MEDIA_TYPE(415),
    UPLOAD_INCOMPLETE(409),
    AI_OUTPUT_INVALID(422),
    OCR_OUTPUT_INVALID(422),
    ASSET_INTEGRITY_MISMATCH(422),
    SOURCE_CLAIM_UNVERIFIED(422),
    UNSUPPORTED_SYNC_OPERATION(400),
    RATE_LIMITED(429),
    AI_UNAVAILABLE(503),
    OCR_UNAVAILABLE(503),
    TURN_UNAVAILABLE(503),
    SEARCH_UNAVAILABLE(503),
    INTERNAL_ERROR(500);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
