package com.harmony.backend.ai.rag.config;

import com.pgvector.PGvector;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;

@Component
@ConditionalOnBean(name = "ragJdbcTemplate")
@RequiredArgsConstructor
public class RagVectorInitializer {

    private final JdbcTemplate ragJdbcTemplate;

    @PostConstruct
    public void registerTypes() {
        try (Connection connection = ragJdbcTemplate.getDataSource().getConnection()) {
            PGvector.registerTypes(connection);
        } catch (Exception ignored) {
        }
        try {
            ragJdbcTemplate.execute("ALTER TABLE rag_document ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64)");
        } catch (Exception ignored) {
        }
        try {
            ragJdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_rag_document_user_content_hash " +
                            "ON rag_document(user_id, content_hash) WHERE is_deleted = false");
        } catch (Exception ignored) {
        }
        try {
            ragJdbcTemplate.execute("ALTER TABLE rag_chunk ADD COLUMN IF NOT EXISTS chunk_metadata TEXT");
        } catch (Exception ignored) {
        }
    }
}
