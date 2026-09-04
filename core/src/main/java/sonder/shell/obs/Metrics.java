package sonder.shell.obs;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import sonder.contract.decider.PingResponse;
import sonder.gateway.NodeProbe;
import sonder.shell.outbox.Outbox;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToLongFunction;

/**
 * Метрики, отвечающие на вопросы ИМЕННО ЭТОЙ системы.
 *
 * <p>Стандартных метрик Spring — задержки запросов, пул соединений,
 * память — актуатор даёт и без нас, и они полезны. Но ни одна из них не
 * отвечает на три вопроса, которые эта система задаёт своим устройством:
 *
 * <ol>
 *   <li><b>близко ли ядро к пределу арены.</b> У NODE-7 два килобайта на
 *   канал, и пик занятости — единственный признак приближения к
 *   переполнению. Узнать о нём по отказу означает узнать поздно;</li>
 *   <li><b>отстаёт ли проекция.</b> Очередь outbox — это расстояние
 *   между «система приняла» и «читатель увидит»;</li>
 *   <li><b>жива ли линия.</b> Ошибки линии и байты в обе стороны
 *   отличают «команд нет» от «команды не проходят» — снаружи это
 *   выглядит одинаково.</li>
 * </ol>
 *
 * <p>ЗНАЧЕНИЯ БЕРУТСЯ ИЗ УЖЕ ИЗМЕРЕННОГО, а не измеряются заново.
 * Счётчики ноды приходят ответом на опрос — тот же, что питает
 * показатель здоровья; глубина очереди читается тем же запросом, что и в
 * замере. Второй способ добыть то же число означал бы два числа,
 * расходящихся между собой.
 *
 * <p>СЧЁТЧИК И ДАТЧИК РАЗЛИЧАЮТСЯ, и различие это не формальность.
 * Накопительные величины ноды — обслужено, отвергнуто, байты, ошибки —
 * заводятся счётчиками: имя получает суффикс {@code _total}, а
 * {@code rate()} по нему считается правильно и переживает перезапуск
 * ноды. Первая редакция объявила их датчиками, и Grafana сказала об этом
 * прямо: «metric might not be a counter, name does not end in _total».
 *
 * <p>ОТСУТСТВИЕ ОТВЕТА — ПРОПУСК, А НЕ ЧИСЛО. Датчики отдают
 * {@code NaN}, когда нода не отвечала: Prometheus такую точку не
 * записывает вовсе, и на графике выходит разрыв. Первая редакция ставила
 * на это место {@code -1} — выдуманное значение, которое читателю
 * приходится помнить, и которое на оси в байтах рисуется провалом ниже
 * нуля. Ноль был бы ещё хуже: он читается как «ноль команд, ноль
 * ошибок», то есть как исправная и незанятая нода.
 *
 * <p>ОПРОС БАЗЫ ИДЁТ ПО ЗАПРОСУ СБОРЩИКА, и это осознанный компромисс:
 * счёт неопубликованных — это {@code COUNT(*)} по индексу, а сборщик
 * приходит раз в несколько секунд. Кэшировать значение отдельным
 * планировщиком значило бы завести третий срок жизни данных (сборщик,
 * кэш, база) и объяснять потом, почему на графике полка.
 */
@Configuration
public class Metrics {

    private static final Logger log = LoggerFactory.getLogger(Metrics.class);

    /**
     * Последнее удавшееся чтение очереди.
     *
     * <p>Отказ базы НЕ обнуляет показатель: ноль на графике очереди
     * читается как «всё разобрано», то есть как хорошая новость. Отказ —
     * это отсутствие сведений, и выглядеть оно обязано прежним
     * значением плюс записью в логе.
     */
    private final AtomicLong очередь = new AtomicLong(0);

