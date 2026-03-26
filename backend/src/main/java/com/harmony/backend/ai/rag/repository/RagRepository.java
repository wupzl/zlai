package com.harmony.backend.ai.rag.repository;

import com.harmony.backend.ai.rag.model.RagChunkCandidate;
import com.harmony.backend.ai.rag.model.RagChunkMatch;
import com.harmony.backend.ai.rag.model.RagDocumentHit;
import com.harmony.backend.ai.rag.model.RagDocumentSummary;
import com.pgvector.PGvector;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@ConditionalOnBean(name = "ragJdbcTemplate")
@RequiredArgsConstructor
public class RagRepository {

    private final JdbcTemplate ragJdbcTemplate;

    public String createDocument(Long userId, String title, String content, String contentHash) {
        String docId = UUID.randomUUID().toString();
        ragJdbcTemplate.update(
                "INSERT INTO rag_document(doc_id, user_id, title, content, content_hash) VALUES (?,?,?,?,?)",
                docId, userId, title, content, contentHash
        );
        return docId;
    }

    public String findActiveDocumentIdByHash(Long userId, String contentHash) {
        if (userId == null || contentHash == null || contentHash.isBlank()) {
            return null;
        }
        List<String> ids = ragJdbcTemplate.query(
                "SELECT doc_id FROM rag_document " +
                        "WHERE user_id = ? AND content_hash = ? AND is_deleted = false " +
                        "ORDER BY updated_at DESC LIMIT 1",
                new Object[]{userId, contentHash},
                (rs, rowNum) -> rs.getString("doc_id")
        );
        return ids.isEmpty() ? null : ids.get(0);
    }

    public void touchDocument(String docId, String title) {
        if (docId == null || docId.isBlank()) {
            return;
        }
        if (title != null && !title.isBlank()) {
            ragJdbcTemplate.update(
                    "UPDATE rag_document SET title = COALESCE(NULLIF(?, ''), title), updated_at = CURRENT_TIMESTAMP " +
                            "WHERE doc_id = ? AND is_deleted = false",
                    title, docId
            );
            return;
        }
        ragJdbcTemplate.update(
                "UPDATE rag_document SET updated_at = CURRENT_TIMESTAMP WHERE doc_id = ? AND is_deleted = false",
                docId
        );
    }

    public void insertChunk(String docId, Long userId, String content, float[] embedding, String chunkMetadata) {
        ragJdbcTemplate.update(
                "INSERT INTO rag_chunk(doc_id, user_id, content, embedding, chunk_metadata) VALUES (?,?,?,?,?)",
                docId, userId, content, new PGvector(embedding), chunkMetadata
        );
    }

    public List<RagChunkMatch> search(Long userId, float[] embedding, int topK) {
        PGvector vector = new PGvector(embedding);
        return ragJdbcTemplate.query(
                "SELECT doc_id, content, chunk_metadata, distance FROM (" +
                        " SELECT doc_id, content, chunk_metadata, (embedding <-> ?) AS distance " +
                        " FROM rag_chunk WHERE user_id = ? " +
                        ") t ORDER BY distance LIMIT ?",
                new Object[]{vector, userId, topK},
                new RagChunkMatchMapper()
        );
    }

    public List<RagChunkCandidate> searchCandidates(Long userId, float[] embedding, int topK) {
        PGvector vector = new PGvector(embedding);
        return ragJdbcTemplate.query(
                "SELECT id, doc_id, doc_title, content, embedding, chunk_metadata, distance FROM (" +
                        " SELECT c.id, c.doc_id, d.title AS doc_title, c.content, c.embedding, c.chunk_metadata, (c.embedding <-> ?) AS distance " +
                        " FROM rag_chunk c JOIN rag_document d ON d.doc_id = c.doc_id " +
                        " WHERE c.user_id = ? AND d.is_deleted = false " +
                        ") t ORDER BY distance LIMIT ?",
                new Object[]{vector, userId, topK},
                new RagChunkCandidateMapper()
        );
    }

