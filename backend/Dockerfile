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
RUN apt-get update \
	&& apt-get install -y --no-install-recommends python3 ca-certificates \
	&& rm -rf /var/lib/apt/lists/* \
	&& useradd --system --uid 10001 --no-create-home appuser \
	&& install -d -o root -g root -m 0555 /opt/drift-collectors/tools/cdg201/mpa \
		/opt/drift-collectors/tools/cdg201/contextual /opt/drift-collectors/tools/maritime_notices
COPY tools/cdg201/mpa/mpa_oceansx_collect.py /opt/drift-collectors/tools/cdg201/mpa/
COPY tools/cdg201/contextual/nea_weather_collect.py /opt/drift-collectors/tools/cdg201/contextual/
COPY tools/maritime_notices/mpa_official_notices_collect.py /opt/drift-collectors/tools/maritime_notices/
RUN chmod 0444 /opt/drift-collectors/tools/cdg201/mpa/mpa_oceansx_collect.py \
	/opt/drift-collectors/tools/cdg201/contextual/nea_weather_collect.py \
	/opt/drift-collectors/tools/maritime_notices/mpa_official_notices_collect.py
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=25.0"
ENV APP_MARITIMECHANGE_COLLECTION_PROJECTDIRECTORY=/opt/drift-collectors
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS:-} -jar /app/app.jar --spring.profiles.active=production --server.port=${PORT:-8080}"]
