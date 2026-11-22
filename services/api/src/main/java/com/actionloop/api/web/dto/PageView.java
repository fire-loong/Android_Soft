package com.actionloop.api.web.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/** offset 分页信封（阶段 2 用；游标分页在后续阶段统一替换为 shared CursorPage）。 */
public record PageView<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static <T> PageView<T> of(Page<T> page) {
        return new PageView<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
