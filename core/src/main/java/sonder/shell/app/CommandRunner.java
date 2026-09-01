package sonder.shell.app;

import sonder.contract.ErrorCode;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Прогон команды с переигрыванием при конфликте версий.
 *
 * <p>Каждая попытка — ОТДЕЛЬНАЯ ТРАНЗАКЦИЯ и отдельная загрузка состояния.
 * Это не деталь реализации, а весь смысл: повтор внутри той же транзакции
 * перечитал бы тот же снимок и пришёл бы к тому же решению, конфликтуя
 * бесконечно. Переигрывание означает «спросить ядро заново, глядя на
 * новое состояние», а не «попробовать записать ещё раз».
 *
 * <p>Число попыток конечно. Бесконечный повтор под нагрузкой превращается
 * в живую блокировку: команда крутится, ресурсы тратятся, а снаружи это
 * выглядит как медленная система. Исчерпание попыток — честный отказ с
 * {@link ErrorCode#STATE_VERSION_CONFLICT}, и он повторяем: контракт
 * помечает эту категорию как retryable, и решение повторить принимает
 * клиент, а не мы за него до бесконечности.
 *
 * <p>Ссылка на {@code STATE_VERSION_CONFLICT} здесь законна: контракт
 * помечает его {@code decided_by: shell}. Оболочка решает его сама,
 * потому что ядро о версиях в хранилище ничего не знает и знать не может.
 */
public final class CommandRunner {

    /**
     * Сколько раз переигрывать. Три — не магия, а рассуждение о том, что
     * конфликт означает: кто-то успел записать раньше. Два подряд
     * конфликта на одном агрегате — уже редкость, три — почти наверняка
     * не случайность, а горячая точка, и об этом лучше узнать по отказу,
     * чем по загрузке процессора.
     */
    public static final int DEFAULT_ATTEMPTS = 3;

    /** Одна попытка: загрузить, решить, записать. */
    @FunctionalInterface
    public interface Attempt<T> {
        T run(Connection c) throws SQLException, VersionConflict;
    }

    private final ConnectionSource connections;
    private final int maxAttempts;

    public CommandRunner(ConnectionSource connections) {
        this(connections, DEFAULT_ATTEMPTS);
    }

    public CommandRunner(ConnectionSource connections, int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException(
                    "попыток должно быть хотя бы одна, а не " + maxAttempts);
        }
        this.connections = connections;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Прогнать команду, переигрывая при конфликте версий.
     *
     * @throws VersionConflict попытки исчерпаны; отказ повторяем клиентом
     */
    public <T> T run(Attempt<T> attempt) throws SQLException, VersionConflict {
        VersionConflict last = null;
        for (int i = 0; i < maxAttempts; i++) {
            try (Connection c = connections.open()) {
                c.setAutoCommit(false);
                try {
                    T result = attempt.run(c);
                    c.commit();
                    return result;
                } catch (VersionConflict conflict) {
                    // Откат обязателен: часть изменений могла лечь до того,
                    // как обнаружился конфликт, и унести их надо целиком.
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

    /** Сколько попыток делает этот прогонщик. Для метрик и тестов. */
    public int getMaxAttempts() {
        return maxAttempts;
    }

    /**
     * Код, которым исчерпание попыток превращается в ответ клиенту.
     *
     * <p>Метод существует затем, чтобы связь «конфликт версий →
     * STATE_VERSION_CONFLICT» была в одном месте и была видна, а не
     * рассыпалась по адаптерам.
     */
    public static ErrorCode conflictCode() {
        return ErrorCode.STATE_VERSION_CONFLICT;
    }
}
