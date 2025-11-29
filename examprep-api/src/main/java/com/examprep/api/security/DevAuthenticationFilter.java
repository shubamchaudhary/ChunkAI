package com.examprep.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.UUID;

/**
 * Development-only authentication filter that creates a mock authenticated user.
 * Only active when security.dev-mode.enabled=true.
 * 
 * WARNING: This filter creates a default user automatically for development.
 * NEVER enable this in production!
 */
@RequiredArgsConstructor
@Slf4j
public class DevAuthenticationFilter extends OncePerRequestFilter implements Ordered {
    
    @Override
    public int getOrder() {
        return 1;
    }
    
    private final boolean devModeEnabled;
    
    private static final UUID DEFAULT_DEV_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String DEFAULT_DEV_USER_EMAIL = "dev@example.com";
    
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        
        // Only apply in dev mode
        if (!devModeEnabled) {
            filterChain.doFilter(request, response);
            return;
        }
        
        // Skip auth endpoints - they should handle their own authentication
        if (request.getRequestURI().startsWith("/api/v1/auth")) {
            filterChain.doFilter(request, response);
            return;
        }
        
        // If no authentication exists, create a dev user authentication
        Authentication existingAuth = SecurityContextHolder.getContext().getAuthentication();
        if (existingAuth == null || !existingAuth.isAuthenticated()) {
            log.debug("[DEV_MODE] Creating mock authentication for request: {}", request.getRequestURI());
            
            // Create a simple authentication token with the default dev user ID
            Authentication devAuth = new UsernamePasswordAuthenticationToken(
                DEFAULT_DEV_USER_ID.toString(), // Principal is user ID as string
                null, // No credentials
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
            );
            
            SecurityContextHolder.getContext().setAuthentication(devAuth);
            
            log.debug("[DEV_MODE] Authenticated as dev user: {} ({})", 
                DEFAULT_DEV_USER_EMAIL, DEFAULT_DEV_USER_ID);
        }
        
        filterChain.doFilter(request, response);
    }
}

