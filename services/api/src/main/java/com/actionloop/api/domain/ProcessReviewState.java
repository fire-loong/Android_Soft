package com.actionloop.api.domain;

/** 流程版本审核状态（指导书 6.2）：与完成凭证的 ReviewState 语义不同，单独建模。 */
public enum ProcessReviewState {
    DRAFT,
    IN_REVIEW,
    VERIFIED,
    PERSONAL_UNVERIFIED,
    EXPIRED
}
