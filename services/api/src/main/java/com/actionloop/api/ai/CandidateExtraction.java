package com.actionloop.api.ai;

import java.util.List;
import java.util.Map;

/** AI/规则抽取的统一输出。CandidateDraft 只描述候选，绝不直接修改事项状态。 */
public record CandidateExtraction(
        List<CandidateDraft> candidates,
        String modelVersion,
        String promptVersion,
        String derivation,
        List<String> warnings
) {
    public record CandidateDraft(
            String title,
            String summary,
            String category,
            String dueAt,
            String dueDate,
            String dueTimezone,
            String timePrecision,
            String location,
            List<String> materials,
            String nextAction,
            double confidence,
            Map<String, Double> fieldConfidence,
            List<String> reasons
    ) {
    }
}
