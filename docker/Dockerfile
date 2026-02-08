FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

COPY oneec-app/build/libs/oneec-oms.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