    public Map<Long, String> findChunkContentsByIds(Long userId, List<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = chunkIds.stream().map(id -> "?").collect(Collectors.joining(","));
        String sql = "SELECT id, content, chunk_metadata FROM rag_chunk WHERE user_id = ? AND id IN (" + placeholders + ")";
        Object[] args = new Object[chunkIds.size() + 1];
        args[0] = userId;
        for (int i = 0; i < chunkIds.size(); i++) {
            args[i + 1] = chunkIds.get(i);
        }
        List<Map.Entry<Long, String>> rows = ragJdbcTemplate.query(sql, args, (rs, rowNum) ->
                Map.entry(rs.getLong("id"), rs.getString("content")));
        Map<Long, String> map = new HashMap<>();
        for (Map.Entry<Long, String> row : rows) {
            map.put(row.getKey(), row.getValue());
        }
        return map;
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

    public List<RagDocumentHit> searchDocumentsByTitle(Long userId, String keyword, int limit) {
        String pattern = "%" + keyword + "%";
        return ragJdbcTemplate.query(
                "SELECT doc_id, title, content, " +
                        "CASE " +
                        " WHEN LOWER(title) = LOWER(?) THEN 1.0 " +
                        " WHEN LOWER(title) LIKE LOWER(?) THEN 0.93 " +
                        " WHEN LOWER(content) LIKE LOWER(?) THEN 0.72 " +
                        " ELSE 0.55 END AS score " +
                        "FROM rag_document " +
                        "WHERE user_id = ? AND is_deleted = false " +
                        "AND (title ILIKE ? OR content ILIKE ?) " +
                        "ORDER BY score DESC, updated_at DESC LIMIT ?",
                new Object[]{keyword, pattern, pattern, userId, pattern, pattern, limit},
                new RagDocumentHitMapper()
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

    public List<RagChunkMatch> searchChunkMatchesByKeyword(Long userId, String keyword, int limit) {
        String pattern = "%" + keyword + "%";
        return ragJdbcTemplate.query(
                "SELECT doc_id, content, chunk_metadata, " +
                        "CASE " +
                        " WHEN LOWER(content) = LOWER(?) THEN 1.0 " +
                        " WHEN LOWER(content) LIKE LOWER(?) THEN 0.92 " +
                        " ELSE 0.78 END " +
                        " + CASE " +
                        " WHEN chunk_metadata ILIKE '%\"blockType\":\"heading\"%' THEN 0.14 " +
                        " WHEN chunk_metadata ILIKE '%\"blockType\":\"table\"%' THEN 0.08 " +
                        " WHEN chunk_metadata ILIKE '%\"blockType\":\"list\"%' THEN 0.05 " +
                        " ELSE 0.0 END " +
                        " + CASE " +
                        " WHEN chunk_metadata ILIKE ? THEN 0.10 " +
                        " ELSE 0.0 END AS score " +
                        "FROM rag_chunk " +
                        "WHERE user_id = ? AND content ILIKE ? " +
                        "ORDER BY CASE " +
                        " WHEN POSITION(LOWER(?) IN LOWER(content)) > 0 THEN POSITION(LOWER(?) IN LOWER(content)) " +
                        " ELSE 999999 END, score DESC, created_at DESC LIMIT ?",
                new Object[]{keyword, pattern, "%" + keyword + "%", userId, pattern, keyword, keyword, limit},
                new RagChunkKeywordMatchMapper()
        );
    }

    public List<RagChunkMatch> searchChunkMatchesByHeading(Long userId, String heading, int limit) {
        String pattern = "%" + heading + "%";
        return ragJdbcTemplate.query(
                "SELECT doc_id, content, chunk_metadata, " +
                        "CASE " +
                        " WHEN chunk_metadata ILIKE ? THEN 1.08 " +
                        " ELSE 0.82 END " +
                        " + CASE " +
                        " WHEN chunk_metadata ILIKE '%\"blockType\":\"heading\"%' THEN 0.18 " +
                        " WHEN chunk_metadata ILIKE '%\"blockType\":\"mixed\"%' THEN 0.05 " +
                        " ELSE 0.0 END AS score " +
                        "FROM rag_chunk " +
                        "WHERE user_id = ? AND chunk_metadata ILIKE ? " +
                        "ORDER BY score DESC, created_at DESC LIMIT ?",
                new Object[]{pattern, userId, pattern, limit},
                new RagChunkKeywordMatchMapper()
        );
    }

    public List<RagChunkMatch> searchChunkMatchesByHeadingInDocument(Long userId, String docId, String heading, int limit) {
        String pattern = "%" + heading + "%";
        return ragJdbcTemplate.query(
                "SELECT doc_id, content, chunk_metadata, " +
                        "CASE " +
                        " WHEN chunk_metadata ILIKE ? THEN 1.12 " +
                        " ELSE 0.84 END " +
                        " + CASE " +
                        " WHEN chunk_metadata ILIKE '%\"blockType\":\"heading\"%' THEN 0.20 " +
                        " WHEN chunk_metadata ILIKE '%\"blockType\":\"mixed\"%' THEN 0.06 " +
                        " ELSE 0.0 END AS score " +
                        "FROM rag_chunk " +
                        "WHERE user_id = ? AND doc_id = ? AND chunk_metadata ILIKE ? " +
                        "ORDER BY score DESC, created_at DESC LIMIT ?",
                new Object[]{pattern, userId, docId, pattern, limit},
                new RagChunkKeywordMatchMapper()
        );
    }

    public List<RagChunkMatch> searchChunkMatchesByKeywordInDocument(Long userId, String docId, String keyword, int limit) {
        String pattern = "%" + keyword + "%";
        return ragJdbcTemplate.query(
                "SELECT doc_id, content, chunk_metadata, " +
                        "CASE " +
                        " WHEN LOWER(content) = LOWER(?) THEN 1.0 " +
                        " WHEN LOWER(content) LIKE LOWER(?) THEN 0.92 " +
                        " ELSE 0.78 END " +
                        " + CASE " +
                        " WHEN chunk_metadata ILIKE '%\"blockType\":\"heading\"%' THEN 0.14 " +
                        " WHEN chunk_metadata ILIKE '%\"blockType\":\"table\"%' THEN 0.08 " +
                        " WHEN chunk_metadata ILIKE '%\"blockType\":\"list\"%' THEN 0.05 " +
                        " ELSE 0.0 END AS score " +
                        "FROM rag_chunk " +
                        "WHERE user_id = ? AND doc_id = ? AND content ILIKE ? " +
                        "ORDER BY CASE " +
                        " WHEN POSITION(LOWER(?) IN LOWER(content)) > 0 THEN POSITION(LOWER(?) IN LOWER(content)) " +
                        " ELSE 999999 END, score DESC, created_at DESC LIMIT ?",
                new Object[]{keyword, pattern, userId, docId, pattern, keyword, keyword, limit},
                new RagChunkKeywordMatchMapper()
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
        if (updated > 0) {
            ragJdbcTemplate.update(
                    "DELETE FROM rag_chunk WHERE doc_id = ? AND user_id = ?",
                    docId, userId
            );
        }
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
        if (updated > 0) {
            ragJdbcTemplate.update(
                    "DELETE FROM rag_chunk WHERE doc_id = ? AND user_id = ?",
                    docId, userId
            );
        }
        return updated > 0;
    }

    private static class RagChunkMatchMapper implements RowMapper<RagChunkMatch> {
        @Override
        public RagChunkMatch mapRow(ResultSet rs, int rowNum) throws SQLException {
            String docId = rs.getString("doc_id");
            String docTitle = rs.getString("doc_title");
            String content = rs.getString("content");
            double distance = rs.getDouble("distance");
            double score = 1.0 / (1.0 + distance);
            return new RagChunkMatch(docId, content, score, rs.getString("chunk_metadata"));
        }
    }

    private static class RagChunkCandidateMapper implements RowMapper<RagChunkCandidate> {
        @Override
        public RagChunkCandidate mapRow(ResultSet rs, int rowNum) throws SQLException {
            long id = rs.getLong("id");
            String docId = rs.getString("doc_id");
            String docTitle = rs.getString("doc_title");
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
            return new RagChunkCandidate(id, docId, docTitle, content, embedding, distance, rs.getString("chunk_metadata"));
        }
    }

    private static class RagChunkKeywordMatchMapper implements RowMapper<RagChunkMatch> {
        @Override
        public RagChunkMatch mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new RagChunkMatch(
                    rs.getString("doc_id"),
                    rs.getString("content"),
                    rs.getDouble("score"),
                    rs.getString("chunk_metadata")
            );
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

    private static class RagDocumentHitMapper implements RowMapper<RagDocumentHit> {
        @Override
        public RagDocumentHit mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new RagDocumentHit(
                    rs.getString("doc_id"),
                    rs.getString("title"),
                    rs.getString("content"),
                    rs.getDouble("score")
            );
        }
    }
}

