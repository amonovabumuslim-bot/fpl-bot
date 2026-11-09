FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml Bot.java ./
RUN mvn -q package -DskipTests

FROM eclipse-temurin:17-jre
COPY --from=build /app/target/bot.jar /bot.jar
CMD ["java", "-jar", "/bot.jar"]
