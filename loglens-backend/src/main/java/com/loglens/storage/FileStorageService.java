package com.loglens.storage;

import java.io.InputStream;
import java.util.UUID;

/**
 * Object storage for raw uploaded log archives (MinIO in this deployment).
 * File URLs are opaque {@code s3://bucket/key} strings owned by the
 * implementation.
 */
public interface FileStorageService {

    /**
     * Stage an upload under {@code {sessionId}/{documentId}} and return its
     * {@code s3://...} URL.
     */
    String store(UUID sessionId, UUID documentId, InputStream data, long size, String contentType);

    /**
     * The {@code s3://...} URL an object would have under {@code {sessionId}/{documentId}},
     * without storing anything. Used by the presigned-upload flow, where the browser
     * PUTs the bytes directly and the backend only records the (predetermined) URL.
     */
    String objectUrl(UUID sessionId, UUID documentId);

    /**
     * Generate a short-lived, browser-usable presigned PUT URL for
     * {@code {sessionId}/{documentId}}. The browser uploads the file bytes straight
     * to blob storage with this URL — the app server never touches them.
     */
    String presignPut(UUID sessionId, UUID documentId, int expirySeconds);

    /**
     * Size in bytes of the object referenced by a {@code store}/{@code objectUrl}
     * URL, or {@code -1} if it does not exist. Used to confirm a presigned upload
     * actually landed (and to record its authoritative size).
     */
    long statSize(String fileUrl);

    /** Open the object referenced by a URL returned from {@link #store}. */
    InputStream openStream(String fileUrl);

    /** Remove the object referenced by a URL returned from {@link #store}. */
    void delete(String fileUrl);
}
