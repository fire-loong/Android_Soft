package com.actionloop.api.web;

import com.actionloop.shared.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * 系统探测接口：验证请求链路、requestId 传播与统一响应信封。
 * 真正的健康检查由 Spring Actuator /actuator/health 提供（不暴露敏感详情）。
 */
@Tag(name = "system", description = "系统探测")
@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    @Operation(summary = "存活探测")
    @GetMapping("/ping")
    public ApiResponse<Map<String, Object>> ping() {
        return ApiResponse.of(Map.of(
                "pong", true,
                "service", "actionloop-api",
                "time", Instant.now().toString()
        ), MDC.get(RequestIdFilter.MDC_KEY));
    }
}
