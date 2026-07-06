plugins {
    java
    id("org.springframework.boot") version "3.3.6"
    id("io.spring.dependency-management") version "1.1.7"
}
group = "com.mayoclone"
version = "0.1.0-SNAPSHOT"
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
repositories { mavenCentral() }
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")

    // Flyway (Postgres) — owns the schema; Spring Boot BOM manages the versions.
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // JWT (access tokens) — HS256 signed.
    implementation("io.jsonwebtoken:jjwt-api:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")

    // Argon2 password hashing needs BouncyCastle at runtime.
    runtimeOnly("org.bouncycastle:bcprov-jdk18on:1.84")

    // Rate limiting.
    implementation("com.bucket4j:bucket4j-core:8.10.1")

    // Observability: Prometheus metrics registry (Micrometer; version from the Boot BOM).
    implementation("io.micrometer:micrometer-registry-prometheus")

    // Structured JSON logging (activated only by the `json`/prod logback profile).
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")

    // Lightweight tracing bridge + OTLP exporter. Wired via config but disabled by
    // default (sampling probability 0.0) so NO collector is needed to run or build.
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")

    runtimeOnly("org.postgresql:postgresql")

    // H2 is used ONLY for tests (Postgres-compatibility mode). Prod is Postgres.
    testRuntimeOnly("com.h2database:h2")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")

    // Testcontainers — powers the ONE Docker-gated Postgres suite (Flyway V1–V4 +
    // audit_log immutability trigger + repo round-trip against a REAL Postgres).
    // Versions are managed by the Spring Boot BOM. The suite is annotated to SKIP
    // cleanly when Docker is unavailable, so the build stays green without Docker.
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}
tasks.withType<Test> { useJUnitPlatform() }
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") { archiveFileName.set("mayoclone-api.jar") }
