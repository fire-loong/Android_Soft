package com.actionloop.api.web.dto;

import com.actionloop.api.domain.EvidenceRef;

import java.util.UUID;

public record EvidenceRefView(
        UUID id,
        UUID sourceItemId,
        UUID assetId,
        Integer pageNo,
        Integer charStart,
        Integer charEnd,
        String quote,
        String bboxJson
) {
    public static EvidenceRefView of(EvidenceRef r) {
        return new EvidenceRefView(r.getId(), r.getSourceItemId(), r.getAssetId(), r.getPageNo(),
                r.getCharStart(), r.getCharEnd(), r.getQuoteText(), r.getBboxJson());
    }
}
