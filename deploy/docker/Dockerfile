# One image recipe for every Spring Boot service: docker build --build-arg MODULE=<service> ...
# The jar is built outside (./gradlew :services:<module>:bootJar), so the build context is the
# repo root and Dockerfile.dockerignore keeps it down to those jars. See ADR-0023.

ARG JRE_IMAGE=eclipse-temurin:21.0.10_7-jre-noble

FROM ${JRE_IMAGE} AS extract
ARG MODULE
WORKDIR /build
COPY services/${MODULE}/build/libs/${MODULE}.jar app.jar
# Splits the fat jar into layers by change frequency, so a code-only rebuild re-ships a few KB
# instead of the ~40-120 MB of dependencies. Flags checked with `-Djarmode=tools -jar x.jar help extract`.
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted --application-filename app.jar

FROM ${JRE_IMAGE}
# Numeric ids: the kubelet can only verify runAsNonRoot against a numeric USER.
RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
# Root-owned and read-only to the app user: the code a process runs is not writable by that process.
COPY --from=extract /build/extracted/dependencies/ ./
COPY --from=extract /build/extracted/spring-boot-loader/ ./
COPY --from=extract /build/extracted/snapshot-dependencies/ ./
COPY --from=extract /build/extracted/application/ ./

# Heap is a percentage of the container limit, never a fixed -Xmx that can exceed it. 75 leaves room
# for metaspace, thread stacks and direct buffers; a service with native stores (RocksDB) lowers it.
# ExitOnOutOfMemoryError: die and be restarted rather than limp on in an undefined state.
# Set through JAVA_TOOL_OPTIONS so the chart can override it without a shell in the entrypoint.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
