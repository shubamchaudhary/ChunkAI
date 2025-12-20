# Build stage
FROM gradle:8.5-jdk17 AS build
WORKDIR /app
COPY . .
RUN chmod +x ./gradlew
RUN ./gradlew :examprep-api:build -x test

# Runtime stage
FROM eclipse-temurin:17-jdk-alpine
WORKDIR /app

# Install fonts for PDF processing (fixes "Using fallback font LiberationSans for Helvetica" warnings)
RUN apk update && \
    apk add --no-cache \
    fontconfig \
    ttf-liberation \
    ttf-dejavu \
    ttf-droid \
    ttf-freefont \
    && fc-cache -f -v

RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring
COPY --from=build /app/examprep-api/build/libs/examprep-api-1.0.0-SNAPSHOT.jar app.jar
EXPOSE 8080
# JVM optimizations for Render.com free tier (512MB RAM limit)
# -XX:+UseContainerSupport: Respect container memory limits
# -XX:MaxRAMPercentage=75: Use up to 75% of available RAM for heap
# -XX:+UseG1GC: Use G1 garbage collector (better for limited memory)
# -XX:+UseStringDeduplication: Reduce memory usage by deduplicating strings
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-XX:+UseG1GC", \
    "-XX:+UseStringDeduplication", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]

