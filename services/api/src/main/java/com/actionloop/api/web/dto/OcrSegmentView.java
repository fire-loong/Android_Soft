package com.actionloop.api.web.dto;

import com.actionloop.api.domain.OcrSegment;

import java.util.UUID;

/** 长截图单段的定位信息：段序 + 原图纵向偏移，用于在 Web 端回溯原图。 */
public record OcrSegmentView(
        UUID id,
        int index,
        int yOffset,
        int height,
        String text
) {
    public static OcrSegmentView of(OcrSegment segment) {
        return new OcrSegmentView(segment.getId(), segment.getSegmentIndex(), segment.getYOffset(),
                segment.getHeight(), segment.getText());
    }
}
