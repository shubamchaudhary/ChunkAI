package com.loglens.common.messages;

import java.util.UUID;

/**
 * One virtual part of a staged log file, produced to {@code log.ingest.parts} by
 * the {@code IngestSplitter}. Carries only a POINTER — a window-aligned byte range
 * of the original blob object — never the log content itself (claim-check pattern).
 * The {@code PartConsumer} does a ranged GET for {@code [byteStart, byteEndExclusive)}
 * and processes that slice with O(one part) memory.
 *
 * @param firstLineNumber 1-based line number of the first line in this part, so
 *                        chunk citations point at the ORIGINAL file's line numbers.
 */
public record IngestPartRequest(
    UUID sessionId,
    UUID userId,
    UUID documentId,
    String fileUrl,
    int partIdx,
    int totalParts,
    long byteStart,
    long byteEndExclusive,
    long firstLineNumber
) {
}
