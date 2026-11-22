package com.actionloop.api.web.dto;

import java.util.List;
import java.util.UUID;

/** 情境重现：原物、引用、关联事项与隐私记录。 */
public record MemoryContext(
        UUID documentId,
        String title,
        String content,
        String docType,
        String privacyNote,
        SourceView source,
        List<EvidenceRefView> evidence,
        List<TaskView> relatedTasks,
        List<String> relatedNotes
) {
}
