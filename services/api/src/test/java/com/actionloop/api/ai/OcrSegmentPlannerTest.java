package com.actionloop.api.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OcrSegmentPlannerTest {

    private static OcrSegmentPlanner.Settings settings() {
        return new OcrSegmentPlanner.Settings(true, 1600, 2.0, 1400, 180);
    }

    @Test
    void ordinaryImageStaysSingleSegment() {
        assertFalse(OcrSegmentPlanner.isLongImage(1200, 1500, settings()));
        List<OcrSegmentPlanner.Segment> plan = OcrSegmentPlanner.plan(1200, 1500, settings());
        assertEquals(1, plan.size());
        assertEquals(0, plan.getFirst().yOffset());
        assertEquals(1500, plan.getFirst().height());
    }

    @Test
    void longScreenshotIsContiguousWithOverlap() {
        List<OcrSegmentPlanner.Segment> plan = OcrSegmentPlanner.plan(1080, 4000, settings());

        assertTrue(plan.size() > 1);
        assertEquals(0, plan.getFirst().yOffset());
        assertEquals(4000, plan.getLast().bottom());
        for (int i = 1; i < plan.size(); i++) {
            OcrSegmentPlanner.Segment previous = plan.get(i - 1);
            OcrSegmentPlanner.Segment current = plan.get(i);
            assertEquals(previous.index() + 1, current.index());
            assertTrue(current.yOffset() < previous.bottom(), "段间必须有重叠，避免边界文字被切断");
            assertTrue(previous.bottom() <= current.bottom(), "段序必须与图片自上而下一致");
        }
    }

    @Test
    void tooShortTailIsMergedIntoPreviousSegment() {
        // 1080x4000 按 1400/180 规划会产出 340px 的尾段，应并入上一段而不是让模型只看到半行字。
        List<OcrSegmentPlanner.Segment> plan = OcrSegmentPlanner.plan(1080, 4000, settings());

        assertEquals(3, plan.size());
        OcrSegmentPlanner.Segment last = plan.getLast();
        assertEquals(4000, last.bottom());
        assertTrue(last.height() >= 380);
    }

    @Test
    void overlapIsCappedAtHalfSegmentHeight() {
        OcrSegmentPlanner.Settings aggressive = new OcrSegmentPlanner.Settings(true, 1600, 2.0, 1400, 5000);
        List<OcrSegmentPlanner.Segment> plan = OcrSegmentPlanner.plan(1080, 4000, aggressive);

        for (OcrSegmentPlanner.Segment segment : plan) {
            assertTrue(segment.overlapWithPrevious() <= 700);
        }
    }

    @Test
    void disabledSegmentationKeepsWholeImage() {
        OcrSegmentPlanner.Settings disabled = new OcrSegmentPlanner.Settings(false, 1600, 2.0, 1400, 180);

        assertFalse(OcrSegmentPlanner.isLongImage(1080, 12000, disabled));
        assertEquals(1, OcrSegmentPlanner.plan(1080, 12000, disabled).size());
    }
}
