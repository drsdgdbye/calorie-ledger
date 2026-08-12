# ===== Build Stage =====
FROM eclipse-temurin:21-jdk-alpine AS builder

# Install sbt
RUN apk add --no-cache bash curl \
    && curl -fL https://github.com/sbt/sbt/releases/download/v1.10.7/sbt-1.10.7.tgz | tar xz -C /opt \
    && ln -s /opt/sbt/bin/sbt /usr/local/bin/sbt

WORKDIR /build

# Cache sbt dependencies
COPY build.sbt .
COPY project/ project/
RUN sbt update

# Copy source and build
COPY src/ src/
COPY frontend/ frontend/
RUN sbt assembly

# ===== Runtime Stage =====
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S app && adduser -S app -G app \
    && apk add --no-cache curl

# Copy fat JAR from builder
COPY --from=builder /build/target/out/jvm/scala-3.8.4/calorie-ledger/calorie-ledger.jar /app/calorie-ledger.jar

USER app
WORKDIR /app
EXPOSE 10001
ENTRYPOINT ["java", "-jar", "/app/calorie-ledger.jar"]