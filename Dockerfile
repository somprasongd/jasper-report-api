# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-26 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY libs/ libs/
# The font jar must be byte-identical to the one the distribution approval was given for.
RUN sha256sum -c libs/font-jar.sha256
RUN mvn -B -q -DskipTests dependency:go-offline

COPY src/main/ src/main/
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-noble AS runtime
# The base image already contains curl (healthcheck), fontconfig and freetype (AWT/Batik text for barcodes).
RUN groupadd --system --gid 10001 jasper \
	&& useradd --system --uid 10001 --gid jasper --home-dir /app --create-home --shell /usr/sbin/nologin jasper \
	&& mkdir -p /app/reports /app/cache /app/config \
	&& chown -R jasper:jasper /app

WORKDIR /app
COPY --from=build --chown=jasper:jasper /workspace/target/jasper-report-api-*.jar /app/app.jar

USER 10001:10001
ENV TZ=Asia/Bangkok
# JRXML folder (mount read-only), cache of compiled sub-reports / synced S3 folders, optional YAML config
VOLUME ["/app/cache"]
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=3 \
	CMD ["curl", "--fail", "--silent", "--output", "/dev/null", "http://127.0.0.1:8080/api/healthz"]
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -jar /app/app.jar"]
