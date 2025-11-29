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
    private static final int BATCH_SIZE = 5; // Process 5 jobs in parallel (balanced for speed vs rate limits)
    private static final ExecutorService executorService = Executors.newFixedThreadPool(BATCH_SIZE);
    
    @Scheduled(fixedDelay = 3000) // Run every 3 seconds
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
            
            // Process jobs in parallel without stagger (rate limiting handles API limits)
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
    
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    private void processJobById(UUID jobId) {
        // Load job within new transaction to avoid lazy loading issues
        ProcessingJob job = jobRepository.findById(jobId)
            .orElseThrow(() -> new RuntimeException("Job not found: " + jobId));
        processJob(job);
    }
    
    /**
     * Process job - split transaction boundaries to avoid connection leaks
     * API calls are outside transactions
     */
    private void processJob(ProcessingJob job) {
        UUID jobId = job.getId();
        UUID documentId = null;
        try {
            // Lock job in transaction
            documentId = lockJob(jobId);
            final UUID finalDocumentId = documentId;
            
            log.info("Processing job {} for document {}", jobId, finalDocumentId);
            
            // Process the document OUTSIDE transaction (contains long-running API calls)
            documentProcessingService.processDocument(finalDocumentId);
            
            // Mark job as completed in transaction
            markJobCompleted(jobId);
            
            log.info("Completed processing job {} for document {}", jobId, finalDocumentId);
            
        } catch (Exception e) {
            log.error("Error processing job {}: {}", jobId, e.getMessage(), e);
            handleJobFailure(jobId, documentId, e);
        }
    }
    
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    private UUID lockJob(UUID jobId) {
        ProcessingJob managedJob = jobRepository.findById(jobId)
            .orElseThrow(() -> new RuntimeException("Job not found: " + jobId));
        
        UUID documentId = managedJob.getDocument().getId();
        
        managedJob.setStatus("PROCESSING");
        managedJob.setLockedBy(WORKER_ID);
        managedJob.setLockedUntil(Instant.now().plusSeconds(LOCK_DURATION_SECONDS));
        managedJob.setStartedAt(Instant.now());
        managedJob.setAttempts(managedJob.getAttempts() + 1);
        jobRepository.save(managedJob);
        
        return documentId;
    }
    
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    private void markJobCompleted(UUID jobId) {
        ProcessingJob completedJob = jobRepository.findById(jobId)
            .orElseThrow(() -> new RuntimeException("Job not found: " + jobId));
        
        completedJob.setStatus("COMPLETED");
        completedJob.setCompletedAt(Instant.now());
        completedJob.setLockedUntil(null);
        completedJob.setLockedBy(null);
        jobRepository.save(completedJob);
    }
    
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    private void handleJobFailure(UUID jobId, UUID documentId, Exception e) {
        try {
            ProcessingJob failedJob = jobRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("Job not found: " + jobId));
            
            if (failedJob.getAttempts() >= failedJob.getMaxAttempts()) {
                failedJob.setStatus("FAILED");
                failedJob.setLastError(e.getMessage());
                failedJob.setCompletedAt(Instant.now());
                
                if (documentId != null) {
                    Document document = documentRepository.findById(documentId)
                        .orElseThrow(() -> new RuntimeException("Document not found: " + documentId));
                    document.setProcessingStatus(ProcessingStatus.FAILED);
                    document.setErrorMessage("Processing failed after " + failedJob.getAttempts() + " attempts: " + e.getMessage());
                    documentRepository.save(document);
                }
            } else {
                failedJob.setStatus("QUEUED");
                failedJob.setLastError(e.getMessage());
                failedJob.setLockedUntil(null);
                failedJob.setLockedBy(null);
            }
            jobRepository.save(failedJob);
        } catch (Exception ex) {
            log.error("Error updating job status after failure", ex);
        }
    }
}
