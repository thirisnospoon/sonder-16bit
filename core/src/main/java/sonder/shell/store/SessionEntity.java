package sonder.shell.store;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Version;
import java.time.Instant;

/**
 * Сессия. Аутентификация целиком в оболочке: она требует обращения к
 * хранилищу, а ядро под DOS обратиться никуда не может (ADR-0011). Коды
 * SESSION_INVALID и CREDENTIALS_INVALID помечены в контракте как решаемые
 * оболочкой именно поэтому.
 *
 * <p>Версия здесь не для конкурентного доступа — сессию никто не правит
 * параллельно, — а ради единообразия: агрегат без версии однажды окажется
 * тем, который правят.
 */
@Entity
@Table(name = "SESSIONS")
public class SessionEntity {

    @Id
    @Column(name = "TOKEN", nullable = false, updatable = false)
    private String token;

    @Column(name = "USER_ID", nullable = false, updatable = false)
    private String userId;

    @Column(name = "CREATED_AT", nullable = false)
    private Instant createdAt;

    @Column(name = "EXPIRES_AT", nullable = false)
    private Instant expiresAt;

    @Version
    @Column(name = "VERSION", nullable = false)
    private int version;

    protected SessionEntity() {
    }

    public SessionEntity(String token, String userId,
                         Instant createdAt, Instant expiresAt) {
        this.token = token;
        this.userId = userId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String getToken() {
        return token;
    }

    public String getUserId() {
        return userId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public int getVersion() {
        return version;
    }
}
