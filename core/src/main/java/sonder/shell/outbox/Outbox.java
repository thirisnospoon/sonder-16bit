package sonder.shell.outbox;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Очередь исходящих событий.
 *
 * <p><b>Каждый метод принимает {@link Connection}, а не источник данных, и
 * это главное решение здесь.</b> Источник данных позволил бы открыть
 * собственное соединение — то есть собственную транзакцию, — и запись
 * события разъехалась бы с изменением агрегата. Между ними возникло бы
 * окно, в котором система считает действие совершённым, а мир о нём не
 * знает. Соединение параметром делает это невозможным механически, а не по
 * договорённости.
 *
 * <p><b>Сырой SQL, а не ORM.</b> Так решено в
 * <a href="../../../../../docs/adr/0013-firebird-and-orm.md">ADR-0013</a>:
 * дренаж очереди — операция над множеством строк с блокировками, и объектов
 * с идентичностью здесь нет. ORM хорош там, где есть объект, и мешает там,
 * где есть множество.
 *
 * <p><b>Источник правды — эта таблица, а не уведомление.</b> Событие
 * Firebird {@code POST_EVENT} недолговечно и полезной нагрузки не несёт:
 * оно только дверной звонок. Потерянное уведомление означает задержку,
 * потерянная строка означала бы потерянное событие.
 */
public final class Outbox {

    /**
     * Сколько строк брать за раз. Не константа ради красоты: слишком
     * большая пачка держит блокировки дольше, чем нужно, и второй
     * потребитель простаивает; слишком маленькая упирается в накладные
     * расходы на круг к базе. Уточняется бенчмарком фазы 10.
     */
    public static final int DEFAULT_BATCH = 32;

    private Outbox() {
    }

    /**
     * Дописать событие в очередь В ТЕКУЩЕЙ ТРАНЗАКЦИИ вызывающего.
     *
     * <p>Транзакцией управляет вызывающий: он же меняет агрегат, и коммит
     * обязан быть общим.
     */
    public static void append(Connection c, OutboxEvent event) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO outbox (aggregate_id, event_type, payload, trace_id,"
                        + " created_at, attempts) VALUES (?, ?, ?, ?, ?, 0)")) {
            ps.setString(1, event.getAggregateId());
            ps.setString(2, event.getType());
            ps.setString(3, event.getPayload());
            ps.setString(4, event.getTraceId());
            ps.setTimestamp(5, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }

    /**
     * Взять пачку неопубликованных строк, заблокировав их за собой.
     *
     * <p>{@code SKIP LOCKED} здесь — не оптимизация, а условие
     * работоспособности: без него второй потребитель встал бы на строке,
     * которую уже взял первый, и параллельный дренаж превратился бы в
     * последовательный, притворяясь параллельным.
     *
     * <p>Порядок по {@code id}, а не по времени создания: часы могут
     * сдвинуться, а идентичность — нет.
     */
    public static List<OutboxRecord> claim(Connection c, int batch) throws SQLException {
        List<OutboxRecord> out = new ArrayList<>();
        String sql = "SELECT id, aggregate_id, event_type, payload, trace_id, attempts"
                + " FROM outbox WHERE published_at IS NULL"
                + " ORDER BY id ROWS " + batch
                + " FOR UPDATE WITH LOCK SKIP LOCKED";
        try (PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new OutboxRecord(
                        rs.getLong(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getInt(6)));
            }
        }
        return out;
    }

    /** Событие опубликовано: больше его не выдавать. */
    public static void markPublished(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE outbox SET published_at = ? WHERE id = ?")) {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    /**
     * Публикация не удалась. Счётчик попыток растёт, строка остаётся
     * неопубликованной.
     *
     * <p>Считать попытки нужно затем, чтобы ядовитое событие было ВИДНО, а
     * не крутилось в очереди вечно, тихо съедая пропускную способность.
     * Решение, что делать с таким событием, принимает человек по метрике, а
     * не код по порогу: автоматическое отбрасывание теряет данные молча.
     */
    public static void recordFailure(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE outbox SET attempts = attempts + 1 WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    /** Сколько строк ждёт публикации. Метрика, а не логика. */
    public static long pendingCount(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM outbox WHERE published_at IS NULL")) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }
}
