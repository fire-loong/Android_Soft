package com.actionloop.api.web.dto;

import java.util.UUID;

/**
 * 发布流程版本。VERIFIED 需要复核人与创建人不同；仅个人使用时允许 PERSONAL_UNVERIFIED，
 * 但必须显式传 allowPersonalUnverified=true，且 UI 必须标注“个人经验，非官方”。
 */
public record PublishProcessVersionRequest(
        UUID reviewerId,
        Boolean allowPersonalUnverified,
        String reviewerNote
) {
}
