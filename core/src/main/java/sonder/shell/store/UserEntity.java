package sonder.shell.store;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Version;
import java.time.Instant;

/**
 * Пользователь как агрегат write-модели.
 *
 * <p>Здесь нет ни одного доменного правила, и это проверяется, а не
 * подразумевается: {@code sonder.arch.ArchitectureTest} роняет сборку, если
 * оболочка вздумает решать за ядро. Длина ника, допустимые символы, право
 * заблокировать — всё это решает NODE-7 (ADR-0011). Задача этого класса —
 * донести состояние до ядра и записать то, что ядро решило.
 *
 * <p>Ограничения длины в аннотациях НЕ дублируются: они заданы схемой, а
 * схема сверяется с контрактом валидатором. Продублировать их здесь значило
 * бы завести третье место, где живёт одно и то же число.
 *
 * <p>{@code version} — оптимистическая блокировка. Ядро видит версию в
 * контексте команды и решает под неё; если между загрузкой и сохранением
 * версия сдвинулась, команду надо переиграть, а не «дожать».
 */
@Entity
@Table(name = "USERS")
public class UserEntity {

    @Id
    @Column(name = "ID", nullable = false, updatable = false)
    private String id;

    @Column(name = "NICK", nullable = false)
    private String nick;

    @Column(name = "DISPLAY_NAME", nullable = false)
    private String displayName;

    /**
     * Роль хранится строкой, а не {@code @Enumerated(ORDINAL)}: порядковый
     * номер молча меняет смысл при вставке значения в середину контракта, и
     * обнаруживается это на уже записанных данных.
     */
    @Column(name = "ROLE", nullable = false)
    private String role;

    @Column(name = "STATUS", nullable = false)
    private String status;

    @Column(name = "PASSWORD_HASH", nullable = false)
    private String passwordHash;

    @Version
    @Column(name = "VERSION", nullable = false)
    private int version;

    @Column(name = "CREATED_AT", nullable = false)
    private Instant createdAt;

    protected UserEntity() {
        // Требуется JPA.
    }

    public UserEntity(String id, String nick, String displayName,
                      String role, String status, String passwordHash,
                      Instant createdAt) {
        this.id = id;
        this.nick = nick;
        this.displayName = displayName;
        this.role = role;
        this.status = status;
        this.passwordHash = passwordHash;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getNick() {
        return nick;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public int getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