    public Metrics(MeterRegistry реестр,
                   DataSource база,
                   ObjectProvider<NodeProbe> опрос) {

        Gauge.builder("sonder.outbox.pending", () -> глубинаОчереди(база))
                .description("Событий ждёт публикации. Расстояние между "
                        + "«принято» и «читатель увидит»")
                .baseUnit("events")
                .register(реестр);

        // СВЕЖЕСТЬ ОПРОСА отдельным показателем.
        //
        // Счётчики ноды обновляются раз в пятнадцать секунд, и на
        // графике они ступеньки. Без этого показателя ступеньку не
        // отличить от остановки: полка на «обслужено» означает либо
        // «команд не было», либо «ответа от ноды нет уже минуту», и это
        // ровно та пара случаев, которую нельзя путать.
        Gauge.builder("sonder.node.probe.age", () -> {
            NodeProbe.Snapshot последний = снимок(опрос);
            if (последний == null) {
                return Double.NaN;
            }
            return (double) Duration
                    .between(последний.getAt(), Instant.now())
                    .getSeconds();
        })
                .description("Сколько секунд назад нода отвечала на опрос. "
                        + "Отличает ступеньку от остановки")
                .baseUnit("seconds")
                .register(реестр);

        // Датчики: величины со значением «сейчас», а не с приростом.
        датчик(реестр, опрос, "sonder.node.arena.peak", "bytes",
                "Пик занятости арены канала",
                PingResponse::getArenaHighMark);
        датчик(реестр, опрос, "sonder.node.arena.capacity", "bytes",
                "Ёмкость арены канала. Пик без неё не значит ничего",
                PingResponse::getArenaCapacity);
        // Без единицы: micrometer дописывает её к имени, и вышло бы
        // `sonder_node_fibers_in_use_fibers`. Байты и секунды в имени
        // полезны, «файберы в файберах» — нет.
        датчик(реестр, опрос, "sonder.node.fibers.in.use", null,
                "Файберов занято обработкой команд",
                PingResponse::getFibersInUse);

        // Счётчики: накапливаются с запуска ноды и обнуляются вместе с
        // ней. Выкладывать наружу ВСЁ, что вернул пинг, — соблазн: строк
        // больше, толку меньше, а дежурный ищет нужную среди похожих.
        счётчик(реестр, опрос, "sonder.node.commands.served",
                "Команд обслужено ядром",
                PingResponse::getCommandsServed);
        счётчик(реестр, опрос, "sonder.node.commands.refused",
                "Команд отвергнуто ядром: правило домена или нехватка арены",
                PingResponse::getCommandsRefused);
        счётчик(реестр, опрос, "sonder.node.commands.malformed",
                "Конвертов не разобралось: расхождение сторон или порча линии",
                PingResponse::getCommandsMalformed);
        счётчик(реестр, опрос, "sonder.node.line.errors",
                "Ошибок линии. Отличает «команд нет» от «команды не проходят»",
                PingResponse::getLineErrors);
        счётчик(реестр, опрос, "sonder.node.line.rx.bytes",
                "Байт принято ядром по линии",
                PingResponse::getRxBytes);
        счётчик(реестр, опрос, "sonder.node.line.tx.bytes",
                "Байт отправлено ядром по линии",
                PingResponse::getTxBytes);
        // Потерянная строка журнала не ломает ничего и потому невидима:
        // неполный журнал читается как полный. Метрика — единственный
        // способ отличить «ядро молчит» от «ядру нечем сказать».
        счётчик(реестр, опрос, "sonder.node.log.lines.lost",
                "Строк журнала не ушло в линию: кольцо было полно",
                PingResponse::getLogLinesLost);
    }

    private long глубинаОчереди(DataSource база) {
        try (Connection c = база.getConnection()) {
            long сейчас = Outbox.pendingCount(c);
            очередь.set(сейчас);
            return сейчас;
        } catch (Exception e) {
            // Молчать нельзя: показатель застыл, и знать об этом должен
            // тот, кто на него смотрит.
            log.warn("глубину очереди не прочитать: {}", e.toString());
            return очередь.get();
        }
    }

    /** Последний удавшийся ответ ноды, либо {@code null}. */
    private static NodeProbe.Snapshot снимок(ObjectProvider<NodeProbe> опрос) {
        NodeProbe probe = опрос.getIfAvailable();
        if (probe == null) {
            // Ноды может не быть вовсе: оболочка умеет работать и с
            // ядром по HTTP, и без ядра совсем.
            return null;
        }
        NodeProbe.Snapshot последний = probe.getLast();
        return последний != null && последний.isOk() ? последний : null;
    }

    private static void датчик(MeterRegistry реестр,
                               ObjectProvider<NodeProbe> опрос,
                               String имя, String единица, String описание,
                               ToLongFunction<PingResponse> поле) {
        Gauge.builder(имя, () -> {
            NodeProbe.Snapshot последний = снимок(опрос);
            return последний == null
                    ? Double.NaN
                    : (double) поле.applyAsLong(последний.getMetrics());
        }).description(описание).baseUnit(единица).register(реестр);
    }

    /**
     * Накопительный показатель ноды.
     *
     * <p>При отсутствии ответа отдаёт ПОСЛЕДНЕЕ ИЗВЕСТНОЕ значение, а не
     * пропуск: счётчик с дырой посередине ломает {@code rate()} на всём
     * окне, а «ответа нет» видно по возрасту опроса. Ноль тут означал бы
     * обнуление, то есть перезапуск ноды, — и {@code rate()} честно
     * нарисовал бы всплеск, которого не было.
     */
    private static void счётчик(MeterRegistry реестр,
                                ObjectProvider<NodeProbe> опрос,
                                String имя, String описание,
                                ToLongFunction<PingResponse> поле) {
        AtomicLong последнее = new AtomicLong(0);
        FunctionCounter.builder(имя, последнее, держатель -> {
            NodeProbe.Snapshot текущий = снимок(опрос);
            if (текущий != null) {
                держатель.set(поле.applyAsLong(текущий.getMetrics()));
            }
            return держатель.get();
        }).description(описание).register(реестр);
    }
}
