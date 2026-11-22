package com.actionloop.api.web;

import com.actionloop.shared.error.ApiError;
import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常 -> 统一错误格式（指导书 13.4）。
 * 错误响应不含堆栈、SQL、密钥或原文；技术细节仅写入服务端日志（日志亦需脱敏）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private String requestId() {
        String id = MDC.get(RequestIdFilter.MDC_KEY);
        return id != null ? id : "req_unknown";
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusiness(BusinessException ex) {
        ApiError body = ApiError.of(ex.code(), ex.getMessage(), requestId(), ex.details());
        return ResponseEntity.status(ex.code().httpStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe -> fields.put(fe.getField(), fe.getDefaultMessage()));
        details.put("fields", fields);
        ApiError body = ApiError.of(ErrorCode.VALIDATION_FAILED, "请求参数校验失败", requestId(), details);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraint(ConstraintViolationException ex) {
        ApiError body = ApiError.of(ErrorCode.VALIDATION_FAILED, ex.getMessage(), requestId(), null);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        ApiError body = ApiError.of(ErrorCode.VALIDATION_FAILED, "请求体格式错误或不是合法 JSON", requestId(), null);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
        ApiError body = ApiError.of(ErrorCode.PERMISSION_DENIED, "无权执行该操作", requestId(), null);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDuplicate(DataIntegrityViolationException ex) {
        log.warn("data integrity violation requestId={}", requestId());
        ApiError body = ApiError.of(ErrorCode.DUPLICATE_OPERATION,
                "数据冲突，操作可能已完成或唯一约束被违反", requestId(), null);
        return ResponseEntity.status(ErrorCode.DUPLICATE_OPERATION.httpStatus()).body(body);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleVersionConflict(OptimisticLockingFailureException ex) {
        log.warn("optimistic lock conflict requestId={}", requestId());
        ApiError body = ApiError.of(ErrorCode.VERSION_CONFLICT, "数据已被他人修改，请刷新后重试", requestId(), null);
        return ResponseEntity.status(ErrorCode.VERSION_CONFLICT.httpStatus()).body(body);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(MissingServletRequestParameterException ex) {
        ApiError body = ApiError.of(ErrorCode.VALIDATION_FAILED,
                "缺少必需参数: " + ex.getParameterName(), requestId(), null);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        ApiError body = ApiError.of(ErrorCode.VALIDATION_FAILED,
                "参数格式错误: " + ex.getName(), requestId(), null);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex) {
        ApiError body = ApiError.of(ErrorCode.RESOURCE_NOT_FOUND, "请求的资源不存在", requestId(), null);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception ex) {
        log.error("unhandled exception requestId={}", requestId(), ex);
        ApiError body = ApiError.of(ErrorCode.INTERNAL_ERROR, "服务器内部错误，请稍后重试", requestId(), null);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
