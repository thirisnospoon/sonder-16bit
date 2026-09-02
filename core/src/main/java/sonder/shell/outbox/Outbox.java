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
     *
     * <p>Строки, у которых отсрочка повтора ещё не истекла, не выдаются.
     * {@code NULL} в {@code next_attempt_at} значит «выдавать сейчас»: так
     * выглядит и всякая новая строка, и всякая, записанная до появления
     * отсрочки.
     *
     * <p>Время параметром, а не {@code Instant.now()} внутри: очередь с
     * собственными часами проверяется только ожиданием, а ожидание в
     * тесте — это не проверка, а надежда.
     */
    public static List<OutboxRecord> claim(Connection c, int batch, Instant now)
            throws SQLException {
        List<OutboxRecord> out = new ArrayList<>();
        String sql = "SELECT id, aggregate_id, event_type, payload, trace_id, attempts"
                + " FROM outbox WHERE published_at IS NULL"
                + " AND (next_attempt_at IS NULL OR next_attempt_at <= ?)"
                + " ORDER BY id ROWS " + batch
                + " FOR UPDATE WITH LOCK SKIP LOCKED";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(now));
            ResultSet rs = ps.executeQuery();
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
    public static void markPublished(Connection c, long id, Instant now)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE outbox SET published_at = ? WHERE id = ?")) {
            ps.setTimestamp(1, Timestamp.from(now));
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    /**
     * Публикация не удалась. Счётчик попыток растёт, строка остаётся
     * неопубликованной и не выдаётся до {@code notBefore}.
     *
     * <p>Считать попытки нужно затем, чтобы ядовитое событие было ВИДНО, а
     * не крутилось в очереди вечно, тихо съедая пропускную способность.
     * Решение, что делать с таким событием, принимает человек по метрике, а
     * не код по порогу: автоматическое отбрасывание теряет данные молча.
     *
     * <p>Насколько отложить — решает не очередь, а {@link Backoff} в
     * дренажёре: здесь только запись срока. Политика в примитиве хранения
     * означала бы, что её нельзя ни подменить, ни проверить отдельно.
     */
    public static void recordFailure(Connection c, long id, Instant notBefore)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE outbox SET attempts = attempts + 1, next_attempt_at = ?"
                        + " WHERE id = ?")) {
            ps.setTimestamp(1, Timestamp.from(notBefore));
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    /**
     * Застрявшие: неопубликованные, у которых накопилось попыток не
     * меньше порога.
     *
     * <p><b>Отбрасывать их автоматически нельзя</b> — так решено там же,
     * где заведён счётчик попыток: порог, по которому код молча выкидывает
     * данные, теряет их тихо. Решение принимает человек, а чтобы он мог
     * его принять, застрявшее должно быть ВИДНО. Этот запрос и есть та
     * видимость.
     *
     * <p>Возвращается пара «сколько» и «самый старый», потому что одного
     * числа мало: сто застрявших строк из-за одного упавшего соседа и сто
     * разных ядовитых событий — разные беды, и различает их возраст
     * самой старой.
     */
    public static Stuck stuck(Connection c, int minAttempts) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*), MIN(id) FROM outbox"
                        + " WHERE published_at IS NULL AND attempts >= ?")) {
            ps.setInt(1, minAttempts);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new Stuck(0, -1);
                }
                long count = rs.getLong(1);
                long oldest = rs.getLong(2);
                return new Stuck(count, rs.wasNull() ? -1 : oldest);
            }
        }
    }

    /** Сколько застряло и какая строка самая старая. */
    public static final class Stuck {
        private final long count;
        private final long oldestId;

        Stuck(long count, long oldestId) {
            this.count = count;
            this.oldestId = oldestId;
        }

        public long getCount() {
            return count;
        }

        /** Идентификатор самой старой застрявшей строки; -1, если таких нет. */
        public long getOldestId() {
            return oldestId;
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
