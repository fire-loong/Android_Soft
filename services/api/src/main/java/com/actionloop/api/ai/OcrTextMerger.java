package com.actionloop.api.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 分段 OCR 文本合并（纯逻辑，便于单测）。
 * 只删除相邻段重叠带内完全相同的行，不做纠错、改写或补写；匹配不上的行原样保留。
 */
public final class OcrTextMerger {

    public record SegmentText(String text, int height, int overlapWithPrevious) {
    }

    public record Merged(String text, int droppedLines) {
    }

    private static final int MAX_BOUNDARY_LINES = 30;
    private static final int EXTRA_GUARD_LINES = 2;

    private OcrTextMerger() {
    }

    public static Merged merge(List<SegmentText> segments) {
        List<String> merged = new ArrayList<>();
        int dropped = 0;
        for (int i = 0; i < segments.size(); i++) {
            SegmentText segment = segments.get(i);
            List<String> lines = normalizeLines(segment.text());
            if (i > 0 && !merged.isEmpty() && !lines.isEmpty()) {
                int cap = boundaryLineCap(segment, segments.get(i - 1), lines.size());
                int duplicate = longestDuplicateRun(merged, lines, cap);
                if (duplicate > 0) {
                    lines = new ArrayList<>(lines.subList(duplicate, lines.size()));
                    dropped += duplicate;
                }
            }
            merged.addAll(lines);
        }
        return new Merged(String.join("\n", merged), dropped);
    }

    /** 重叠带的行数上限按“重叠像素 / 上一段高度 × 上一段行数”估算，并留 2 行余量。 */
    private static int boundaryLineCap(SegmentText current, SegmentText previous, int currentLineCount) {
        if (current.overlapWithPrevious() <= 0 || previous.height() <= 0) {
            return Math.min(EXTRA_GUARD_LINES, currentLineCount);
        }
        int previousLines = countLines(previous.text());
        double ratio = (double) current.overlapWithPrevious() / previous.height();
        int estimated = (int) Math.ceil(previousLines * ratio) + EXTRA_GUARD_LINES;
        return Math.max(1, Math.min(Math.min(estimated, MAX_BOUNDARY_LINES), currentLineCount));
    }

    private static int longestDuplicateRun(List<String> mergedTail, List<String> head, int cap) {
        int max = Math.min(cap, Math.min(mergedTail.size(), head.size()));
        for (int k = max; k >= 1; k--) {
            if (sameRun(mergedTail, head, k)) {
                return k;
            }
        }
        return 0;
    }

    private static boolean sameRun(List<String> mergedTail, List<String> head, int k) {
        int offset = mergedTail.size() - k;
        for (int j = 0; j < k; j++) {
            if (!normalizeKey(mergedTail.get(offset + j)).equals(normalizeKey(head.get(j)))) {
                return false;
            }
        }
        return true;
    }

    private static List<String> normalizeLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null) {
            return lines;
        }
        for (String raw : text.split("\\r\\n|\\r|\\n")) {
            String line = raw.strip();
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static int countLines(String text) {
        return normalizeLines(text).size();
    }

    /** 只折叠空白并忽略大小写，保留字符本身，避免把不同内容误判成重复。 */
    private static String normalizeKey(String line) {
        return line.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
