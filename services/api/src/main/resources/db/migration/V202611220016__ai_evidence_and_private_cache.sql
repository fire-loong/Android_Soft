-- AI 引用、原始日期与 ACL 隔离派生缓存（指导书 14.3/14.7）。
ALTER TABLE candidate_tasks ADD COLUMN raw_due_text text;
ALTER TABLE candidate_tasks ADD COLUMN evidence_quotes_json jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE candidate_tasks ADD COLUMN uncertainties_json jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE candidate_tasks ADD COLUMN schema_version text;
ALTER TABLE candidate_tasks ADD COLUMN needs_user_confirmation boolean NOT NULL DEFAULT true;

CREATE TABLE ai_result_cache (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    requested_by        uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_item_id      uuid NOT NULL REFERENCES source_items(id) ON DELETE CASCADE,
    input_hash          char(64) NOT NULL,
    model_version       text NOT NULL,
    prompt_version      text NOT NULL,
    schema_version      text NOT NULL,
    result_json         jsonb NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    last_used_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uniq_ai_result_private_key UNIQUE (
        workspace_id, requested_by, source_item_id, input_hash,
        model_version, prompt_version, schema_version
    )
);
CREATE INDEX idx_ai_result_source ON ai_result_cache (source_item_id);
