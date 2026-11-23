package com.actionloop.api.job;

import com.actionloop.api.service.OcrService;
import com.actionloop.api.web.dto.OcrRequest;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** 复用 OcrService.recognize：外部调用、成员关系复核、失败证据和按模型缓存都保持同一实现。 */
@Component
public class AssetOcrJobHandler implements JobHandler {

    static final String TYPE = "ASSET_OCR";

    private final OcrService ocrService;

    public AssetOcrJobHandler(OcrService ocrService) {
        this.ocrService = ocrService;
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
            UUID workspaceId = execution.requiredUuid("workspaceId");
            UUID assetId = execution.requiredUuid("assetId");
            String model = execution.requiredText("model");
            ocrService.recognize(execution.requestedBy(), assetId,
                    new OcrRequest(workspaceId, true, model), execution.id());
        } finally {
            if (requestId != null) {
                MDC.remove("requestId");
            }
        }
    }
}
