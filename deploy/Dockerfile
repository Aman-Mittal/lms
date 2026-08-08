# Copyright 2026 Aman Mittal
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# syntax=docker/dockerfile:1.7

# Base images are ARG-parameterised and pinned, matching the house pattern.
#
# The builder is GraalVM Community Edition: GPLv2 WITH the Classpath Exception.
# It must NOT be swapped for an Oracle GraalVM image, which ships under the
# proprietary GraalVM Free Terms and Conditions and would break this project's
# Apache-2.0 distribution. See DOCS/adr/0003.
#
# Pinned by digest, not by tag. `native-image-community` publishes only `21` and
# `latest`, and `latest` is a moving target -- an unnoticed jump would change
# both the JDK and the native-image version underneath a reproducible build.
# This digest is GraalVM CE 25.2.4 / JDK 25.0.4.
ARG NATIVE_BUILDER_IMAGE=ghcr.io/graalvm/native-image-community@sha256:7187b4a37ebd4055f7a5fb518f9882ad6e25331862633991cbe007a1f43d739d
ARG JVM_BUILDER_IMAGE=eclipse-temurin:25-jdk-noble
ARG JVM_RUNTIME_IMAGE=eclipse-temurin:25-jre-noble
ARG NATIVE_RUNTIME_IMAGE=gcr.io/distroless/base-debian12:nonroot


# ---------------------------------------------------------------------------
# deps -- resolve the dependency graph once so it caches independently of source
# ---------------------------------------------------------------------------
FROM ${JVM_BUILDER_IMAGE} AS deps
WORKDIR /build
COPY backend/.mvn/ .mvn/
COPY backend/mvnw backend/pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp dependency:go-offline


# ---------------------------------------------------------------------------
# jvm-build -- fast path, used by docker-compose for local development
# ---------------------------------------------------------------------------
FROM ${JVM_BUILDER_IMAGE} AS jvm-build
WORKDIR /build
COPY backend/.mvn/ .mvn/
COPY backend/mvnw backend/pom.xml ./
COPY backend/src/ src/
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -DskipTests package


# ---------------------------------------------------------------------------
# jvm -- JVM runtime image. Fallback target if image-backed deploys turn out to
#        be unavailable on Render's free instance type.
# ---------------------------------------------------------------------------
FROM ${JVM_RUNTIME_IMAGE} AS jvm
WORKDIR /app
RUN useradd --system --uid 10001 --no-create-home lms
COPY --from=jvm-build /build/target/*.jar /app/application.jar
USER 10001
EXPOSE 8080
# MaxRAMPercentage rather than a fixed -Xmx: the container limit is the source
# of truth, and 512 MB leaves little room for a wrong guess.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k"
ENTRYPOINT ["java", "-jar", "/app/application.jar"]


# ---------------------------------------------------------------------------
# native-build -- GraalVM native image. Needs ~6-8 GB RAM and 10+ minutes,
#                 which is why this runs in CI rather than on Render.
# ---------------------------------------------------------------------------
FROM ${NATIVE_BUILDER_IMAGE} AS native-build
WORKDIR /build
COPY backend/.mvn/ .mvn/
COPY backend/mvnw backend/pom.xml ./
COPY backend/src/ src/
# `package`, not the bare `native:compile` goal. Invoking the goal directly
# skips the lifecycle, so resources are never copied into target/classes --
# application.yaml goes missing, the datasource has no URL, and Spring Boot's
# AOT step (which really does instantiate beans at build time) fails with
# "Failed to determine a suitable driver class". The native profile binds
# native:compile-no-fork to the package phase.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -Pnative -DskipTests package


# ---------------------------------------------------------------------------
# native -- the deployed image. Distroless, non-root, no shell, no package
#           manager. This is the default target.
# ---------------------------------------------------------------------------
FROM ${NATIVE_RUNTIME_IMAGE} AS native
WORKDIR /app
COPY --from=native-build /build/target/lms-platform /app/lms-platform
# `nonroot` in the distroless base is uid 65532.
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["/app/lms-platform"]
