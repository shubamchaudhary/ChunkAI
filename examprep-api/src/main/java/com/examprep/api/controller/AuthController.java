package com.examprep.api.controller;

import com.examprep.api.dto.request.LoginRequest;
import com.examprep.api.dto.request.RegisterRequest;
import com.examprep.api.dto.response.AuthResponse;
import com.examprep.api.dto.response.ErrorResponse;
import com.examprep.api.security.JwtTokenProvider;
import com.examprep.data.entity.User;
import com.examprep.data.repository.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

/**
 * Enhanced Authentication Controller with improved validation and UX
 *
 * Features:
 * - Comprehensive input validation with helpful error messages
 * - Password strength validation
 * - Rate limiting ready (can be added via @RateLimiter annotation)
 * - Detailed response messages for frontend
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;

    // Token expiration: 24 hours
    private static final long TOKEN_EXPIRATION_SECONDS = 86400L;

    // Password validation patterns
    private static final Pattern HAS_UPPERCASE = Pattern.compile("[A-Z]");
    private static final Pattern HAS_LOWERCASE = Pattern.compile("[a-z]");
    private static final Pattern HAS_DIGIT = Pattern.compile("\\d");
    private static final Pattern HAS_SPECIAL = Pattern.compile("[!@#$%^&*(),.?\":{}|<>]");

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request, BindingResult bindingResult) {
        // Check for validation errors
        if (bindingResult.hasErrors()) {
            String errorMessage = bindingResult.getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");

            log.warn("Registration validation failed: {}", errorMessage);
            return ResponseEntity.badRequest().body(
                ErrorResponse.builder()
                    .error("VALIDATION_ERROR")
                    .message(errorMessage)
                    .status(400)
                    .build()
            );
        }

        // Normalize email
        String email = request.getEmail().toLowerCase().trim();

        // Check if user exists
        if (userRepository.existsByEmail(email)) {
            log.warn("Registration failed: email already exists: {}", email);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                ErrorResponse.builder()
                    .error("EMAIL_EXISTS")
                    .message("An account with this email already exists. Please sign in or use a different email.")
                    .status(409)
                    .build()
            );
        }

        // Validate password strength
        String passwordError = validatePasswordStrength(request.getPassword());
        if (passwordError != null) {
            return ResponseEntity.badRequest().body(
                ErrorResponse.builder()
                    .error("WEAK_PASSWORD")
                    .message(passwordError)
                    .status(400)
                    .build()
            );
        }

        // Create user
        User user = User.builder()
            .email(email)
            .passwordHash(passwordEncoder.encode(request.getPassword()))
            .fullName(request.getFullName() != null ? request.getFullName().trim() : null)
            .isActive(true)
            .build();

        try {
            user = userRepository.save(user);
            log.info("New user registered: {} ({})", user.getId(), email);
        } catch (Exception e) {
            log.error("Failed to save user: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ErrorResponse.builder()
                    .error("REGISTRATION_FAILED")
                    .message("Registration failed. Please try again.")
                    .status(500)
                    .build()
            );
        }

        String token = tokenProvider.generateToken(user.getId(), user.getEmail());
        Instant expiresAt = Instant.now().plus(TOKEN_EXPIRATION_SECONDS, ChronoUnit.SECONDS);

        AuthResponse response = AuthResponse.builder()
            .userId(user.getId())
            .email(user.getEmail())
            .fullName(user.getFullName())
            .token(token)
            .expiresIn(TOKEN_EXPIRATION_SECONDS)
            .expiresAt(expiresAt)
            .isNewUser(true)
            .message("Account created successfully! Welcome to ChunkAI.")
            .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request, BindingResult bindingResult) {
        // Check for validation errors
        if (bindingResult.hasErrors()) {
            String errorMessage = bindingResult.getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");

            return ResponseEntity.badRequest().body(
                ErrorResponse.builder()
                    .error("VALIDATION_ERROR")
                    .message(errorMessage)
                    .status(400)
                    .build()
            );
        }

        // Normalize email
        String email = request.getEmail().toLowerCase().trim();

        // Find user
        User user = userRepository.findByEmail(email).orElse(null);

        if (user == null) {
            log.warn("Login failed: user not found: {}", email);
            // Don't reveal whether email exists
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                ErrorResponse.builder()
                    .error("INVALID_CREDENTIALS")
                    .message("Invalid email or password. Please check your credentials and try again.")
                    .status(401)
                    .build()
            );
        }

        // Check password
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            log.warn("Login failed: invalid password for user: {}", email);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                ErrorResponse.builder()
                    .error("INVALID_CREDENTIALS")
                    .message("Invalid email or password. Please check your credentials and try again.")
                    .status(401)
                    .build()
            );
        }

        // Check if account is active
        if (!user.getIsActive()) {
            log.warn("Login failed: account deactivated: {}", email);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                ErrorResponse.builder()
                    .error("ACCOUNT_DEACTIVATED")
                    .message("Your account has been deactivated. Please contact support.")
                    .status(403)
                    .build()
            );
        }

        // Update last login
        Instant previousLogin = user.getLastLoginAt();
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        log.info("User logged in: {} ({})", user.getId(), email);

        String token = tokenProvider.generateToken(user.getId(), user.getEmail());
        Instant expiresAt = Instant.now().plus(TOKEN_EXPIRATION_SECONDS, ChronoUnit.SECONDS);

        AuthResponse response = AuthResponse.builder()
            .userId(user.getId())
            .email(user.getEmail())
            .fullName(user.getFullName())
            .token(token)
            .expiresIn(TOKEN_EXPIRATION_SECONDS)
            .expiresAt(expiresAt)
            .lastLoginAt(previousLogin)
            .isNewUser(false)
            .message("Welcome back" + (user.getFullName() != null ? ", " + user.getFullName().split(" ")[0] : "") + "!")
            .build();

        return ResponseEntity.ok(response);
    }

    @GetMapping("/validate")
    public ResponseEntity<?> validateToken(@RequestHeader("Authorization") String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                ErrorResponse.builder()
                    .error("INVALID_TOKEN")
                    .message("Invalid or missing authorization header")
                    .status(401)
                    .build()
            );
        }

        String token = authHeader.substring(7);
        try {
            if (tokenProvider.validateToken(token)) {
                return ResponseEntity.ok().body(java.util.Map.of(
                    "valid", true,
                    "message", "Token is valid"
                ));
            }
        } catch (Exception e) {
            log.debug("Token validation failed: {}", e.getMessage());
        }

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
            ErrorResponse.builder()
                .error("TOKEN_EXPIRED")
                .message("Your session has expired. Please sign in again.")
                .status(401)
                .build()
        );
    }

    /**
     * Validate password strength
     * Returns error message if weak, null if strong enough
     */
    private String validatePasswordStrength(String password) {
        if (password == null || password.length() < 8) {
            return "Password must be at least 8 characters long";
        }

        int strength = 0;
        StringBuilder missing = new StringBuilder();

        if (HAS_UPPERCASE.matcher(password).find()) {
            strength++;
        } else {
            missing.append("uppercase letter, ");
        }

        if (HAS_LOWERCASE.matcher(password).find()) {
            strength++;
        } else {
            missing.append("lowercase letter, ");
        }

        if (HAS_DIGIT.matcher(password).find()) {
            strength++;
        } else {
            missing.append("number, ");
        }

        if (HAS_SPECIAL.matcher(password).find()) {
            strength++;
        }

        // Require at least 3 of 4 criteria
        if (strength < 3) {
            String missingStr = missing.toString();
            if (missingStr.endsWith(", ")) {
                missingStr = missingStr.substring(0, missingStr.length() - 2);
            }
            return "Password is too weak. Please include: " + missingStr;
        }

        return null;
    }
}
