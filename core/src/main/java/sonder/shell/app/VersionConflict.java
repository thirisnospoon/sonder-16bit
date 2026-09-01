package sonder.shell.app;

/**
 * Состояние изменилось между загрузкой и сохранением.
 *
 * <p>Это НЕ ошибка. Ядро приняло решение, глядя на версию N, а к моменту
 * записи агрегат был уже версии N+1 — значит решение принято по неверному
 * состоянию, и единственный честный ответ: переиграть команду целиком, с
 * новой загрузкой и новым вызовом ядра.
 *
 * <p>Дописать изменение поверх чужого было бы потерей данных, о которой
 * никто не узнает. Именно поэтому исключение проверяемое: обработать его
 * должен тот, кто знает, можно ли команду переиграть.
 */
public final class VersionConflict extends Exception {

    private static final long serialVersionUID = 1L;

    private final String aggregateId;
    private final int expectedVersion;

    public VersionConflict(String aggregateId, int expectedVersion) {
        super("состояние " + aggregateId + " изменилось: ожидалась версия "
                + expectedVersion);
        this.aggregateId = aggregateId;
        this.expectedVersion = expectedVersion;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public int getExpectedVersion() {
        return expectedVersion;
    }
}
