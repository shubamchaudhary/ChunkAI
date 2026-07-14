package com.deepdocai.storage;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * JDBC data access for the per-session chunk tables {@code log_chunks_s_<tid>}.
 * These tables are created dynamically per session, so JPA cannot map them; all
 * access goes through raw JDBC. Table names are resolved exclusively via
 * {@link SessionChunkTableManager#tableName(UUID)}, which validates the session
 * id is a real UUID before it is spliced into SQL (injection guard).
 */
@Repository
public class SessionChunkRepository {

    private static final int BATCH_SIZE = 500;

    private final JdbcTemplate jdbc;
    private final SessionChunkTableManager tableManager;

    public SessionChunkRepository(JdbcTemplate jdbc, SessionChunkTableManager tableManager) {
        this.jdbc = jdbc;
        this.tableManager = tableManager;
    }

    /** A chunk to persist. {@code chunkId} is assigned by the caller before insert. */
    public record NewChunk(
        UUID chunkId,
        UUID documentId,
        Instant timeBucket,
        int lineStart,
        int lineEnd,
        String content,
        boolean anomalous
    ) {
    }

    /**
     * Batch-insert chunks into the session's table in fixed-size JDBC batches
     * (500 rows per statement). Embeddings are left {@code NULL} here and filled
     * later by the Phase-3 enrichment lane.
     */
    @Transactional
    public int insertBatch(UUID sessionId, List<NewChunk> chunks) {
        if (chunks.isEmpty()) {
            return 0;
        }
        String table = tableManager.tableName(sessionId); // validated UUID → safe
        String sql = "INSERT INTO " + table +
            " (chunk_id, document_id, time_bucket, line_start, line_end, content, is_anomalous)" +
            " VALUES (?, ?, ?, ?, ?, ?, ?)";

        int inserted = 0;
        for (int start = 0; start < chunks.size(); start += BATCH_SIZE) {
            List<NewChunk> slice = chunks.subList(start, Math.min(start + BATCH_SIZE, chunks.size()));
            jdbc.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    NewChunk c = slice.get(i);
                    ps.setObject(1, c.chunkId());
                    ps.setObject(2, c.documentId());
                    ps.setObject(3, c.timeBucket().atOffset(ZoneOffset.UTC));
                    ps.setInt(4, c.lineStart());
                    ps.setInt(5, c.lineEnd());
                    ps.setString(6, c.content());
                    ps.setBoolean(7, c.anomalous());
                }

                @Override
                public int getBatchSize() {
                    return slice.size();
                }
            });
            inserted += slice.size();
        }
        return inserted;
    }

    /** Remove any chunks already staged for a document — makes re-ingest idempotent. */
    @Transactional
    public void deleteByDocument(UUID sessionId, UUID documentId) {
        String table = tableManager.tableName(sessionId); // validated UUID → safe
        jdbc.update("DELETE FROM " + table + " WHERE document_id = ?", documentId);
    }
}
