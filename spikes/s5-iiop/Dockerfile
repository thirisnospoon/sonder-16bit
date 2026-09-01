# Образ для спайка S5: JDK 8 — последний, несущий CORBA и idlj в составе.
FROM eclipse-temurin:8-jdk

WORKDIR /s5
COPY idl/ idl/
COPY src/ src/

# idlj генерирует стабы и скелетоны из IDL. В JDK 11 его уже нет — это и есть
# то, что делает Java 8 требованием, а не вкусом (ADR-0004).
RUN mkdir -p gen classes \
 && idlj -fall -td gen idl/enrichment.idl \
 && javac -encoding UTF-8 -d classes $(find gen -name '*.java') \
 && javac -encoding UTF-8 -cp classes -d classes src/*.java \
 && echo "сгенерировано классов: $(find classes -name '*.class' | wc -l)"

ENV JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8"
