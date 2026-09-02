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
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Правило ADR-0016 механически: {@code events} не читает таблиц
 * {@code core}.
 *
 * <p>Правило, записанное только в документе, соблюдается ровно до тех пор,
 * пока его помнят. Оно уже один раз нарушилось — проекция читала
 * {@code posts} за временем создания поста, — и обнаружилось это чтением
 * архитектуры, а не сборкой.
 *
 * <p><b>Проверяется исходный текст, а не байт-код,</b> и это не лень.
 * Запрос живёт строковым литералом; ArchUnit видит зависимости между
 * классами и про содержимое строк не знает ничего. Обойти проверку,
 * собрав имя таблицы из кусков, можно — но такое в ревью и видно, а
 * случайное {@code JOIN follows} не видно совсем.
 *
 * <p>Ищутся не упоминания, а обращения: имя таблицы после {@code FROM},
 * {@code JOIN}, {@code INTO} или {@code UPDATE}. Иначе проверка ругалась
 * бы на собственные комментарии, объясняющие, почему этих таблиц тут нет.
 */
class EventsOwnsItsDataTest {

    /** Пакеты, которые в бою станут единицей {@code events}. */
    private static final List<String> EVENTS_PACKAGES = Arrays.asList(
            "sonder/shell/outbox",
            "sonder/shell/projection",
            "sonder/shell/stream");

    /** Таблицы write-модели: их пишет и читает {@code core}. */
    private static final List<String> CORE_TABLES = Arrays.asList(
            "posts", "users", "follows", "comments", "sessions");

    private static final Pattern ACCESS = Pattern.compile(
            "\\b(?:FROM|JOIN|INTO|UPDATE)\\s+(" + String.join("|", CORE_TABLES) + ")\\b",
            Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("конвейер событий не обращается к таблицам ядра оболочки")
    void eventsDoesNotTouchCoreTables() throws IOException {
        List<String> sources = new ArrayList<>();
        List<String> offences = new ArrayList<>();

        for (String pkg : EVENTS_PACKAGES) {
            Path dir = Paths.get("src/main/java").resolve(pkg);
            assertTrue(Files.isDirectory(dir),
                    "нет каталога " + dir + ": проверка искала бы в пустоте");
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    if (!file.toString().endsWith(".java")) {
                        return FileVisitResult.CONTINUE;
                    }
                    sources.add(file.toString());
                    String text = new String(Files.readAllBytes(file),
                            StandardCharsets.UTF_8);
                    Matcher m = ACCESS.matcher(text);
                    while (m.find()) {
                        offences.add(file.getFileName() + ": " + m.group());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        assertFalse(sources.isEmpty(),
                "не найдено ни одного исходника конвейера — проверка была бы пустой");

        assertTrue(offences.isEmpty(),
                "конвейер событий обращается к таблицам core: " + offences
                        + ". По ADR-0016 содержимое агрегата берётся вызовом по "
                        + "IIOP, а граф подписок — своей проекцией. Прямое "
                        + "чтение делает разделение на core и events "
                        + "невозможным навсегда");
    }

    /**
     * Обратная сторона: проверка знает, что искать.
     *
     * <p>Шаблон, который не находит ничего нигде, зелен всегда. Здесь он
     * прикладывается к тексту, где обращение заведомо есть, — и обязан его
     * найти.
     */
    @Test
    @DisplayName("правило умеет заметить обращение")
    void ruleCanFire() {
        String sample = "\"SELECT f.follower_id FROM follows f WHERE f.target_id = ?\"";
        assertTrue(ACCESS.matcher(sample).find(),
                "шаблон не видит прямого обращения — значит, не увидел бы и "
                        + "настоящего");

        String innocent = "// Таблица follows принадлежит core, и читать её нельзя.";
        assertFalse(ACCESS.matcher(innocent).find(),
                "шаблон срабатывает на комментарии: проверка, ругающаяся на "
                        + "объяснение самой себя, будет отключена в первый же день");
    }
}
