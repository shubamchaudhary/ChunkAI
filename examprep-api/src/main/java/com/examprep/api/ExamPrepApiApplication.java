package com.examprep.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "com.examprep")
@EntityScan("com.examprep.data.entity")
@EnableJpaRepositories("com.examprep.data.repository")
public class ExamPrepApiApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(ExamPrepApiApplication.class, args);
    }
}

