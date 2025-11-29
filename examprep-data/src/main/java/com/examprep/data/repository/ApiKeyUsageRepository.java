package com.examprep.data.repository;

import com.examprep.data.entity.ApiKeyUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiKeyUsageRepository extends JpaRepository<ApiKeyUsage, UUID> {
    
    /**
     * Find usage record for a specific key and minute bucket.
     */
    Optional<ApiKeyUsage> findByKeyIdentifierAndMinuteBucket(
        String keyIdentifier,
        Instant minuteBucket
    );
    
    /**
     * Find usage record for a specific key and day bucket.
     */
    Optional<ApiKeyUsage> findByKeyIdentifierAndDayBucket(
        String keyIdentifier,
        LocalDate dayBucket
    );
    
    /**
     * Clean up old usage records (older than 7 days).
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM ApiKeyUsage aku WHERE aku.minuteBucket < :cutoff")
    void deleteOldRecords(@Param("cutoff") Instant cutoff);
}

