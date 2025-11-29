package com.examprep.api.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;

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
        
        // Normalize JDBC URL - add jdbc: prefix if missing
        String url = dataSourceUrl;
        if (url != null && !url.isEmpty() && !url.startsWith("jdbc:")) {
            if (url.startsWith("postgresql://")) {
                url = "jdbc:" + url;
                log.info("Normalized JDBC URL: added jdbc: prefix");
            }
        }
        
        properties.setUrl(url);
        return properties;
    }
}

