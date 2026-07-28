FROM node:24-slim AS frontend
WORKDIR /app
COPY package.json package-lock.json vite.config.js ./
COPY client ./client
RUN npm ci && npm run build

FROM maven:3.9.9-eclipse-temurin-23 AS backend
WORKDIR /app
COPY backend ./backend
RUN mvn -q -f backend/pom.xml package -DskipTests

FROM eclipse-temurin:23-jre-slim
WORKDIR /app
COPY --from=backend /app/backend/target/zippy-backend-0.0.1-SNAPSHOT.jar /app/app.jar
COPY --from=frontend /app/public /app/public
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
