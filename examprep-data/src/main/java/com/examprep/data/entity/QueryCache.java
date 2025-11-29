package com.examprep.data.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "query_cache")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QueryCache {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_id", nullable = false)
    private Chat chat;
    
    @Column(name = "query_text", nullable = false, columnDefinition = "TEXT")
    private String queryText;
    
    @Column(name = "query_hash", nullable = false, length = 64)
    private String queryHash;
    
    @Column(name = "query_embedding", columnDefinition = "vector(768)")
    private float[] queryEmbedding;
    
    @Column(name = "response_text", nullable = false, columnDefinition = "TEXT")
    private String responseText;
    
    @Column(name = "sources_used", columnDefinition = "jsonb")
    @org.hibernate.annotations.JdbcTypeCode(SqlTypes.JSON)
    private String sourcesUsed;
    
    @CreationTimestamp
    @Column(name = "created_at")
    private Instant createdAt;
    
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    
    @Column(name = "hit_count")
    @Builder.Default
    private Integer hitCount = 0;
    
    // Transient field for similarity score (used in semantic cache lookup)
    @Transient
    private Double similarity;
}

