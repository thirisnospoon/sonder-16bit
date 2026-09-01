package sonder.shell.outbox;

/** Строка очереди, взятая в работу. */
public final class OutboxRecord {

    private final long id;
    private final String aggregateId;
    private final String type;
    private final String payload;
    private final String traceId;
    private final int attempts;

    public OutboxRecord(long id, String aggregateId, String type,
                        String payload, String traceId, int attempts) {
        this.id = id;
        this.aggregateId = aggregateId;
        this.type = type;
        this.payload = payload;
        this.traceId = traceId;
        this.attempts = attempts;
    }

    public long getId() {
        return id;
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

    public int getAttempts() {
        return attempts;
    }

    @Override
    public String toString() {
        return "OutboxRecord{" + id + " " + type + " попыток=" + attempts + "}";
    }
}
