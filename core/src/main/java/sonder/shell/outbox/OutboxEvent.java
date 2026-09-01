package sonder.shell.outbox;

import java.util.Objects;

/**
 * Событие, которое решило породить ядро, по дороге в очередь.
 *
 * <p>Оболочка событий не придумывает: и тип, и идентификатор агрегата, и
 * поля приходят из решения NODE-7. Здесь они только доносятся до таблицы.
 */
public final class OutboxEvent {

    private final String aggregateId;
    private final String type;
    private final String payload;
    private final String traceId;

    public OutboxEvent(String aggregateId, String type, String payload, String traceId) {
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId");
        this.type = Objects.requireNonNull(type, "type");
        this.payload = Objects.requireNonNull(payload, "payload");
        this.traceId = traceId;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getType() {
        return type;
    }

    public String getPayload() {
        return payload;
    }

    public String getTraceId() {
        return traceId;
    }

    @Override
    public String toString() {
        return "OutboxEvent{" + type + " " + aggregateId + "}";
    }
}
