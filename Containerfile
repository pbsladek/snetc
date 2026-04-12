# ── Stage 1: build uberjar ────────────────────────────────────────────────────
FROM clojure:temurin-21-tools-deps-alpine AS builder

WORKDIR /build

# Copy dependency descriptors first so this layer is cached until they change.
COPY deps.edn build.clj ./

# Pre-fetch all dependencies (main + build tooling).
RUN clojure -P && clojure -P -T:build

# Copy source and build the uberjar.
COPY src/ src/
RUN clojure -T:build uber

# ── Stage 2: minimal runtime ──────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=builder /build/target/snetc-0.1.0.jar snetc.jar

ENTRYPOINT ["java", "-jar", "snetc.jar"]
