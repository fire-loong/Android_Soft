-- V202611210005__create_search_documents.sql
-- 混合检索基表：关键词 FTS（pg_trgm + PostgreSQL FTS）立即可用。
-- 语义向量 embedding 列由 db/migration-vector/V202611210006 提供，需安装 pgvector 并启用 vector profile（阶段 6）。
-- 权限过滤在检索前执行（指导书 12.3/5.4）。

CREATE TABLE search_documents (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    owner_scope         jsonb NOT NULL DEFAULT '{}'::jsonb,
    source_item_id      uuid REFERENCES source_items(id) ON DELETE SET NULL,
    asset_id            uuid REFERENCES evidence_assets(id) ON DELETE SET NULL,
    title               text,
    content_tokens      text,
    content_tsv         tsvector,
    raw_text_hash       text,
    model_version       text,
    doc_type            text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    deleted_at          timestamptz
);
CREATE INDEX idx_search_documents_ws ON search_documents (workspace_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_search_documents_tsv ON search_documents USING gin (content_tsv);
CREATE INDEX idx_search_documents_trgm ON search_documents USING gin (coalesce(title,'') gin_trgm_ops, coalesce(content_tokens,'') gin_trgm_ops);
