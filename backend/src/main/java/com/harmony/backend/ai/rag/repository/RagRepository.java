package com.harmony.backend.ai.rag.repository;

import com.harmony.backend.ai.rag.model.RagChunkCandidate;
import com.harmony.backend.ai.rag.model.RagChunkMatch;
import com.harmony.backend.ai.rag.model.RagDocumentSummary;
import com.pgvector.PGvector;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

@Repository
@ConditionalOnBean(name = "ragJdbcTemplate")
@RequiredArgsConstructor
public class RagRepository {

    private final JdbcTemplate ragJdbcTemplate;

    public String createDocument(Long userId, String title, String content) {
        String docId = UUID.randomUUID().toString();
        ragJdbcTemplate.update(
                "INSERT INTO rag_document(doc_id, user_id, title, content) VALUES (?,?,?,?)",
                docId, userId, title, content
        );
        return docId;
    }

    public void insertChunk(String docId, Long userId, String content, float[] embedding) {
        ragJdbcTemplate.update(
                "INSERT INTO rag_chunk(doc_id, user_id, content, embedding) VALUES (?,?,?,?)",
                docId, userId, content, new PGvector(embedding)
        );
    }

    public List<RagChunkMatch> search(Long userId, float[] embedding, int topK) {
        PGvector vector = new PGvector(embedding);
        return ragJdbcTemplate.query(
                "SELECT doc_id, content, (embedding <-> ?) AS distance " +
                        "FROM rag_chunk WHERE user_id = ? " +
                        "ORDER BY embedding <-> ? LIMIT ?",
                new Object[]{vector, userId, vector, topK},
                new RagChunkMatchMapper()
        );
    }

    public List<RagChunkCandidate> searchCandidates(Long userId, float[] embedding, int topK) {
        PGvector vector = new PGvector(embedding);
        return ragJdbcTemplate.query(
                "SELECT doc_id, content, embedding, (embedding <-> ?) AS distance " +
                        "FROM rag_chunk WHERE user_id = ? " +
                        "ORDER BY embedding <-> ? LIMIT ?",
                new Object[]{vector, userId, vector, topK},
                new RagChunkCandidateMapper()
        );
    }

    public List<RagDocumentSummary> listDocuments(Long userId, int offset, int size) {
        return ragJdbcTemplate.query(
                "SELECT doc_id, title, created_at, updated_at FROM rag_document " +
                        "WHERE user_id = ? AND is_deleted = false " +
                        "ORDER BY created_at DESC LIMIT ? OFFSET ?",
                new Object[]{userId, size, offset},
                new RagDocumentSummaryMapper()
        );
    }

    public List<String> searchByKeyword(Long userId, String keyword, int limit) {
        String pattern = "%" + keyword + "%";
        return ragJdbcTemplate.query(
                "SELECT content FROM rag_document " +
                        "WHERE user_id = ? AND is_deleted = false " +
                        "AND (title ILIKE ? OR content ILIKE ?) " +
                        "ORDER BY updated_at DESC LIMIT ?",
                new Object[]{userId, pattern, pattern, limit},
                (rs, rowNum) -> rs.getString("content")
        );
    }

    public List<String> searchChunksByKeyword(Long userId, String keyword, int limit) {
        String pattern = "%" + keyword + "%";
        return ragJdbcTemplate.query(
                "SELECT content FROM rag_chunk " +
                        "WHERE user_id = ? AND content ILIKE ? " +
                        "ORDER BY created_at DESC LIMIT ?",
                new Object[]{userId, pattern, limit},
                (rs, rowNum) -> rs.getString("content")
        );
    }

    public List<String> listRecentDocumentContents(Long userId, int limit) {
        return ragJdbcTemplate.query(
                "SELECT content FROM rag_document " +
                        "WHERE user_id = ? AND is_deleted = false " +
                        "ORDER BY updated_at DESC LIMIT ?",
                new Object[]{userId, limit},
                (rs, rowNum) -> rs.getString("content")
        );
    }

