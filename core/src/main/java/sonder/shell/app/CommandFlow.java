package sonder.shell.app;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Полный ход команды: загрузить состояние, спросить ядро, записать решение.
 *
 * <p><b>ВЫЗОВ ЯДРА ИДЁТ ВНЕ ТРАНЗАКЦИИ, и это главное решение здесь.</b>
 *
 * <p>Соблазн держать одну транзакцию на весь ход велик: тогда состояние, по
 * которому решало ядро, гарантированно то же, что и при записи. Но ядро
 * живёт за последовательной линией, и круговой обмен с ним стоит 13 мс по
 * измерению S2. Транзакция, открытая на всё это время, умножается на
 * шестнадцать одновременных команд — и база начинает держать снимки и
 * блокировки ради ожидания сети. Такая система работает на демонстрации и
 * ложится под нагрузкой, а причина выглядит как «медленная база».
 *
 * <p>Поэтому ход разбит на три фазы:
 *
 * <ol>
 *   <li><b>Чтение.</b> Короткая транзакция, из неё выходит состояние
 *       вместе с версиями агрегатов.</li>
 *   <li><b>Решение.</b> Соединения нет вовсе. Ядро отвечает по тому
 *       состоянию, что ему прислали.</li>
 *   <li><b>Запись.</b> Новая транзакция, запись с проверкой версии. Если
 *       версия сдвинулась — решение принято по устаревшему состоянию, и
 *       весь ход повторяется с начала.</li>
 * </ol>
 *
 * <p>Именно ради третьей фазы существует оптимистическая блокировка.
 * Промежуток между чтением и записью здесь не дефект, который терпят, а
 * осознанный размен: вместо того чтобы удерживать состояние, мы замечаем,
 * что оно изменилось, и переигрываем.
 *
 * <p>Повтор начинается с ЧТЕНИЯ, а не с записи. Записать то же решение
 * ещё раз значило бы применить к новому состоянию решение, принятое по
 * старому, — то есть ровно то, чего проверка версии не даёт сделать
 * случайно и чего нельзя делать намеренно.
 */
public final class CommandFlow {

    @FunctionalInterface
    public interface Read<S> {
        S read(Connection c) throws SQLException;
    }

    /**
     * Решение. Бросает {@link Exception}, а не {@link SQLException}: за
     * этой границей вызов уходит по сети к ядру, и её отказы к базе
     * отношения не имеют.
     */
    @FunctionalInterface
    public interface Decide<S, D> {
        D decide(S state) throws Exception;
    }

    /**
     * Запись. Возвращает результат хода, а не void: что именно узнает
     * вызывающий — принято ли, сколько событий записано, — решает именно
     * эта фаза. Решение ядра ей для этого мало: отказ и принятие пишутся
     * по-разному, и знание об этом живёт здесь.
     */
    @FunctionalInterface
    public interface Write<S, D, R> {
        R write(Connection c, S state, D decision)
                throws SQLException, VersionConflict;
    }

    private final ConnectionSource connections;
    private final int maxAttempts;

    public CommandFlow(ConnectionSource connections) {
        this(connections, CommandRunner.DEFAULT_ATTEMPTS);
    }

    public CommandFlow(ConnectionSource connections, int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException(
                    "попыток должно быть хотя бы одна, а не " + maxAttempts);
        }
        this.connections = connections;
        this.maxAttempts = maxAttempts;
    }

    public <S, D, R> R run(Read<S> read, Decide<S, D> decide,
                           Write<S, D, R> write) throws Exception {
        VersionConflict last = null;

        for (int i = 0; i < maxAttempts; i++) {
            // Фаза 1: чтение. Транзакция закрывается сразу — держать её
            // через сетевой вызов и есть та ошибка, ради которой всё
            // разбито на фазы.
            S state;
            try (Connection c = connections.open()) {
                c.setAutoCommit(false);
                try {
                    state = read.read(c);
                    c.commit();
                } catch (SQLException | RuntimeException e) {
                    c.rollback();
                    throw e;
                }
            }

            // Фаза 2: решение. Соединения нет.
            D decision = decide.decide(state);

            // Фаза 3: запись под проверкой версии.
            try (Connection c = connections.open()) {
                c.setAutoCommit(false);
                try {
                    R result = write.write(c, state, decision);
                    c.commit();
                    return result;
                } catch (VersionConflict conflict) {
                    c.rollback();
                    last = conflict;
                } catch (SQLException | RuntimeException e) {
                    c.rollback();
                    throw e;
                }
            }
        }

        throw last;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }
}
