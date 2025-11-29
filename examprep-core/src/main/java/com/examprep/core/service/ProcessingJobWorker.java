package com.examprep.core.service;

import com.examprep.common.constants.ProcessingStatus;
import com.examprep.data.entity.Document;
import com.examprep.data.entity.ProcessingJob;
import com.examprep.data.repository.DocumentRepository;
import com.examprep.data.repository.ProcessingJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Background worker that processes queued document processing jobs in parallel batches
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProcessingJobWorker {
    
    private final ProcessingJobRepository jobRepository;
    private final DocumentRepository documentRepository;
    private final DocumentProcessingService documentProcessingService;
    
    private static final String WORKER_ID = "worker-" + UUID.randomUUID().toString().substring(0, 8);
    private static final int LOCK_DURATION_SECONDS = 300; // 5 minutes
    private static final int BATCH_SIZE = 10; // Process 10 jobs in parallel
    private static final ExecutorService executorService = Executors.newFixedThreadPool(BATCH_SIZE);
    
    @Scheduled(fixedDelay = 2000) // Run every 2 seconds for faster processing
    @Transactional // Required for repository queries with @Lock
    public void processQueuedJobs() {
        try {
            List<ProcessingJob> queuedJobs = jobRepository.findNextQueuedJob(Instant.now());
            
            if (queuedJobs.isEmpty()) {
                return;
            }
            
            // Process up to BATCH_SIZE jobs in parallel
            List<ProcessingJob> jobsToProcess = queuedJobs.stream()
                .limit(BATCH_SIZE)
                .collect(Collectors.toList());
            
            log.info("Processing {} jobs in parallel (batch size: {})", jobsToProcess.size(), BATCH_SIZE);
            
            // Extract job IDs to avoid lazy loading issues
            List<UUID> jobIds = jobsToProcess.stream()
                .map(ProcessingJob::getId)
                .collect(Collectors.toList());
            
            // Process all jobs in parallel using IDs
            List<CompletableFuture<Void>> futures = jobIds.stream()
                .map(jobId -> CompletableFuture.runAsync(() -> processJobById(jobId), executorService))
                .collect(Collectors.toList());
            
            // Wait for all to complete (but don't block the scheduler)
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .exceptionally(ex -> {
                    log.error("Error in batch processing", ex);
                    return null;
                });
            
        } catch (Exception e) {
            log.error("Error in processing job worker", e);
        }
    }
    
    /**
     * Process job by ID - NO transaction wrapper, uses separate short transactions per phase
     */
    private void processJobById(UUID jobId) {
        UUID documentId = null;
        int attempts = 0;
        
        try {
            // Phase 1: Lock the job (short transaction) - returns document ID
            JobLockResult lockResult = lockJob(jobId);
            documentId = lockResult.getDocumentId();
            attempts = lockResult.getAttempts();
            final UUID finalDocumentId = documentId;
            
            log.info("Processing job {} for document {}", jobId, finalDocumentId);
            
            // Phase 2: Process the document (NO transaction - does its own short transactions)
            // This can take minutes due to API rate limiting, so we don't hold DB connection
            documentProcessingService.processDocument(finalDocumentId);
            
            // Phase 3: Mark job as completed (short transaction)
            markJobCompleted(jobId);
            
            log.info("Completed processing job {} for document {}", jobId, finalDocumentId);
            
        } catch (Exception e) {
            log.error("Error processing job {}: {}", jobId, e.getMessage(), e);
            handleJobFailure(jobId, documentId, attempts, e);
        }
    }
    
    /**
     * Phase 1: Lock the job in a short transaction
     * Returns document ID and attempts count
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    private JobLockResult lockJob(UUID jobId) {
        ProcessingJob job = jobRepository.findById(jobId)
            .orElseThrow(() -> new RuntimeException("Job not found: " + jobId));
        
        // Get document ID while still in transaction (avoid lazy loading issues)
        UUID documentId = job.getDocument().getId();
        int attempts = job.getAttempts();
        
        job.setStatus("PROCESSING");
        job.setLockedBy(WORKER_ID);
        job.setLockedUntil(Instant.now().plusSeconds(LOCK_DURATION_SECONDS));
        job.setStartedAt(Instant.now());
        job.setAttempts(attempts + 1);
        
        jobRepository.save(job);
        
        return new JobLockResult(documentId, attempts + 1);
    }
    
    /**
     * Helper class to return job lock results
     */
    @lombok.Value
    private static class JobLockResult {
        UUID documentId;
        int attempts;
    }
    
    /**
     * Phase 3: Mark job as completed in a short transaction
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    private void markJobCompleted(UUID jobId) {
        ProcessingJob job = jobRepository.findById(jobId)
            .orElseThrow(() -> new RuntimeException("Job not found: " + jobId));
        
        job.setStatus("COMPLETED");
        job.setCompletedAt(Instant.now());
        job.setLockedUntil(null);
        job.setLockedBy(null);
        jobRepository.save(job);
    }
    
    /**
     * Handle job failure - uses separate transactions for job and document updates
     */
    private void handleJobFailure(UUID jobId, UUID documentId, int attempts, Exception e) {
        try {
            // Check if we should retry or fail (load in transaction to check max attempts)
            JobStatusInfo statusInfo = getJobStatusInfo(jobId);
            
            if (statusInfo.getCurrentAttempts() >= statusInfo.getMaxAttempts()) {
                // Mark as failed (separate transactions for job and document)
                markJobAsFailed(jobId, e.getMessage());
                if (documentId != null) {
                    markDocumentAsFailed(documentId, statusInfo.getCurrentAttempts(), e.getMessage());
                }
            } else {
                // Retry - unlock and requeue
                requeueJob(jobId, e.getMessage());
            }
        } catch (Exception ex) {
            log.error("Error updating job status after failure", ex);
        }
    }
    
    /**
     * Get job status info in a short transaction
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    private JobStatusInfo getJobStatusInfo(UUID jobId) {
        ProcessingJob job = jobRepository.findById(jobId)
            .orElseThrow(() -> new RuntimeException("Job not found: " + jobId));
        
        return new JobStatusInfo(job.getAttempts(), job.getMaxAttempts());
    }
    
    /**
     * Helper class for job status info
     */
    @lombok.Value
    private static class JobStatusInfo {
        int currentAttempts;
        int maxAttempts;
    }
    
    /**
     * Mark job as failed in a short transaction
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    private void markJobAsFailed(UUID jobId, String errorMessage) {
        ProcessingJob job = jobRepository.findById(jobId)
            .orElseThrow(() -> new RuntimeException("Job not found: " + jobId));
        
        job.setStatus("FAILED");
        job.setLastError(errorMessage);
        job.setCompletedAt(Instant.now());
        job.setLockedUntil(null);
        job.setLockedBy(null);
        jobRepository.save(job);
    }
    
    /**
     * Mark document as failed in a short transaction
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    private void markDocumentAsFailed(UUID documentId, int attempts, String errorMessage) {
        String errorMsg = "Processing failed after " + attempts + " attempts: " + errorMessage;
        documentRepository.updateProcessingStatus(
            documentId,
            ProcessingStatus.FAILED.name(),
            null,
            java.time.Instant.now(),
            errorMsg,
            null,
            null
        );
    }
    
    /**
     * Requeue job for retry in a short transaction
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    private void requeueJob(UUID jobId, String errorMessage) {
        ProcessingJob job = jobRepository.findById(jobId)
            .orElseThrow(() -> new RuntimeException("Job not found: " + jobId));
        
        job.setStatus("QUEUED");
        job.setLastError(errorMessage);
        job.setLockedUntil(null);
        job.setLockedBy(null);
        jobRepository.save(job);
    }
}
