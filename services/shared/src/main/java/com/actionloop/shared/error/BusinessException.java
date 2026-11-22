package com.actionloop.shared.error;

import java.util.Map;

/**
 * 业务异常：携带确定错误码与可选 details，由全局异常处理器映射为统一错误响应。
 * 不允许用异常泄露内部细节；message 面向用户，技术原因写入日志（日志亦需脱敏）。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> details;

    public BusinessException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public BusinessException(ErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
