package com.examprep.core.service;

import com.examprep.data.entity.Document;
import com.examprep.data.entity.ProcessingJob;
import com.examprep.data.repository.DocumentRepository;
import com.examprep.data.repository.ProcessingJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Background worker that processes queued document processing jobs
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
    
    @Scheduled(fixedDelay = 5000) // Run every 5 seconds
    @Transactional
    public void processQueuedJobs() {
        try {
            List<ProcessingJob> queuedJobs = jobRepository.findNextQueuedJob(Instant.now());
            
            if (queuedJobs.isEmpty()) {
                return;
            }
            
            // Process first job (highest priority, oldest)
            ProcessingJob job = queuedJobs.get(0);
            
            // Lock the job
            job.setStatus("PROCESSING");
            job.setLockedBy(WORKER_ID);
            job.setLockedUntil(Instant.now().plusSeconds(LOCK_DURATION_SECONDS));
            job.setStartedAt(Instant.now());
            job.setAttempts(job.getAttempts() + 1);
            jobRepository.save(job);
            
            log.info("Processing job {} for document {}", job.getId(), job.getDocument().getId());
            
            try {
                // Process the document
                documentProcessingService.processDocument(job.getDocument().getId());
                
                // Mark job as completed
                job.setStatus("COMPLETED");
                job.setCompletedAt(Instant.now());
                job.setLockedUntil(null);
                jobRepository.save(job);
                
                log.info("Completed processing job {} for document {}", job.getId(), job.getDocument().getId());
                
            } catch (Exception e) {
                log.error("Error processing job {}: {}", job.getId(), e.getMessage(), e);
                
                // Handle retry logic
                if (job.getAttempts() >= job.getMaxAttempts()) {
                    job.setStatus("FAILED");
                    job.setLastError(e.getMessage());
                    job.setCompletedAt(Instant.now());
                    
                    // Mark document as failed
                    Document document = job.getDocument();
                    document.setProcessingStatus(com.examprep.common.constants.ProcessingStatus.FAILED);
                    document.setErrorMessage("Processing failed after " + job.getAttempts() + " attempts: " + e.getMessage());
                    documentRepository.save(document);
                } else {
                    // Retry - unlock and requeue
                    job.setStatus("QUEUED");
                    job.setLastError(e.getMessage());
                    job.setLockedUntil(null);
                    job.setLockedBy(null);
                }
                jobRepository.save(job);
            }
            
        } catch (Exception e) {
            log.error("Error in processing job worker", e);
        }
    }
}

