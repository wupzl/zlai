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
    }
}
