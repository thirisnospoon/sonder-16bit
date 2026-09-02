package sonder.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sonder.shell.outbox.Outbox;
import sonder.shell.outbox.OutboxDrainer;
import sonder.shell.outbox.OutboxPump;
import sonder.shell.outbox.OutboxSchedule;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Тик дренажа при отказе базы.
 *
 * <p>Базы здесь нет и не нужно: проверяется ровно одно свойство, и оно не
 * про базу, а про планировщик. <b>Планировщик Spring перестаёт повторять
 * задачу, из которой вылетело исключение.</b> Один отказ базы навсегда
 * остановил бы дренаж, и выглядело бы это как «события просто перестали
 * доходить» — без единой записи о том, когда и почему.
 *
 * <p>Отказ подаётся самый настоящий: источник соединений, который их не
 * даёт. Подменять сам насос нечем и незачем — путь от тика до отказа
 * проходится целиком.
 */
class OutboxTickerTest {

    /** Источник соединений, которых нет: база лежит. */
    private static OutboxSchedule.Ticker tickerOverDeadDatabase() {
        OutboxDrainer drainer = new OutboxDrainer(
                () -> {
                    throw new SQLException("база не отвечает");
                },
                (c, record) -> { });
        return new OutboxSchedule.Ticker(
                new OutboxPump(drainer, Outbox.DEFAULT_BATCH));
    }

    @Test
    @DisplayName("отказ базы не выпускается наружу и не гасит расписание")
    void failureDoesNotEscape() {
        OutboxSchedule.Ticker ticker = tickerOverDeadDatabase();

        assertDoesNotThrow(ticker::tick,
                "исключение из тика остановило бы расписание навсегда");
        assertDoesNotThrow(ticker::tick,
                "второй тик не состоялся: расписание уже было бы мёртвым");

        assertEquals(2, ticker.getTicks(), "тики не посчитаны");
        assertEquals(2, ticker.getFailures(),
                "отказ не посчитан: молчаливо вставший дренаж неотличим "
                        + "от пустой очереди");
    }
}
