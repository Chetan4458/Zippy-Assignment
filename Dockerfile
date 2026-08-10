FROM node:26-slim AS frontend-dependencies
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

FROM frontend-dependencies AS frontend-test
COPY vite.config.js ./
COPY client ./client
RUN npm run test:frontend

FROM frontend-test AS frontend-build
RUN npm run build

FROM maven:3.9.15-eclipse-temurin-26 AS backend-build
WORKDIR /app
COPY backend/pom.xml ./backend/pom.xml
RUN mvn -q -f backend/pom.xml dependency:go-offline
COPY backend/src ./backend/src
RUN mvn -q -f backend/pom.xml package

FROM eclipse-temurin:25-jre-jammy AS runtime
WORKDIR /app
ENV SPRING_PROFILES_ACTIVE=prod
RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system zippy \
    && useradd --system --gid zippy --home-dir /app --shell /usr/sbin/nologin zippy \
    && mkdir -p /app/data \
    && chown -R zippy:zippy /app
COPY --from=backend-build --chown=zippy:zippy /app/backend/target/zippy-backend-0.0.1-SNAPSHOT.jar /app/app.jar
COPY --from=frontend-build --chown=zippy:zippy /app/dist /app/public
COPY --chown=zippy:zippy scripts/container-healthcheck.sh /app/healthcheck.sh
RUN chmod 0555 /app/healthcheck.sh
EXPOSE 8080
USER zippy
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=5 \
  CMD ["/app/healthcheck.sh"]
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
