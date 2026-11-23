package com.actionloop.api.job;

import com.actionloop.api.ai.CandidateExtraction;
import com.actionloop.api.ai.CandidateExtractionService;
import com.actionloop.api.ai.ExtractionRequest;
import com.actionloop.api.service.SourceService;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 异步来源候选抽取：外部 AI/规则调用在数据库事务外执行，落库时重新锁定来源与成员关系，
 * 避免租约过期后的重复写或撤权后写入。
 */
@Component
public class SourceAiExtractionJobHandler implements JobHandler {

    static final String TYPE = "SOURCE_AI_EXTRACTION";

    private final CandidateExtractionService extractionService;
    private final SourceService sourceService;

    public SourceAiExtractionJobHandler(CandidateExtractionService extractionService,
                                        SourceService sourceService) {
        this.extractionService = extractionService;
        this.sourceService = sourceService;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void execute(JobExecution execution) {
        String requestId = execution.optionalText("requestId");
        if (requestId != null) {
            MDC.put("requestId", requestId);
        }
        try {
            UUID sourceId = execution.requiredUuid("sourceId");
            String text = execution.requiredText("text");
            ExtractionRequest request = sourceService.buildExtractionRequest(
                    execution.workspaceId(), sourceId, execution.requestedBy(), text,
                    execution.optionalText("timezone"));
            CandidateExtraction extraction = extractionService.extract(request);
            sourceService.persistExtraction(execution.workspaceId(), sourceId,
                    execution.requestedBy(), extraction);
        } finally {
            if (requestId != null) {
                MDC.remove("requestId");
            }
        }
    }
}
