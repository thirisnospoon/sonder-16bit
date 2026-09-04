package sonder.report;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Выгрузка постов в плоский файл для свода на COBOL.
 *
 * <p>ЗАПУСКАЕТСЯ ОТДЕЛЬНО ОТ ПРИЛОЖЕНИЯ, без Spring. Пакет не должен
 * поднимать веб-сервер, линию к ядру и планировщик ради того, чтобы
 * прочитать таблицу: всё это стоит секунд, требует живой ноды и роняет
 * выгрузку там, где она ни при чём.
 *
 * <p>ПОЧЕМУ НЕ SQL-СКРИПТОМ, как было сначала. Разметка записи —
 * фиксированная ширина в БАЙТАХ, а `RPAD` в Firebird добивает до
 * СИМВОЛОВ. Имя «Андрей» в поле на 240 символов давало 246 байт, всё
 * последующее уезжало на шесть, и свод считал по мусору: 90 300 байт на
 * пост при доменном пределе в тысячу знаков. Выглядело правдоподобно.
 *
 * <p>{@link DigestRecord} порождается из того же контракта, что и
 * копибук COBOL, добивает по байтам и ОТКАЗЫВАЕТСЯ обрезать: поле, не
 * влезшее в ширину, — это отказ выгрузки, а не молча испорченная
 * запись. Пока писателя не было, порождённый класс не использовался
 * ничем, то есть контракт правил лишь одну сторону из двух.
 *
 * <p>СОРТИРОВКА В ЗАПРОСЕ. Контрольный переход в COBOL работает только
 * на упорядоченном входе; индекс есть у базы, а не у пакета.
 */
public final class DigestExport {

    /**
     * Выборка. Длины считает БАЗА — {@code OCTET_LENGTH} и
     * {@code CHAR_LENGTH}: считать их здесь значило бы завести третье
     * мнение о том, что такое длина.
     */
    private static final String SQL =
            "SELECT p.id, u.nick, u.display_name, p.created_at,"
                    + " OCTET_LENGTH(p.body), CHAR_LENGTH(p.body)"
                    + " FROM posts p"
                    + " JOIN users u ON u.id = p.author_id"
                    + " WHERE p.status = 'VISIBLE'"
                    + "   AND p.created_at >= ?"
                    + "   AND p.created_at < ?"
                    + " ORDER BY u.nick, p.created_at";

    private static final DateTimeFormatter ДЕНЬ =
            DateTimeFormatter.ofPattern("yyyyMMdd");

    private DigestExport() {
    }

    /**
     * Начало периода.
     *
     * <p>ГРАНИЦА ПОЛУОТКРЫТАЯ: от начала дня включительно до начала
     * следующего исключительно. Иначе пост, созданный ровно в полночь,
     * попал бы в два свода сразу или ни в один — смотря как округлили, —
     * и заметить это можно было бы только сложением двух отчётов.
     *
     * <p>По умолчанию ВЧЕРАШНИЙ день: свод ночной, он считает
     * закончившиеся сутки, а не текущие, у которых ещё будут записи.
     */
    private static LocalDate начало(String[] args) {
        for (String a : args) {
            if (a.startsWith("--since=")) {
                return LocalDate.parse(a.substring("--since=".length()));
            }
        }
        return LocalDate.now(ZoneOffset.UTC).minusDays(1);
    }

    /** Конец периода, исключительно. По умолчанию — сутки от начала. */
    private static LocalDate конец(String[] args, LocalDate since) {
        for (String a : args) {
            if (a.startsWith("--until=")) {
                return LocalDate.parse(a.substring("--until=".length()));
            }
        }
        return since.plusDays(1);
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("нужно: DigestExport <путь>"
                    + " [--since=ГГГГ-ММ-ДД] [--until=ГГГГ-ММ-ДД]");
            System.exit(2);
        }
        String url = требуется("SONDER_DB_URL");
        String user = либо("SONDER_DB_USER", "sysdba");
        String password = либо("SONDER_DB_PASSWORD", "masterkey");

        Path target = Paths.get(args[0]);
        LocalDate since = начало(args);
        LocalDate until = конец(args, since);
        if (!until.isAfter(since)) {
            System.err.println("период пуст: --since=" + since
                    + " не раньше --until=" + until);
            System.exit(2);
        }

        try {
            long записей = выгрузить(url, user, password, target, since, until);
            System.out.println("период: " + since + " .. " + until
                    + " (не включая), записей: " + записей
                    + ", по " + DigestRecord.BYTES + " байт");
        } catch (SQLException | IOException e) {
            System.err.println("выгрузка не удалась: " + e);
            System.exit(1);
        }
    }

    private static long выгрузить(String url, String user, String password,
                                  Path target,
                                  LocalDate since, LocalDate until)
            throws SQLException, IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        try (Connection c = DriverManager.getConnection(url, user, password);
             PreparedStatement ps = c.prepareStatement(SQL)) {
            ps.setTimestamp(1, Timestamp.from(
                    since.atStartOfDay(ZoneOffset.UTC).toInstant()));
            ps.setTimestamp(2, Timestamp.from(
                    until.atStartOfDay(ZoneOffset.UTC).toInstant()));
            return записать(ps, target);
        }
    }

    /**
     * Прогнать выборку в файл.
     *
     * <p>Файл создаётся ВСЕГДА, даже когда записей ноль. Пустой период —
     * обычное дело: выходной, первый день после запуска, сутки без
     * единого поста. Отсутствие файла заставило бы пакет отличать
     * «нечего считать» от «выгрузка не отработала», а это разные беды с
     * разной срочностью. Свод по пустому входу печатает шапку и нули:
     * это ответ, а не отказ.
     */
    private static long записать(PreparedStatement ps, Path target)
            throws SQLException, IOException {
        long n = 0;
        try (ResultSet rs = ps.executeQuery();
             OutputStream out = new BufferedOutputStream(
                     Files.newOutputStream(target))) {
            while (rs.next()) {
                Timestamp created = rs.getTimestamp(4);
                LocalDate день = created.toInstant()
                        .atZone(ZoneOffset.UTC).toLocalDate();
                byte[] запись = DigestRecord.of(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        день.format(ДЕНЬ),
                        rs.getLong(5),
                        rs.getLong(6));
                out.write(запись);
                // Перевод строки: файл читается как LINE SEQUENTIAL, и
                // ровно один байт разделителя объявлен контрактом.
                out.write('\n');
                n++;
            }
        }
        return n;
    }

    private static String требуется(String имя) {
        String v = System.getenv(имя);
        if (v == null || v.isEmpty()) {
            System.err.println("нужна переменная окружения " + имя);
            System.exit(2);
        }
        return v;
    }

    private static String либо(String имя, String умолчание) {
        String v = System.getenv(имя);
        return (v == null || v.isEmpty()) ? умолчание : v;
    }
}
