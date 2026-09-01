package sonder.store;

import org.flywaydb.core.Flyway;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

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
}
