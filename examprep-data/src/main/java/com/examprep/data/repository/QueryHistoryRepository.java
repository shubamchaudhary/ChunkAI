package com.examprep.data.repository;

import com.examprep.data.entity.QueryHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface QueryHistoryRepository extends JpaRepository<QueryHistory, UUID> {
    Page<QueryHistory> findByUser_IdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}

