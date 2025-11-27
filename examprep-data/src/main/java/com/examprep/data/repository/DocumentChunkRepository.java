package com.examprep.data.repository;

import com.examprep.data.entity.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID>, DocumentChunkRepositoryCustom {
    
    List<DocumentChunk> findByDocumentId(UUID documentId);
    
    void deleteByDocumentId(UUID documentId);
    
    long countByUserId(UUID userId);
}

