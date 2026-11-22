package com.actionloop.api.web.dto;

import java.util.List;
import java.util.UUID;

/** 提交步骤完成凭证（指导书 13.2 POST /steps/{id}/completions）。 */
public record SubmitCompletionRequest(
        String statement,
        List<UUID> assetIds
) {
}
