package com.loglens.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.loglens")
@EntityScan("com.loglens.data.entity")
@EnableJpaRepositories("com.loglens.data.repository")
@EnableScheduling
public class LogLensApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(LogLensApplication.class, args);
    }
}

