package com.scalelogs.api.controller;

import com.scalelogs.data.entity.User;
import com.scalelogs.data.repository.UserRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/user")
@RequiredArgsConstructor
public class UserController {
    
    private final UserRepository userRepository;
    
    @GetMapping("/profile")
    public ResponseEntity<UserProfileResponse> getProfile(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));
        
        UserProfileResponse response = UserProfileResponse.builder()
            .id(user.getId())
            .email(user.getEmail())
            .fullName(user.getFullName())
            .createdAt(user.getCreatedAt())
            .build();
        
        return ResponseEntity.ok(response);
    }
    
    @Data
    @Builder
    private static class UserProfileResponse {
        private UUID id;
        private String email;
        private String fullName;
        private Instant createdAt;
    }
}

