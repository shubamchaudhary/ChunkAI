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
    api(project(":examprep-data"))
    api(project(":examprep-llm"))
    
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework:spring-context")
    
    // File processing
    implementation("org.apache.poi:poi:5.2.5")
    implementation("org.apache.poi:poi-ooxml:5.2.5")
    implementation("org.apache.poi:poi-scratchpad:5.2.5")
    implementation("org.apache.pdfbox:pdfbox:3.0.1")
    
    // OCR
    implementation("net.sourceforge.tess4j:tess4j:5.10.0")
}

