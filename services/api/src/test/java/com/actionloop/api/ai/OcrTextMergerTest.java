package com.actionloop.api.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OcrTextMergerTest {

    @Test
    void duplicatesInsideOverlapBandAreRemovedInOrder() {
        OcrTextMerger.Merged merged = OcrTextMerger.merge(List.of(
                new OcrTextMerger.SegmentText("课程通知\n截止 11 月 30 日", 1400, 0),
                new OcrTextMerger.SegmentText("截止 11 月 30 日\n提交 PDF 到学习通", 1400, 180)));

        assertEquals("课程通知\n截止 11 月 30 日\n提交 PDF 到学习通", merged.text());
        assertEquals(1, merged.droppedLines());
    }

    @Test
    void unrelatedBoundaryKeepsAllLinesInsteadOfGuessing() {
        OcrTextMerger.Merged merged = OcrTextMerger.merge(List.of(
                new OcrTextMerger.SegmentText("第一段结尾", 1400, 0),
                new OcrTextMerger.SegmentText("第二段开头", 1400, 180)));

        assertEquals("第一段结尾\n第二段开头", merged.text());
        assertEquals(0, merged.droppedLines());
    }

    @Test
    void dedupeNeverExceedsEstimatedOverlapLines() {
        String repeated = String.join("\n", java.util.Collections.nCopies(10, "重复行"));
        OcrTextMerger.Merged merged = OcrTextMerger.merge(List.of(
                new OcrTextMerger.SegmentText(repeated, 1400, 0),
                new OcrTextMerger.SegmentText(repeated, 1400, 180)));

        // 估算上限 = ceil(10 * 180 / 1400) + 2 = 4；超出重叠带的重复内容必须保留。
        assertEquals(4, merged.droppedLines());
        assertEquals(16, merged.text().split("\n").length);
    }

    @Test
    void matchingIgnoresWhitespaceAndCaseButKeepsOriginalText() {
        OcrTextMerger.Merged merged = OcrTextMerger.merge(List.of(
                new OcrTextMerger.SegmentText("  Deadline   Nov 30  ", 1400, 0),
                new OcrTextMerger.SegmentText("deadline nov 30\nNext line", 1400, 180)));

        // 匹配时折叠空白/忽略大小写；合并结果显示原文，不改写 OCR 内容。
        assertEquals("Deadline   Nov 30\nNext line", merged.text());
        assertEquals(1, merged.droppedLines());
    }

    @Test
    void blankLinesAreDroppedWithoutInventingContent() {
        OcrTextMerger.Merged merged = OcrTextMerger.merge(List.of(
                new OcrTextMerger.SegmentText("\n\n第一行\n\n第二行\n\n", 1400, 0)));

        assertEquals("第一行\n第二行", merged.text());
        assertEquals(0, merged.droppedLines());
    }
}
