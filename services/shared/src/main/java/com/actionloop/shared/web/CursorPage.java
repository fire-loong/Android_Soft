package com.actionloop.shared.web;

import java.util.List;

/**
 * 游标分页响应，与指导书 13.1 一致：items + nextCursor + hasMore。
 * 游标不可猜测权限；默认 20，最大 100。
 */
public record CursorPage<T>(
        List<T> items,
        String nextCursor,
        boolean hasMore
) {
    public static <T> CursorPage<T> of(List<T> items, String nextCursor, boolean hasMore) {
        return new CursorPage<>(items, nextCursor, hasMore);
    }

    public static <T> CursorPage<T> empty() {
        return new CursorPage<>(List.of(), null, false);
    }
}
