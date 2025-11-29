package com.examprep.api.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@Configuration
@Slf4j
@Profile("production")
public class DatabaseConfig {

    @Value("${spring.datasource.url:}")
    private String dataSourceUrl;

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        DataSourceProperties properties = new DataSourceProperties();
        
        String url = dataSourceUrl;
        
        // If URL contains credentials, parse and rebuild without them
        if (url != null && !url.isEmpty()) {
            try {
                // Remove jdbc: prefix if present for parsing
                String urlToParse = url.startsWith("jdbc:") ? url.substring(5) : url;
                
                if (urlToParse.contains("@")) {
                    // Parse URL with credentials: postgresql://user:pass@host:port/db
                    URI uri = new URI(urlToParse);
                    String userInfo = uri.getUserInfo();
                    
                    if (userInfo != null && userInfo.contains(":")) {
                        String[] creds = userInfo.split(":", 2);
                        String username = URLDecoder.decode(creds[0], StandardCharsets.UTF_8);
                        String password = URLDecoder.decode(creds[1], StandardCharsets.UTF_8);
                        
                        // Rebuild URL without credentials
                        int port = uri.getPort() > 0 ? uri.getPort() : 5432;
                        String host = uri.getHost();
                        String path = uri.getPath();
                        
                        // Use external hostname if internal hostname is detected
                        if (host != null && host.startsWith("dpg-") && !host.contains("render.com")) {
                            host = host + ".singapore-postgres.render.com";
                            log.info("Using external hostname: {}", host);
                        }
                        
                        String cleanUrl = String.format("jdbc:postgresql://%s:%d%s", host, port, path);
                        properties.setUrl(cleanUrl);
                        properties.setUsername(username);
                        properties.setPassword(password);
                        
                        log.info("Parsed DATABASE_URL - host: {}, port: {}, database: {}", host, port, path);
                        return properties;
                    }
                }
                
                // If no credentials in URL, just normalize jdbc: prefix
                if (!url.startsWith("jdbc:")) {
                    if (url.startsWith("postgresql://")) {
                        url = "jdbc:" + url;
                        log.info("Normalized JDBC URL: added jdbc: prefix");
                    }
                }
                
                // Fix hostname if internal
                if (url.contains("dpg-") && !url.contains("render.com")) {
                    url = url.replaceFirst("dpg-([^/]+)", "dpg-$1.singapore-postgres.render.com");
                    log.info("Fixed hostname to external: {}", url);
                }
                
                properties.setUrl(url);
            } catch (Exception e) {
                log.error("Error parsing DATABASE_URL: {}", e.getMessage(), e);
                // Fallback to original URL
                properties.setUrl(url);
            }
        }
        
        return properties;
    }
}

