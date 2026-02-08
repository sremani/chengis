# ============================================================================
# Chengis CI/CD Engine — Multi-stage Docker Build
# ============================================================================
# Build:   docker build -t chengis:latest .
# Run:     docker run -p 8080:8080 -v chengis-data:/data chengis:latest
# Agent:   docker run chengis:latest agent --master-url http://master:8080
# ============================================================================

# --- Stage 1: Build the uberjar ---
FROM clojure:temurin-21-lein-jammy AS builder
WORKDIR /build

# Cache dependencies first (layer caching optimization)
COPY project.clj .
RUN lein deps

# Copy source and build
COPY src/ src/
COPY resources/ resources/
RUN lein uberjar

# --- Stage 2: Runtime image ---
FROM eclipse-temurin:21-jre-jammy

LABEL maintainer="chengis" \
      description="Chengis CI/CD Engine" \
      org.opencontainers.image.source="https://github.com/sremani/chengis"

WORKDIR /app

# Install curl for health checks
RUN apt-get update && apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

# Copy the uberjar from builder
COPY --from=builder /build/target/uberjar/chengis-*-standalone.jar chengis.jar

# Create data directories
RUN mkdir -p /data/workspaces /data/artifacts /data/plugins /data/backups

# Default configuration via environment variables
ENV CHENGIS_DATABASE_PATH=/data/chengis.db \
    CHENGIS_WORKSPACE_ROOT=/data/workspaces \
    CHENGIS_ARTIFACTS_ROOT=/data/artifacts \
    CHENGIS_SERVER_PORT=8080 \
    CHENGIS_SERVER_HOST=0.0.0.0

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:8080/health || exit 1

ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "chengis.jar"]
CMD ["serve"]
