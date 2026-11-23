-- V202611220019__backfill_outbox_server_event_ids.sql
-- 回填 m7c 之前写入的 outbox_events：它们没有 server_event_id，relay 无法携带 seq。
-- 匹配规则保守：同 workspace/aggregate/type 且完整 payload 一致的最早 server_event。
-- 无法匹配的历史行保持 null，relay 会标记已发布但不推送；客户端仍可通过 REST cursor 从 server_events 恢复。

WITH unmatched_outbox AS (
    SELECT
        id,
        workspace_id,
        aggregate_id,
        aggregate_type,
        event_type,
        payload,
        row_number() OVER (
            PARTITION BY workspace_id, aggregate_id, aggregate_type, event_type, payload
            ORDER BY id
        ) AS rn
    FROM outbox_events
    WHERE server_event_id IS NULL
),
matched_event AS (
    SELECT
        id,
        workspace_id,
        aggregate_id,
        aggregate_type,
        event_type,
        payload_minimized,
        row_number() OVER (
            PARTITION BY workspace_id, aggregate_id, aggregate_type, event_type, payload_minimized
            ORDER BY seq
        ) AS rn
    FROM server_events
)
UPDATE outbox_events o
SET server_event_id = me.id
FROM unmatched_outbox uo
JOIN matched_event me
  ON me.workspace_id = uo.workspace_id
 AND me.aggregate_id = uo.aggregate_id
 AND me.aggregate_type = uo.aggregate_type
 AND me.event_type = uo.event_type
 AND me.payload_minimized::text = uo.payload::text
 AND me.rn = uo.rn
WHERE o.id = uo.id;
