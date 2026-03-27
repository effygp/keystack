# Build stage
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Copy the entire project
COPY . .

# Build the fat JAR (Shadow JAR)
# We use --no-daemon to avoid persistent processes in Docker build
RUN ./gradlew :keystack-cli:shadowJar --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Add a non-root user for security
RUN adduser -D keystack

# Copy the shadow JAR from the builder stage
COPY --from=builder /app/keystack-cli/build/libs/keystack-all.jar /app/keystack.jar

# Set ownership to the non-root user
RUN chown -R keystack:keystack /app

# Expose the default Keystack/LocalStack port
EXPOSE 4566

# Switch to the non-root user
USER keystack

# Entrypoint to run the Keystack CLI with the 'start' command
ENTRYPOINT ["java", "-jar", "/app/keystack.jar", "start"]
