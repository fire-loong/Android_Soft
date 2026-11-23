package com.actionloop.api.web.dto;

import com.actionloop.api.domain.OcrResult;
import com.actionloop.api.domain.OcrSegment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * OCR 结果视图。segments 仅在长截图分段识别时非空；不伪造 bbox 或坐标。
 */
public record OcrResultView(
        UUID id,
        UUID assetId,
        String provider,
        String model,
        String promptVersion,
        String inputSha256,
        String text,
        Integer imageWidth,
        Integer imageHeight,
        int segmentCount,
        boolean coordinatesAvailable,
        List<OcrSegmentView> segments,
        UUID createdBy,
        Instant createdAt
) {
    public static OcrResultView of(OcrResult result) {
        return of(result, List.of());
    }

    public static OcrResultView of(OcrResult result, List<OcrSegment> segments) {
        List<OcrSegmentView> segmentViews = segments == null ? List.of()
                : segments.stream().map(OcrSegmentView::of).toList();
        return new OcrResultView(result.getId(), result.getAssetId(), result.getProvider(), result.getModel(),
                result.getPromptVersion(), result.getInputSha256(), result.getText(),
                result.getImageWidth(), result.getImageHeight(),
                result.getSegmentCount() == null ? 1 : result.getSegmentCount(),
                result.isCoordinatesAvailable(), segmentViews,
                result.getCreatedBy(), result.getCreatedAt());
    }
}
