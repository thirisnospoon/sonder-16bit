package sonder.gateway;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Здоровье ноды в том месте, которое ищут первым: {@code /actuator/health}.
 *
 * <p>Метрики NODE-7 существовали, но никуда не уходили: их отдавала
 * только команда {@code ping}, которую никто не звал. Проверенный код, до
 * которого не доходит исполнение, работой не считается — и наблюдаемость
 * тут не исключение, а самый частый случай.
 *
 * <p><b>Протухший снимок — это НЕ ЗДОРОВЬЕ.</b> Замолчавшая нода
 * оставляет последний ответ нетронутым: он полон, правдоподобен и без
 * времени снятия неотличим от свежего. Показать его как «UP» значило бы
 * соврать ровно тогда, когда правда нужнее всего.
 *
 * <p>Линии нет вовсе — раздела нет вовсе: этот показатель появляется
 * вместе с транспортом. Так система работает на стендах без эмулятора, и
 * красная лампа, горящая там по устройству, погасла бы в глазах людей за
 * неделю. Неизвестным ({@code UNKNOWN}) здоровье бывает только до первого
 * опроса — линия есть, ответа ещё нет.
 */
public final class NodeHealth implements HealthIndicator {

    private final NodeProbe probe;
    private final Supplier<Instant> clock;

    public NodeHealth(NodeProbe probe) {
        this(probe, Instant::now);
    }

    /**
     * Часы отдельно от показателя.
     *
     * <p>Протухание — главное, что здесь происходит, и проверить его,
     * дожидаясь настоящих сорока пяти секунд, нельзя: такую проверку
     * выключат первой. Часы снаружи стоят одного поля.
     */
    public NodeHealth(NodeProbe probe, Supplier<Instant> clock) {
        this.probe = probe;
        this.clock = clock;
    }

    @Override
    public Health health() {
        Instant now = clock.get();
        NodeProbe.Snapshot last = probe.getLast();

        if (last == null) {
            return Health.unknown()
                    .withDetail("нода", "ещё не опрошена")
                    .build();
        }

        if (probe.isStale(last, now)) {
            return Health.down()
                    .withDetail("нода", "снимок протух")
                    .withDetail("снят", last.getAt().toString())
                    .withDetail("протухает через", probe.getStaleAfter().toString())
                    .build();
        }

        if (!last.isOk()) {
            return Health.down()
                    .withDetail("нода", "не ответила")
                    .withDetail("причина", last.getFailure())
                    .withDetail("снято", last.getAt().toString())
                    .build();
        }

        Health.Builder up = Health.up()
                .withDetail("снято", last.getAt().toString());
        for (Map.Entry<String, Object> e
                : NodeProbe.describe(last.getMetrics()).entrySet()) {
            up = up.withDetail(e.getKey(), e.getValue());
        }
        return up.build();
    }
}
