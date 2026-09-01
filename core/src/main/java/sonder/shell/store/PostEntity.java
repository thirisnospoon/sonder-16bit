package sonder.shell.store;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Version;
import java.time.Instant;

/**
 * Пост как агрегат write-модели.
 *
 * <p>Удаление — смена статуса, а не удаление строки. Причина не в
 * аккуратности: событие {@code post.deleted} уже ушло в outbox и будет
 * обработано конвейером, а обработчику может понадобиться то, что он
 * обрабатывает. Строка, исчезнувшая раньше своего события, даёт гонку,
 * которая проявляется под нагрузкой и никак иначе.
 */
@Entity
@Table(name = "POSTS")
public class PostEntity {

    @Id
    @Column(name = "ID", nullable = false, updatable = false)
    private String id;

    @Column(name = "AUTHOR_ID", nullable = false, updatable = false)
    private String authorId;

    @Column(name = "BODY", nullable = false)
    private String body;

    @Column(name = "STATUS", nullable = false)
    private String status;

    @Version
    @Column(name = "VERSION", nullable = false)
    private int version;

    @Column(name = "CREATED_AT", nullable = false)
    private Instant createdAt;

    protected PostEntity() {
    }

    public PostEntity(String id, String authorId, String body,
                      String status, Instant createdAt) {
        this.id = id;
        this.authorId = authorId;
        this.body = body;
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getAuthorId() {
        return authorId;
    }

    public String getBody() {
        return body;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
