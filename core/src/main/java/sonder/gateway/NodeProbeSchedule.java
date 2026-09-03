package sonder.gateway;

import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;

/**
 * Часы опроса ноды.
 *
 * <p>Отдельным классом, а не методом в {@link NodeProbe}: сам опрос
 * обязан оставаться вызываемым руками — из теста, из ручки, из чего
 * угодно, — а расписание ему для этого не нужно. Смешав их, пришлось бы
 * поднимать контекст всякий раз, когда нужен один опрос.
 *
 * <p>Интервал считается от КОНЦА прошлого опроса. Нода за линией отвечает
 * не мгновенно, и {@code fixedRate} копил бы опросы друг на друга,
 * занимая каналы, которые нужны командам.
 */
public final class NodeProbeSchedule {

    private final NodeProbe probe;

    public NodeProbeSchedule(NodeProbe probe) {
        this.probe = probe;
    }

    @Scheduled(
            fixedDelayString = "${sonder.decider.line.probe-ms:15000}",
            initialDelayString = "${sonder.decider.line.probe-initial-ms:3000}")
    public void tick() {
        // Отказ опроса записывается снимком, а не улетает исключением:
        // упавший планировщик перестал бы обновлять снимок вовсе, и тот
        // протух бы — то есть здоровье стало бы DOWN по причине,
        // не имеющей отношения к ноде.
        probe.probe(Instant.now());
    }
}
