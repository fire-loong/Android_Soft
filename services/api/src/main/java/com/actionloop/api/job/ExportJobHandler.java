package com.actionloop.api.job;

import com.actionloop.api.service.ExportService;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** 异步生成空间导出快照（指导书 13.2）；复用 {@link ExportService#process} 保持状态与审计一致。 */
@Component
public class ExportJobHandler implements JobHandler {

    private final ExportService exportService;

    public ExportJobHandler(ExportService exportService) {
        this.exportService = exportService;
    }

    @Override
    public String type() {
        return ExportService.JOB_TYPE;
    }

    @Override
    public void execute(JobExecution execution) {
        String requestId = execution.optionalText("requestId");
        if (requestId != null) {
            MDC.put("requestId", requestId);
        }
        try {
            UUID exportId = execution.requiredUuid("exportId");
            exportService.process(exportId);
        } finally {
            if (requestId != null) {
                MDC.remove("requestId");
            }
        }
    }
}
