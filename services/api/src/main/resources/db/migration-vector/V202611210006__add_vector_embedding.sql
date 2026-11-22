-- V202611210006__add_vector_embedding.sql
-- 语义检索列：需先安装 pgvector，并以 spring profile=vector 启用（spring.flyway.locations 追加 classpath:db/migration-vector）。
-- 未安装时本迁移不执行，应用仍可用关键词 FTS 检索（指导书 14.7 降级：模型/向量不可用不影响核心事项与关键词检索）。
CREATE EXTENSION IF NOT EXISTS vector;
ALTER TABLE search_documents ADD COLUMN IF NOT EXISTS embedding vector(1024);
CREATE INDEX IF NOT EXISTS search_documents_embedding_hnsw
    ON search_documents USING hnsw (embedding vector_cosine_ops);
