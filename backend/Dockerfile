FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY backend/.mvn/ backend/.mvn/
COPY backend/mvnw backend/mvnw
COPY backend/pom.xml backend/pom.xml
COPY backend/src/ backend/src/
WORKDIR /workspace/backend
RUN chmod +x mvnw && ./mvnw -B -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/backend/target/*.jar /app/app.jar
RUN useradd --system --uid 10001 --no-create-home appuser
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=25.0"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS:-} -jar /app/app.jar --spring.profiles.active=production --server.port=${PORT:-8080}"]
