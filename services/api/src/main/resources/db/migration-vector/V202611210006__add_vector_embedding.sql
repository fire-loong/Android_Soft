-- V202611210006__add_vector_embedding.sql
-- 语义检索列：默认 Flyway 已包含 db/migration-vector，生产环境需安装 pgvector。
-- 未安装 pgvector 时迁移会明确失败，不静默降级；embedding 生成失败时仍可退回关键词 FTS 排序。
CREATE EXTENSION IF NOT EXISTS vector;
ALTER TABLE search_documents ADD COLUMN IF NOT EXISTS embedding vector(768);
CREATE INDEX IF NOT EXISTS search_documents_embedding_hnsw
    ON search_documents USING hnsw (embedding vector_cosine_ops);
