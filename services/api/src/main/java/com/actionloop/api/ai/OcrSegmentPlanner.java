package com.actionloop.api.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * 长截图分段规划（纯逻辑，便于单测）。
 * 相邻段保留重叠带，避免文字正好被切在边界；每段带段索引与纵向偏移，用于回到原图定位。
 */
public final class OcrSegmentPlanner {

    /** 一段的切片范围：[yOffset, yOffset + height)，index 从 0 开始。 */
    public record Segment(int index, int yOffset, int height, int overlapWithPrevious) {
        public int bottom() {
            return yOffset + height;
        }
    }

    public record Settings(boolean enabled, int longImageMinHeight, double longImageAspectRatio,
                           int segmentHeight, int segmentOverlap) {
    }

    private static final int HARD_MIN_SEGMENT_HEIGHT = 400;
    private static final int HARD_MIN_TAIL_HEIGHT = 200;

    private OcrSegmentPlanner() {
    }

    public static boolean isLongImage(int width, int height, Settings settings) {
        if (!settings.enabled() || width <= 0 || height <= 0) {
            return false;
        }
        if (height < settings.longImageMinHeight()) {
            return false;
        }
        return height >= width * Math.max(1.0, settings.longImageAspectRatio());
    }

    public static List<Segment> plan(int width, int height, Settings settings) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("IMAGE_DIMENSION_INVALID");
        }
        if (!isLongImage(width, height, settings)) {
            return List.of(new Segment(0, 0, height, 0));
        }
        int segmentHeight = Math.max(settings.segmentHeight(), HARD_MIN_SEGMENT_HEIGHT);
        int overlap = Math.min(Math.max(settings.segmentOverlap(), 0), segmentHeight / 2);
        if (height <= segmentHeight) {
            return List.of(new Segment(0, 0, height, 0));
        }

        List<Segment> segments = new ArrayList<>();
        int top = 0;
        int index = 0;
        while (true) {
            int bottom = Math.min(top + segmentHeight, height);
            int overlapWithPrevious = index == 0 ? 0 : overlap;
            segments.add(new Segment(index, top, bottom - top, overlapWithPrevious));
            index++;
            if (bottom >= height) {
                break;
            }
            top = bottom - overlap;
        }

        // 尾段过矮时并入上一段：避免模型只看到半行标题，同时保持切片覆盖连续、不丢内容。
        int minTail = Math.max(overlap + HARD_MIN_TAIL_HEIGHT, segmentHeight / 4);
        if (segments.size() > 1 && segments.getLast().height() < minTail) {
            segments.removeLast();
            Segment previous = segments.getLast();
            segments.set(segments.size() - 1, new Segment(previous.index(), previous.yOffset(),
                    height - previous.yOffset(), previous.overlapWithPrevious()));
        }
        return List.copyOf(segments);
    }
}
