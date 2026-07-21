package com.loglens.data.repository;

import com.loglens.data.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    /** Duplicate-upload detection backed by the {@code uq_doc_per_session} constraint. */
    Optional<Document> findBySessionIdAndOriginalFileNameAndFileSizeBytes(
        UUID sessionId, String originalFileName, Long fileSizeBytes);

    List<Document> findBySessionIdOrderByUploadedAtDesc(UUID sessionId);

    @Modifying
    @Transactional
    @Query("update Document d set d.processingStatus = com.loglens.common.constants.ProcessingStatus.PROCESSING "
        + "where d.id = :id")
    void markProcessing(@Param("id") UUID id);

    @Modifying
    @Transactional
    @Query("update Document d set d.processingStatus = com.loglens.common.constants.ProcessingStatus.COMPLETED, "
        + "d.stagedFileDeleted = true, d.processedAt = :processedAt where d.id = :id")
    void markCompleted(@Param("id") UUID id, @Param("processedAt") Instant processedAt);

    @Modifying
    @Transactional
    @Query("update Document d set d.processingStatus = com.loglens.common.constants.ProcessingStatus.FAILED, "
        + "d.errorMessage = :message where d.id = :id")
    void markFailed(@Param("id") UUID id, @Param("message") String message);

    // ── Partitioned ingest ──────────────────────────────────────────────────

    /**
     * Splitter: record the part count and move the document to PROCESSING. Does NOT
     * reset parsed_parts — a fresh document defaults to 0, and if the splitter is
     * redelivered after some parts already processed (crash before ack), those parts'
     * markers make them skip on replay, so their increments must NOT be lost. Splitting
     * is deterministic, so total_parts is stable across a re-split.
     */
    @Modifying
    @Transactional
    @Query("update Document d set d.totalParts = :totalParts, "
        + "d.processingStatus = com.loglens.common.constants.ProcessingStatus.PROCESSING where d.id = :id")
    void setTotalPartsProcessing(@Param("id") UUID id, @Param("totalParts") int totalParts);

    /** Each part atomically bumps the completion counter (joins the part's transaction). */
    @Modifying
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    @Query("update Document d set d.parsedParts = d.parsedParts + 1 where d.id = :id")
    void incrementParsedParts(@Param("id") UUID id);

    /**
     * Atomic finalize claim: exactly one caller flips PROCESSING→COMPLETED once all
     * parts are in (parsed_parts == total_parts). Returns 1 for the winner, 0 for
     * everyone else — no coordinator process needed.
     */
    @Modifying
    @Transactional
    @Query("update Document d set d.processingStatus = com.loglens.common.constants.ProcessingStatus.COMPLETED "
        + "where d.id = :id and d.totalParts > 0 and d.parsedParts >= d.totalParts "
        + "and d.processingStatus = com.loglens.common.constants.ProcessingStatus.PROCESSING")
    int claimFinalize(@Param("id") UUID id);

    /** Finalizer: staged blob deleted, stamp processed_at. */
    @Modifying
    @Transactional
    @Query("update Document d set d.stagedFileDeleted = true, d.processedAt = :processedAt where d.id = :id")
    void markStagedDeleted(@Param("id") UUID id, @Param("processedAt") Instant processedAt);
}
