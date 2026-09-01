package sonder.shell.store;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Version;
import java.time.Instant;

/**
 * Комментарий как агрегат write-модели.
 *
 * <p>Операция комментария появилась в контракте позже остальных: границы и
 * коды отказа для неё были объявлены с самого начала, а операции не было, и
 * три кода существовали только на бумаге. Нашла это механическая сверка
 * полноты golden-набора, а не чтение глазами.
 */
@Entity
@Table(name = "COMMENTS")
public class CommentEntity {

    @Id
    @Column(name = "ID", nullable = false, updatable = false)
    private String id;

    @Column(name = "POST_ID", nullable = false, updatable = false)
    private String postId;

    @Column(name = "AUTHOR_ID", nullable = false, updatable = false)
    private String authorId;

    @Column(name = "BODY", nullable = false)
    private String body;

    @Version
    @Column(name = "VERSION", nullable = false)
    private int version;

    @Column(name = "CREATED_AT", nullable = false)
    private Instant createdAt;

    protected CommentEntity() {
    }

    public CommentEntity(String id, String postId, String authorId,
                         String body, Instant createdAt) {
        this.id = id;
        this.postId = postId;
        this.authorId = authorId;
        this.body = body;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getPostId() {
        return postId;
    }

    public String getAuthorId() {
        return authorId;
    }

    public String getBody() {
        return body;
    }

    public int getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
