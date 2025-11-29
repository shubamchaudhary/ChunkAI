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
    fonts-liberation \
    ttf-dejavu \
    ttf-droid \
    ttf-freefont \
    && fc-cache -f -v

RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring
COPY --from=build /app/examprep-api/build/libs/examprep-api-1.0.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

