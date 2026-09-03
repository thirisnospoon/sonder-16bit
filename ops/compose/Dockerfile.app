# Оболочка Sonder.
#
# Две ступени: сборка тянет весь Maven и все зависимости, запуск не несёт
# ни того, ни другого. Разница не косметическая — образ со сборочным
# инструментарием внутри это лишние сотни мегабайт на каждом узле и
# лишняя поверхность там, где ей быть незачем.

# Имя ступени латиницей: это идентификатор формата Dockerfile, а не
# текст, и кириллицу он не принимает («invalid name for build stage»).
FROM maven:3.9-eclipse-temurin-8 AS build
WORKDIR /src

# Сначала описания, потом исходники: слой зависимостей переживает правку
# кода, и повторная сборка не выкачивает интернет заново.
COPY pom.xml ./
COPY core/pom.xml core/
RUN mvn -B --no-transfer-progress -f core/pom.xml dependency:go-offline || true

COPY contracts/ contracts/
COPY core/ core/
RUN mvn -B --no-transfer-progress -f core/pom.xml -DskipTests package

FROM eclipse-temurin:8-jre
WORKDIR /app

# Обычный пользователь, а не root. Оболочка ходит в сеть и разбирает
# то, что пришло снаружи; лишние права ей ни к чему.
RUN useradd --system --create-home --uid 10001 sonder
USER sonder

COPY --from=build /src/core/target/core-0.1.0-SNAPSHOT.jar /app/sonder.jar

# Порт HTTP и порт линии. Второй ЖДЁТ ноду: нульмодем DOSBox приходит
# клиентом, поэтому оболочка обязана подняться первой.
EXPOSE 8080 5077

ENTRYPOINT ["java", "-jar", "/app/sonder.jar"]