    public List<String> listRecentChunkContents(Long userId, int limit) {
        return ragJdbcTemplate.query(
                "SELECT content FROM rag_chunk " +
                        "WHERE user_id = ? " +
                        "ORDER BY created_at DESC LIMIT ?",
                new Object[]{userId, limit},
                (rs, rowNum) -> rs.getString("content")
        );
    }

    public long countDocuments(Long userId) {
        Long count = ragJdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM rag_document WHERE user_id = ? AND is_deleted = false",
                new Object[]{userId},
                Long.class
        );
        return count == null ? 0L : count;
    }

    public List<RagDocumentSummary> listAllDocuments(int offset, int size) {
        return ragJdbcTemplate.query(
                "SELECT doc_id, title, created_at, updated_at FROM rag_document " +
                        "WHERE is_deleted = false " +
                        "ORDER BY created_at DESC LIMIT ? OFFSET ?",
                new Object[]{size, offset},
                new RagDocumentSummaryMapper()
        );
    }

    public long countAllDocuments() {
        Long count = ragJdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM rag_document WHERE is_deleted = false",
                Long.class
        );
        return count == null ? 0L : count;
    }

    public boolean deleteDocument(Long userId, String docId) {
        int updated = ragJdbcTemplate.update(
                "UPDATE rag_document SET is_deleted = true, updated_at = CURRENT_TIMESTAMP " +
                        "WHERE doc_id = ? AND user_id = ? AND is_deleted = false",
                docId, userId
        );
        ragJdbcTemplate.update(
                "DELETE FROM rag_chunk WHERE doc_id = ? AND user_id = ?",
                docId, userId
        );
        return updated > 0;
    }

    public boolean deleteDocumentAdmin(String docId) {
        List<Long> userIds = ragJdbcTemplate.query(
                "SELECT user_id FROM rag_document WHERE doc_id = ? AND is_deleted = false LIMIT 1",
                new Object[]{docId},
                (rs, rowNum) -> rs.getLong("user_id")
        );
        Long userId = userIds.isEmpty() ? null : userIds.get(0);
        if (userId == null) {
            return false;
        }
        int updated = ragJdbcTemplate.update(
                "UPDATE rag_document SET is_deleted = true, updated_at = CURRENT_TIMESTAMP " +
                        "WHERE doc_id = ? AND is_deleted = false",
                docId
        );
        ragJdbcTemplate.update(
                "DELETE FROM rag_chunk WHERE doc_id = ? AND user_id = ?",
                docId, userId
        );
        return updated > 0;
    }

    private static class RagChunkMatchMapper implements RowMapper<RagChunkMatch> {
        @Override
        public RagChunkMatch mapRow(ResultSet rs, int rowNum) throws SQLException {
            String docId = rs.getString("doc_id");
            String content = rs.getString("content");
            double distance = rs.getDouble("distance");
            double score = 1.0 / (1.0 + distance);
            return new RagChunkMatch(docId, content, score);
        }
    }

    private static class RagChunkCandidateMapper implements RowMapper<RagChunkCandidate> {
        @Override
        public RagChunkCandidate mapRow(ResultSet rs, int rowNum) throws SQLException {
            String docId = rs.getString("doc_id");
            String content = rs.getString("content");
            double distance = rs.getDouble("distance");
            float[] embedding = null;
            Object vectorObj = rs.getObject("embedding");
            if (vectorObj instanceof PGvector pgvector) {
                embedding = pgvector.toArray();
            } else if (vectorObj instanceof String strValue) {
                try {
                    embedding = new PGvector(strValue).toArray();
                } catch (SQLException ignored) {
                    embedding = null;
                }
            }
            return new RagChunkCandidate(docId, content, embedding, distance);
        }
    }

    private static class RagDocumentSummaryMapper implements RowMapper<RagDocumentSummary> {
        @Override
        public RagDocumentSummary mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new RagDocumentSummary(
                    rs.getString("doc_id"),
                    rs.getString("title"),
                    rs.getObject("created_at", java.time.OffsetDateTime.class),
                    rs.getObject("updated_at", java.time.OffsetDateTime.class)
            );
        }
    }
}
