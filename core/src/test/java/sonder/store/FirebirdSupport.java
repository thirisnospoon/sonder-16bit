package sonder.store;

import org.flywaydb.core.Flyway;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Общая обвязка интеграционных тестов: подключение к базе и миграции.
 *
 * <p>База поднимается СНАРУЖИ, скриптом {@code ops/ci/run-it.sh}, и её адрес
 * приходит системным свойством. Testcontainers здесь не используется
 * намеренно: изнутри контейнера сборки его библиотека договаривается о
 * версии Docker API 1.32, демон требует не ниже 1.40, и сообщение об этом
 * приходит от демона в адрес клиента — по нему естественно решить, что
 * сломан Docker. Час на это потрачен; скрипт делает то же самое без
 * посредника.
 *
 * <p>Без адреса базы тесты ПРОПУСКАЮТСЯ, а не падают. Запуск без базы —
 * это «не запускали», а не «сломано», и путать эти исходы в CI дороже, чем
 * кажется: красный прогон, который на самом деле просто не запускался,
 * учит не доверять красному вообще.
 */
abstract class FirebirdSupport {

    private static String jdbcUrl;
    private static String user;
    private static String password;
    private static boolean migrated;

    /**
     * Подключиться и применить миграции. Вызывается из {@code @BeforeAll}
     * каждого класса; сами миграции применяются один раз на прогон — Flyway
     * идемпотентен, но лишний круг к базе на каждом классе ни к чему.
     */
    static void prepareDatabase() throws Exception {
        jdbcUrl = System.getProperty("sonder.it.jdbcUrl");
        user = System.getProperty("sonder.it.user", "sysdba");
        password = System.getProperty("sonder.it.password", "masterkey");

        assumeTrue(jdbcUrl != null && !jdbcUrl.isEmpty(),
                "нет sonder.it.jdbcUrl — запускать через ./sonder java-it");

        awaitConnectable();

        if (!migrated) {
            Flyway.configure()
                    .dataSource(jdbcUrl, user, password)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            migrated = true;
        }
    }

    private static void awaitConnectable() throws Exception {
        SQLException last = null;
        for (int i = 0; i < 30; i++) {
            try (Connection c = connect()) {
                return;
            } catch (SQLException e) {
                last = e;
                Thread.sleep(1000);
            }
        }
        throw new IllegalStateException("Firebird не отвечает: " + jdbcUrl, last);
    }

    static Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, user, password);
    }

    /**
     * Все таблицы схемы, от ссылающихся к тем, на кого ссылаются.
     *
     * <p>Список ОДИН на все тесты, и это не удобство. Раньше каждый класс
     * перечислял таблицы сам, списки разошлись, и стоило одному тесту
     * начать писать комментарии, как соседний перестал уметь удалить пост:
     * его список про комментарии не знал. Тот же класс дефекта, что и
     * разошедшийся с {@code .gitattributes} перечень файлов, и ловится он
     * так же плохо — не там, где ошиблись.
     *
     * <p>Что список ничего не забыл, проверяет
     * {@code SchemaIT.wipeCoversEverySchemaTable}: таблица, добавленная
     * миграцией и не добавленная сюда, красит сборку.
     */
    static final List<String> TABLES = Collections.unmodifiableList(Arrays.asList(
            "feed_entries", "outbox", "sessions", "follows", "comments", "posts", "users"));

    /** Опустошить базу в уже открытом соединении. Порядок обходит ключи. */
    static void wipe(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            for (String table : TABLES) {
                st.executeUpdate("DELETE FROM " + table);
            }
        }
    }

    /** То же, но со своим соединением. */
    static void wipe() throws SQLException {
        try (Connection c = connect()) {
            wipe(c);
        }
    }
}
