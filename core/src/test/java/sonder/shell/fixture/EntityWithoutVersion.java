package sonder.shell.fixture;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * НАМЕРЕННЫЙ НАРУШИТЕЛЬ: агрегат без версии.
 *
 * <p>Забыть @Version легко, а последствие незаметно: два параллельных
 * сохранения перезаписывают друг друга, и обнаруживается это по пропавшему
 * изменению, а не по ошибке.
 */
@Entity
@Table(name = "FIXTURE_NO_VERSION")
public class EntityWithoutVersion {

    @Id
    private String id;

    public String getId() {
        return id;
    }
}
