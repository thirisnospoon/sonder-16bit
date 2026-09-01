package sonder.store;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Схема против настоящего Firebird.
 *
 * <p>Проверяется не «миграции применились» — это слишком слабое
 * утверждение. Проверяется, что схема запрещает ровно то, что запрещает
 * контракт, и разрешает ровно то, что он разрешает. Разница видна на
 * границе: имя из шестидесяти КИРИЛЛИЧЕСКИХ букв обязано пройти, из
 * шестидесяти одной — нет. Это сто двадцать байт против ста двадцати двух,
 * и схема, считающая байты, приняла бы оба.
 *
 * <p>Ровно на этой границе ядро уже ошибалось (ADR-0014), поэтому проверять
 * её здесь — не перестраховка, а память.
 *
 * <p>Имя класса кончается на IT, а не на Test: интеграционные тесты требуют
 * поднятой базы и потому запускаются отдельной целью ({@code ./sonder
 * java-it}), а не в каждой сборке. Базу поднимает скрипт, адрес приходит
 * системным свойством.
 */
class SchemaIT {

    private static String jdbcUrl;
    private static String user;
    private static String password;

    /**
     * База поднимается снаружи, скриптом {@code ops/ci/run-it.sh}, и её
     * адрес приходит системным свойством. Без свойства тесты пропускаются, а
     * не падают: запуск без базы — это «не запускали», а не «сломано», и
     * путать эти два исхода в CI дороже, чем кажется.
     */
    @BeforeAll
    static void connectToDatabase() throws Exception {
        jdbcUrl = System.getProperty("sonder.it.jdbcUrl");
        user = System.getProperty("sonder.it.user", "sysdba");
        password = System.getProperty("sonder.it.password", "masterkey");

        assumeTrue(jdbcUrl != null && !jdbcUrl.isEmpty(),
                "нет sonder.it.jdbcUrl — запускать через ./sonder java-it");

        awaitConnectable();

        Flyway.configure()
                .dataSource(jdbcUrl, user, password)
                .locations("classpath:db/migration")
                .load()
                .migrate();
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

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, user, password);
    }

    private static void insertUser(String id, String nick, String displayName,
                                   String role, String status) throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO users (id, nick, display_name, role, status,"
                             + " password_hash, version, created_at)"
                             + " VALUES (?, ?, ?, ?, ?, 'x', 0, ?)")) {
            ps.setString(1, id);
            ps.setString(2, nick);
            ps.setString(3, displayName);
            ps.setString(4, role);
            ps.setString(5, status);
            ps.setTimestamp(6, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }

    private static String repeat(String s, int times) {
        StringBuilder sb = new StringBuilder(s.length() * times);
        for (int i = 0; i < times; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    @Test
    @DisplayName("миграции применились и таблицы на месте")
    void tablesExist() throws Exception {
        try (Connection c = connect();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT TRIM(RDB$RELATION_NAME) FROM RDB$RELATIONS"
                             + " WHERE RDB$SYSTEM_FLAG = 0")) {
            java.util.Set<String> names = new java.util.HashSet<>();
            while (rs.next()) {
                names.add(rs.getString(1).toUpperCase());
            }
            for (String want : new String[]{"USERS", "POSTS", "COMMENTS",
                    "FOLLOWS", "SESSIONS", "OUTBOX"}) {
                assertTrue(names.contains(want),
                        "нет таблицы " + want + ", есть: " + names);
            }
        }
    }

    /**
     * Граница длины считается в СИМВОЛАХ. Шестьдесят кириллических букв —
     * это сто двадцать байт, и схема, считающая байты, такое имя отвергла
     * бы, хотя контракт его разрешает.
     */
    @Test
    @DisplayName("имя из шестидесяти кириллических символов принимается")
    void sixtyCyrillicCharactersFit() throws Exception {
        String name = repeat("я", 60);
        assertEquals(60, name.length());
        insertUser("u-cyr", "cyr", name, "USER", "ACTIVE");

        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT display_name FROM users WHERE id = ?")) {
            ps.setString(1, "u-cyr");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "строка не нашлась");
                assertEquals(name, rs.getString(1),
                        "имя вернулось не тем, чем было записано");
            }
        }
    }

    @Test
    @DisplayName("символом больше предела — отказ базы")
    void sixtyOneCharactersRejected() {
        assertThrows(SQLException.class,
                () -> insertUser("u-long", "long", repeat("я", 61),
                        "USER", "ACTIVE"),
                "база приняла имя длиннее контракта — ядро и база разрешают разное");
    }

    @Test
    @DisplayName("роль вне перечисления контракта не сохраняется")
    void unknownRoleRejected() {
        assertThrows(SQLException.class,
                () -> insertUser("u-king", "king", "Король", "КОРОЛЬ", "ACTIVE"),
                "роль «КОРОЛЬ» — не отказанное действие, а испорченная строка");
    }

    @Test
    @DisplayName("подписка на себя не представима")
    void selfFollowRejected() throws Exception {
        insertUser("u-self", "self", "Сам", "USER", "ACTIVE");
        assertThrows(SQLException.class, () -> {
            try (Connection c = connect();
                 PreparedStatement ps = c.prepareStatement(
                         "INSERT INTO follows (follower_id, target_id, created_at)"
                                 + " VALUES (?, ?, ?)")) {
                ps.setString(1, "u-self");
                ps.setString(2, "u-self");
                ps.setTimestamp(3, Timestamp.from(Instant.now()));
                ps.executeUpdate();
            }
        });
    }

    /**
     * Событие пишется в ту же транзакцию, что и изменение агрегата. Здесь
     * проверяется меньшее, но необходимое: строка outbox кладётся и
     * читается, а идентификатор выдаётся базой.
     */
    @Test
    @DisplayName("строка outbox пишется и получает идентификатор")
    void outboxRoundTrip() throws Exception {
        insertUser("u-ob", "ob", "Автор", "USER", "ACTIVE");

        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO outbox (aggregate_id, event_type, payload,"
                            + " trace_id, created_at, attempts)"
                            + " VALUES (?, ?, ?, ?, ?, 0)")) {
                ps.setString(1, "p-1001");
                ps.setString(2, "post.created");
                ps.setString(3, "{\"authorId\":\"u-ob\"}");
                ps.setString(4, "t-1");
                ps.setTimestamp(5, Timestamp.from(Instant.now()));
                ps.executeUpdate();
            }
            c.commit();
        }

        try (Connection c = connect();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, event_type, payload FROM outbox"
                             + " WHERE published_at IS NULL ORDER BY id")) {
            assertTrue(rs.next(), "неопубликованная строка не нашлась");
            assertTrue(rs.getLong(1) > 0, "идентификатор не выдан базой");
            assertEquals("post.created", rs.getString(2));
            assertNotNull(rs.getString(3));
        }
    }
}
