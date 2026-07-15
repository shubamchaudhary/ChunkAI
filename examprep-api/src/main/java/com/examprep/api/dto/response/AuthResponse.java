package com.examprep.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Enhanced Auth Response with comprehensive user info
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuthResponse {
    private UUID userId;
    private String email;
    private String fullName;
    private String token;
    private Long expiresIn;
    private Instant expiresAt;
    private Instant lastLoginAt;
    private boolean isNewUser;
    private String message;
}

