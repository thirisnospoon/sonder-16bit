# Клиент спайка S6. Java 8 — ограничение проекта, не выбор здесь.
FROM maven:3.9-eclipse-temurin-8

WORKDIR /s6
COPY pom.xml .
# Отдельным слоем, чтобы правка кода не тянула зависимости заново.
RUN mvn -q -B dependency:go-offline || true
COPY src/ src/
RUN mvn -q -B package
CMD ["java", "-cp", "target/classes:target/lib/*", "FirebirdProbe"]
