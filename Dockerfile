# syntax=docker/dockerfile:1

# ─────────────────────────────────────────────────────────────────────────────
# Stage 1 — build the bootJar with the pinned Gradle wrapper (8.10).
# Using the JDK image + the repo's wrapper guarantees the exact Gradle version
# rather than whatever a `gradle:*` tag ships.
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Copy only the build scaffolding first so dependency resolution caches
# independently of source changes (fast rebuilds when only src/ changes).
COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies || true

# Now the source; a src-only change reuses the warmed dependency layer above.
COPY src src
RUN ./gradlew --no-daemon clean bootJar \
    && cp build/libs/mayoclone-api.jar /workspace/app.jar

# ─────────────────────────────────────────────────────────────────────────────
# Stage 2 — slim JRE runtime, non-root.
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre AS runtime

# curl is used by the container HEALTHCHECK below.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    # Non-root runtime user (fixed uid/gid so k8s runAsUser can pin it).
    && groupadd --gid 10001 app \
    && useradd --uid 10001 --gid 10001 --home /app --shell /usr/sbin/nologin app

WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/app.jar /app/app.jar

USER 10001:10001
EXPOSE 8080

# JSON logs + prod config in containers; override at runtime if needed.
ENV SPRING_PROFILES_ACTIVE=prod \
    # Container-aware heap sizing; leave headroom for metaspace/threads.
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -XX:+UseContainerSupport -Djava.security.egd=file:/dev/./urandom"

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

# Use the shell form so $JAVA_OPTS word-splits; exec keeps java as PID 1.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
