package com.examprep.data.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "api_key_usage")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiKeyUsage {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @Column(name = "key_identifier", nullable = false, length = 50)
    private String keyIdentifier;
    
    // Rate limiting (per minute)
    @Column(name = "minute_bucket", nullable = false)
    private Instant minuteBucket;
    
    @Column(name = "request_count")
    @Builder.Default
    private Integer requestCount = 0;
    
    @Column(name = "token_count")
    @Builder.Default
    private Integer tokenCount = 0;
    
    // Daily tracking
    @Column(name = "day_bucket", nullable = false)
    private LocalDate dayBucket;
    
    @Column(name = "daily_request_count")
    @Builder.Default
    private Integer dailyRequestCount = 0;
    
    // Health tracking
    @Column(name = "last_success_at")
    private Instant lastSuccessAt;
    
    @Column(name = "last_failure_at")
    private Instant lastFailureAt;
    
    @Column(name = "consecutive_failures")
    @Builder.Default
    private Integer consecutiveFailures = 0;
    
    @CreationTimestamp
    @Column(name = "created_at")
    private Instant createdAt;
}

