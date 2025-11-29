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
import java.util.ArrayList;
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
    private static final int BATCH_SIZE = 3; // Process only 3 jobs in parallel to avoid overwhelming Gemini API
    private static final ExecutorService executorService = Executors.newFixedThreadPool(BATCH_SIZE);
    
    @Scheduled(fixedDelay = 5000) // Run every 5 seconds to avoid overwhelming the system
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
            
            // Stagger job processing to avoid overwhelming API - start jobs with delays
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < jobIds.size(); i++) {
                final UUID jobId = jobIds.get(i);
                // Stagger: wait 2 seconds before starting each new job
                final int delayMs = i * 2000;
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    if (delayMs > 0) {
                        try {
                            Thread.sleep(delayMs);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log.warn("Interrupted while waiting to start job {}", jobId);
                        }
                    }
                    processJobById(jobId);
                }, executorService);
                futures.add(future);
            }
            
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
    
    @Transactional(propagation = Propagation.REQUIRES_NEW) // Each job needs its own transaction
    private void processJob(ProcessingJob job) {
        UUID jobId = job.getId();
        UUID documentId = null;
        try {
            // Load job and document within transaction to avoid lazy loading issues
            ProcessingJob managedJob = jobRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("Job not found: " + jobId));
            
            // Get document ID before async processing
            documentId = managedJob.getDocument().getId();
            final UUID finalDocumentId = documentId; // Make effectively final for lambda
            
            // Lock the job
            managedJob.setStatus("PROCESSING");
            managedJob.setLockedBy(WORKER_ID);
            managedJob.setLockedUntil(Instant.now().plusSeconds(LOCK_DURATION_SECONDS));
            managedJob.setStartedAt(Instant.now());
            managedJob.setAttempts(managedJob.getAttempts() + 1);
            jobRepository.save(managedJob);
            
            log.info("Processing job {} for document {}", managedJob.getId(), finalDocumentId);
            
            // Process the document
            documentProcessingService.processDocument(finalDocumentId);
            
            // Reload job within transaction
            ProcessingJob completedJob = jobRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("Job not found: " + jobId));
            
            // Mark job as completed
            completedJob.setStatus("COMPLETED");
            completedJob.setCompletedAt(Instant.now());
            completedJob.setLockedUntil(null);
            completedJob.setLockedBy(null);
            jobRepository.save(completedJob);
            
            log.info("Completed processing job {} for document {}", completedJob.getId(), finalDocumentId);
            
        } catch (Exception e) {
            log.error("Error processing job {}: {}", jobId, e.getMessage(), e);
            
            try {
                // Reload job within transaction for error handling
                ProcessingJob failedJob = jobRepository.findById(jobId)
                    .orElseThrow(() -> new RuntimeException("Job not found: " + jobId));
                
                // Handle retry logic
                if (failedJob.getAttempts() >= failedJob.getMaxAttempts()) {
                    failedJob.setStatus("FAILED");
                    failedJob.setLastError(e.getMessage());
                    failedJob.setCompletedAt(Instant.now());
                    
                    // Mark document as failed - reload document within transaction
                    if (documentId != null) {
                        final UUID finalDocumentId = documentId; // Make effectively final for lambda
                        Document document = documentRepository.findById(finalDocumentId)
                            .orElseThrow(() -> new RuntimeException("Document not found: " + finalDocumentId));
                        document.setProcessingStatus(ProcessingStatus.FAILED);
                        document.setErrorMessage("Processing failed after " + failedJob.getAttempts() + " attempts: " + e.getMessage());
                        documentRepository.save(document);
                    }
                } else {
                    // Retry - unlock and requeue
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
}
