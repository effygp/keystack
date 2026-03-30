# Build stage
FROM eclipse-temurin:17-jdk AS builder

WORKDIR /app

# Copy the entire project
COPY . .

# Ensure gradlew has executable permissions
RUN chmod +x gradlew

# Build the fat JAR (Shadow JAR) with memory limits
# We use -P flags to ensure settings are passed correctly
RUN ./gradlew :keystack-cli:shadowJar --no-daemon \
    -Dorg.gradle.jvmargs="-Xmx1g -XX:MaxMetaspaceSize=512m" \
    -Dorg.gradle.parallel=false \
    -Dorg.gradle.workers.max=1 \
    -Pkotlin.compiler.execution.strategy=in-process

# Runtime stage
FROM eclipse-temurin:17-jre

WORKDIR /app

# Add a non-root user for security (Debian/Ubuntu syntax)
RUN useradd -m keystack

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
