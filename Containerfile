# syntax=docker/dockerfile:1.7

ARG CLOJURE_IMAGE=clojure:temurin-21-tools-deps-bookworm
ARG NATIVE_IMAGE=ghcr.io/graalvm/native-image-community:21
ARG RUNTIME_IMAGE=dhi.io/static:20250419-glibc-debian13

FROM ${CLOJURE_IMAGE} AS jar-builder

WORKDIR /build

COPY deps.edn build.clj ./
RUN --mount=type=cache,target=/root/.m2 \
    clojure -P && clojure -P -T:build

COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 \
    clojure -T:build uber

FROM ${NATIVE_IMAGE} AS native-builder

WORKDIR /build

COPY --from=jar-builder /build/target/snetc-0.1.0.jar /build/snetc.jar
RUN native-image \
    -jar /build/snetc.jar \
    -o /build/snetc \
    --initialize-at-build-time \
    --no-fallback \
    -H:+UnlockExperimentalVMOptions \
    -H:+StaticExecutableWithDynamicLibC \
    -H:-UnlockExperimentalVMOptions \
    -H:+ReportExceptionStackTraces

FROM ${RUNTIME_IMAGE}

COPY --from=native-builder /build/snetc /snetc

ENTRYPOINT ["/snetc"]
