FROM eclipse-temurin:25-jre-alpine
COPY build/libs/debridav-0.10.1.jar /app/debridav.jar
ENTRYPOINT ["java", "-jar", "/app/debridav.jar"]
