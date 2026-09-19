# Build stage
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /workspace

# Cache dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Build application
COPY src src
RUN mvn clean package -DskipTests -B

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
LABEL maintainer="AURORA Core Platform Team"
LABEL description="AURORA Control Plane - Autonomous Reliability & Self-Healing Infrastructure"

# Security: Create non-root system group and user
RUN addgroup -S aurora && adduser -S aurora -G aurora

WORKDIR /app

# Copy executable jar from builder stage
COPY --from=builder /workspace/target/*.jar /app/aurora-control-plane.jar

# Adjust ownership
RUN chown -R aurora:aurora /app

USER aurora:aurora

# Expose HTTP port
EXPOSE 8080

# Configure JVM container options
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# Healthcheck
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/aurora-control-plane.jar"]
