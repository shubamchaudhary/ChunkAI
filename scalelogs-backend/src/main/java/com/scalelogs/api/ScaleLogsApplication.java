package com.scalelogs.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.scalelogs")
@EntityScan("com.scalelogs.data.entity")
@EnableJpaRepositories("com.scalelogs.data.repository")
@EnableScheduling
public class ScaleLogsApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(ScaleLogsApplication.class, args);
    }
}

