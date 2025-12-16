# Build stage
FROM gradle:8.5-jdk17 AS build
WORKDIR /app
COPY . .
RUN chmod +x ./gradlew
RUN ./gradlew :examprep-api:build -x test

# Runtime stage - Optimized for Render.com free tier
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Install fonts for PDF processing
RUN apk update && \
    apk add --no-cache \
    fontconfig \
    ttf-liberation \
    ttf-dejavu \
    && fc-cache -f -v

# Create non-root user
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# Copy application
COPY --from=build /app/examprep-api/build/libs/examprep-api-1.0.0-SNAPSHOT.jar app.jar

EXPOSE 8080

# JVM Optimizations for Render.com Free Tier (512MB RAM):
# -XX:+UseSerialGC: Lower memory footprint than G1GC
# -Xms128m -Xmx384m: Conservative heap for 512MB container
# -XX:+TieredCompilation -XX:TieredStopAtLevel=1: Faster startup
# -Xss256k: Smaller thread stack size
# -XX:+UseStringDeduplication: Reduce memory for duplicate strings
# -Djava.security.egd: Faster random number generation
# -Dspring.jmx.enabled=false: Disable JMX to reduce memory
# -Dspring.main.lazy-initialization=true: Lazy load beans for faster startup
ENTRYPOINT ["java", \
    "-XX:+UseSerialGC", \
    "-Xms128m", \
    "-Xmx384m", \
    "-XX:+TieredCompilation", \
    "-XX:TieredStopAtLevel=1", \
    "-Xss256k", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-Dspring.jmx.enabled=false", \
    "-jar", "app.jar"]
