package com.actionloop.api.web.dto;

import com.actionloop.api.domain.ProcessStep;

import java.util.List;
import java.util.UUID;

public record ProcessStepView(
        UUID id,
        Integer position,
        String instruction,
        Boolean required,
        String officialEntryUrl,
        String conditionJson,
        List<UUID> sourceRefIds
) {
    public static ProcessStepView of(ProcessStep step, List<UUID> sourceRefIds) {
        return new ProcessStepView(step.getId(), step.getPosition(), step.getInstruction(),
                step.getRequired(), step.getOfficialEntryUrl(), step.getConditionJson(), sourceRefIds);
    }
}
