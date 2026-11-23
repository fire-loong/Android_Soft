package com.actionloop.api.ai;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class RuleExtractorTest {

    @Test
    void fullChineseDateKeepsSingleRawQuoteAndResolvedTime() {
        ExtractionRequest request = new ExtractionRequest("请于2026年11月30日18:00前提交PDF。",
                Instant.parse("2026-11-22T02:00:00Z"), "Asia/Shanghai", "班级群", null,
                null, null, null, null);

        CandidateExtraction.CandidateDraft result = new RuleExtractor().extract(request).candidates().getFirst();

        assertEquals("2026年11月30日18:00", result.rawDueText());
        assertEquals("2026-11-30T10:00:00Z", result.dueAt());
        assertFalse(result.uncertainties().contains("MULTIPLE_DATES"));
    }

    @Test
    void conflictingDatesNeverProduceAbsoluteDeadline() {
        ExtractionRequest request = new ExtractionRequest("初稿11月28日提交，终稿11月30日18:00前提交。",
                Instant.parse("2026-11-22T02:00:00Z"), "Asia/Shanghai", "班级群", null,
                null, null, null, null);

        CandidateExtraction.CandidateDraft result = new RuleExtractor().extract(request).candidates().getFirst();

        assertNull(result.dueAt());
        assertNull(result.dueDate());
        assertTrue(result.reasons().contains("MULTIPLE_DATES"));
        assertTrue(result.needsUserConfirmation());
    }

    @Test
    void dateWithCommonTaskPhraseStillCountsAsTask() {
        // 「交」不在 ACTION_WORDS 中，但「交作业」是真实任务，不得因 A-04 降级而丢失提醒。
        ExtractionRequest request = new ExtractionRequest("11月30日交作业",
                Instant.parse("2026-11-22T02:00:00Z"), "Asia/Shanghai", "班级群", null,
                null, null, null, null);

        CandidateExtraction.CandidateDraft result = new RuleExtractor().extract(request).candidates().getFirst();

        assertFalse(result.reasons().contains("POSSIBLE_NON_TASK"));
        assertTrue(result.confidence() >= 0.6, "真实任务必须达到提醒置信阈值: " + result.confidence());
        assertNotNull(result.dueAt());
    }

    @Test
    void dateOnlyChatIsMarkedNonTaskInsteadOfSilentlyDroppingReminder() {
        ExtractionRequest request = new ExtractionRequest("今天晚上一起去吃饭吧",
                Instant.parse("2026-11-22T02:00:00Z"), "Asia/Shanghai", "班级群", null,
                null, null, null, null);

        CandidateExtraction.CandidateDraft result = new RuleExtractor().extract(request).candidates().getFirst();

        assertTrue(result.reasons().contains("POSSIBLE_NON_TASK"), "闲聊必须显式标注降级原因");
        assertTrue(result.confidence() < 0.6, "闲聊不得达到提醒置信阈值");
    }
}
