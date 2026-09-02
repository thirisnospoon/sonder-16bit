package sonder.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Контекст с живым дренажом умирает вместе со своим классом.
 *
 * <p><b>Это правило написано после двух одинаковых поломок.</b> Spring
 * КЭШИРУЕТ контексты между классами тестов, чтобы не поднимать приложение
 * заново. Контекст с включённой очередью уносит с собой живой фоновый
 * поток — расписание или подписку на события базы, — и поток этот
 * продолжает разбирать очередь до конца прогона.
 *
 * <p>Ломается при этом не тот класс, который контекст поднял. В первый раз
 * упал {@code SchemaIT} — проверка схемы базы, к дренажу отношения не
 * имеющая; во второй раз {@code OutboxPumpIT} и {@code OutboxIT}, где
 * строки исчезали из очереди между вставкой и проверкой. Искать причину
 * при этом начинают там, где упало.
 *
 * <p>Отсюда правило: класс, включающий {@code sonder.outbox.enabled},
 * обязан объявить {@code @DirtiesContext}. Проверяется исходный текст —
 * свойство задаётся строкой в {@code @DynamicPropertySource}, и никакой
 * аннотацией это не выражено.
 */
class LiveContextsDieWithClassTest {

    /** Как выглядит включение очереди в тестовых свойствах. */
    private static final String ENABLES_OUTBOX =
            "\"sonder.outbox.enabled\", () -> \"true\"";

    private static final String DIRTIES = "@DirtiesContext";
    private static final String DIRTIES_QUALIFIED =
            "org.springframework.test.annotation.DirtiesContext(";

    @Test
    @DisplayName("тест с включённой очередью не оставляет контекст жить дальше")
    void outboxEnablingTestsDirtyTheirContext() throws IOException {
        List<String> scanned = new ArrayList<>();
        List<String> offenders = new ArrayList<>();

        Files.walkFileTree(Paths.get("src/test/java"), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                if (!file.toString().endsWith(".java")) {
                    return FileVisitResult.CONTINUE;
                }
                String text = new String(Files.readAllBytes(file),
                        StandardCharsets.UTF_8);
                if (!text.contains(ENABLES_OUTBOX)) {
                    return FileVisitResult.CONTINUE;
                }
                scanned.add(file.getFileName().toString());
                if (!text.contains(DIRTIES) && !text.contains(DIRTIES_QUALIFIED)) {
                    offenders.add(file.getFileName().toString());
                }
                return FileVisitResult.CONTINUE;
            }
        });

        assertFalse(scanned.isEmpty(),
                "не найдено ни одного теста с включённой очередью — правило "
                        + "проверяло бы пустоту, а такое зелено всегда");

        assertTrue(offenders.isEmpty(),
                "тест включает очередь и не помечен @DirtiesContext: "
                        + offenders + ". Контекст переживёт класс, фоновый "
                        + "дренаж продолжит разбирать очередь, и упадёт не "
                        + "этот класс, а соседний");
    }
}
