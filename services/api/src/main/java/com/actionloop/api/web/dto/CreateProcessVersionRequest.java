package com.actionloop.api.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateProcessVersionRequest(
        @Valid @Size(max = 100) List<ProcessStepInput> steps
) {
}
