package com.examprep.data.repository;

import com.examprep.common.constants.ProcessingStatus;
import com.examprep.data.entity.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentRepository extends JpaRepository<Document, UUID> {
    
    Page<Document> findByUserId(UUID userId, Pageable pageable);
    
    Page<Document> findByUserIdAndProcessingStatus(
        UUID userId, 
        ProcessingStatus status, 
        Pageable pageable
    );
    
    Optional<Document> findByIdAndUserId(UUID id, UUID userId);
    
    List<Document> findByUserIdAndProcessingStatusIn(
        UUID userId, 
        List<ProcessingStatus> statuses
    );
    
    @Query("""
        SELECT SUM(d.fileSizeBytes) 
        FROM Document d 
        WHERE d.user.id = :userId
    """)
    Long getTotalStorageByUserId(UUID userId);
    
    long countByUserId(UUID userId);
}

