-- PostgreSQL schema for RAG (pgvector)
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE IF NOT EXISTS rag_document (
    id BIGSERIAL PRIMARY KEY,
    doc_id VARCHAR(64) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    is_deleted BOOLEAN DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS rag_chunk (
    id BIGSERIAL PRIMARY KEY,
    doc_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    embedding vector(1536) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_rag_chunk_user ON rag_chunk(user_id);
CREATE INDEX IF NOT EXISTS idx_rag_chunk_doc ON rag_chunk(doc_id);
-- Vector ANN index for pgvector (<-> L2 distance in current query)
CREATE INDEX IF NOT EXISTS idx_rag_chunk_embedding_hnsw_l2
ON rag_chunk
USING hnsw (embedding vector_l2_ops)
WITH (m = 16, ef_construction = 200);

-- Optional trigram indexes for ILIKE keyword fallback
CREATE INDEX IF NOT EXISTS idx_rag_document_title_trgm
ON rag_document USING gin (title gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_rag_document_content_trgm
ON rag_document USING gin (content gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_rag_chunk_content_trgm
ON rag_chunk USING gin (content gin_trgm_ops);
