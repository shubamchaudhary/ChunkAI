plugins {
    `java-library`
    id("io.spring.dependency-management")
}

configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
    imports {
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
    }
}

dependencies {
    api(project(":examprep-common"))
    
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    api("org.postgresql:postgresql:42.7.1")
    
    // pgvector support
    api("com.pgvector:pgvector:0.1.4")
    
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.h2database:h2")
}

