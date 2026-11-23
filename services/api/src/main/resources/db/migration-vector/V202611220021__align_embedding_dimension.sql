-- V202611220021__align_embedding_dimension.sql
-- 实测 xop3qwen8bembedding 返回 768 维，不是原定假设的 1024 维。
-- 若历史环境曾按旧 1024 维创建列，这里统一改回 768 并重建 HNSW 索引。

DROP INDEX IF EXISTS search_documents_embedding_hnsw;
ALTER TABLE search_documents ALTER COLUMN embedding TYPE vector(768);
CREATE INDEX IF NOT EXISTS search_documents_embedding_hnsw
    ON search_documents USING hnsw (embedding vector_cosine_ops);
