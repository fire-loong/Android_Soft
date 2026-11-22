package com.actionloop.api.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 待核验完成凭证（指导书 9.2 协同空间）：跨事项汇总，含事项/步骤标题，便于核验人逐条处理。
 */
public record PendingCompletionView(
        UUID completionId,
        UUID taskId,
        String taskTitle,
        UUID stepId,
        String stepTitle,
        UUID submitterId,
        String statement,
        Instant submittedAt,
        List<UUID> assetIds
) {
}
