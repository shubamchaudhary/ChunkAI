package com.examprep.api.config;

import com.examprep.data.entity.User;
import com.examprep.data.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Initializes development user when dev mode is enabled.
 * Creates a default dev user if it doesn't exist.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DevModeInitializer implements CommandLineRunner {
    
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    
    @Value("${security.dev-mode.enabled:false}")
    private boolean devModeEnabled;
    
    private static final UUID DEFAULT_DEV_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String DEFAULT_DEV_USER_EMAIL = "dev@example.com";
    private static final String DEFAULT_DEV_USER_NAME = "Dev User";
    private static final String DEFAULT_DEV_PASSWORD = "dev123";
    
    @Override
    public void run(String... args) {
        if (!devModeEnabled) {
            return;
        }
        
        log.warn("========================================");
        log.warn("⚠️  DEV MODE IS ENABLED ⚠️");
        log.warn("Automatic authentication is active.");
        log.warn("NEVER enable this in production!");
        log.warn("========================================");
        
        // Ensure dev user exists
        User devUser = userRepository.findById(DEFAULT_DEV_USER_ID)
            .or(() -> userRepository.findByEmail(DEFAULT_DEV_USER_EMAIL))
            .orElse(null);
        
        if (devUser == null) {
            log.info("[DEV_MODE] Creating dev user: {} ({})", DEFAULT_DEV_USER_EMAIL, DEFAULT_DEV_USER_ID);
            
            devUser = User.builder()
                .id(DEFAULT_DEV_USER_ID)
                .email(DEFAULT_DEV_USER_EMAIL)
                .fullName(DEFAULT_DEV_USER_NAME)
                .passwordHash(passwordEncoder.encode(DEFAULT_DEV_PASSWORD))
                .isActive(true)
                .createdAt(Instant.now())
                .build();
            
            userRepository.save(devUser);
            log.info("[DEV_MODE] Dev user created successfully. You can login with:");
            log.info("[DEV_MODE]   Email: {}", DEFAULT_DEV_USER_EMAIL);
            log.info("[DEV_MODE]   Password: {}", DEFAULT_DEV_PASSWORD);
        } else {
            log.info("[DEV_MODE] Dev user already exists: {} ({})", devUser.getEmail(), devUser.getId());
            // Update to ensure it's active
            if (!devUser.getIsActive()) {
                devUser.setIsActive(true);
                userRepository.save(devUser);
            }
        }
    }
}

