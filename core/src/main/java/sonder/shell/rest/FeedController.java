package sonder.shell.rest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sonder.contract.ErrorCode;
import sonder.shell.auth.SessionStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Лента.
 *
 * <p>Читает проекцию {@code feed_entries}, а не собирает ленту на лету
 * соединением с подписками: раскладку сделал фанаут на записи
 * ({@code sonder.shell.projection.FeedProjection}), и здесь остаётся
 * страница по ключу.
 *
 * <p>Ядра тут нет и быть не должно: читать — не решать. Единственное
 * решение, которое принимает контроллер, — чья это лента, и принимает он
 * его по сессии.
 *
 * <p><b>Тело и автор берутся из write-модели.</b> Проекция хранит только
 * то, чем лента упорядочена: владельца, пост, автора и время. Копия тела
 * в проекции была бы вторым источником правды — тем же, от которого
 * отказались в полезной нагрузке события.
 */
@RestController
public class FeedController {

    /** Умолчание и потолок — из контракта, а не из вкуса. */
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;

    private final DataSource dataSource;

    public FeedController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/feed")
    public ResponseEntity<Map<String, Object>> getFeed(
            @CookieValue(value = SessionCookie.NAME, required = false) String token,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit)
            throws SQLException {

        String traceId = Trace.current();
        String actorId = actor(token);
        if (actorId == null) {
            return RestErrors.of(ErrorCode.SESSION_INVALID, traceId);
        }

        // Границы контракта — это границы, а не подсказки: за ними
        // запрос отвергается, а не подтягивается молча к ближайшему
        // допустимому. Молчаливая правка означала бы, что клиент попросил
        // одно, получил другое и не узнал об этом.
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            return RestErrors.of(ErrorCode.MALFORMED_REQUEST, traceId);
        }

        FeedCursor from = null;
        if (cursor != null && !cursor.isEmpty()) {
            from = FeedCursor.parse(cursor);
            if (from == null) {
                return RestErrors.of(ErrorCode.MALFORMED_REQUEST, traceId);
            }
        }

        // Берём на одну строку больше, чем отдаём. Это и есть ответ на
        // вопрос «есть ли ещё»: считать общее число строк ленты дороже и
        // всё равно неверно — пока считаешь, их становится больше.
        List<Map<String, Object>> items = new ArrayList<>();
        Instant lastAt = null;
        String lastId = null;
        boolean hasMore = false;

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(pageSql(from != null, size))) {
            int p = 1;
            ps.setString(p++, actorId);
            if (from != null) {
                ps.setTimestamp(p++, Timestamp.from(from.getCreatedAt()));
                ps.setTimestamp(p++, Timestamp.from(from.getCreatedAt()));
                ps.setString(p, from.getPostId());
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (items.size() == size) {
                        hasMore = true;
                        break;
                    }
                    Map<String, Object> author = new LinkedHashMap<>();
                    author.put("userId", rs.getString(2));
                    author.put("nick", rs.getString(3));
                    author.put("displayName", rs.getString(4));

                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("postId", rs.getString(1));
                    item.put("author", author);
                    item.put("body", rs.getString(5));
                    lastAt = rs.getTimestamp(6).toInstant();
                    lastId = rs.getString(1);
                    item.put("createdAt", lastAt.toString());
                    items.add(item);
                }
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        // Курсор отдаётся только когда есть что листать. Курсор при
        // hasMore = false звал бы клиента за пустой страницей.
        if (hasMore && lastId != null) {
            body.put("nextCursor", new FeedCursor(lastAt, lastId).encode());
        }
        body.put("hasMore", hasMore);
        return ResponseEntity.ok(body);
    }

    /**
     * Страница ленты.
     *
     * <p>Соединение с {@code posts} не только за телом: пост мог стать
     * невидимым между фанаутом и чтением, и отдавать его читателю нельзя.
     * Проекция такую строку уберёт по событию {@code post.deleted}, но
     * между удалением и дренажом проходит время, и лента обязана быть
     * права раньше очереди.
     */
    private static String pageSql(boolean withCursor, int size) {
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT fe.post_id, u.id, u.nick, u.display_name,")
          .append(" p.body, fe.created_at")
          .append(" FROM feed_entries fe")
          .append(" JOIN posts p ON p.id = fe.post_id")
          .append(" JOIN users u ON u.id = p.author_id")
          .append(" WHERE fe.owner_id = ? AND p.status = 'VISIBLE'");
        if (withCursor) {
            // Строго старше курсора. Равенство времени разрешается
            // идентификатором — иначе пост-ровесник либо потерялся бы,
            // либо приехал дважды.
            sb.append(" AND (fe.created_at < ?")
              .append(" OR (fe.created_at = ? AND fe.post_id < ?))");
        }
        sb.append(" ORDER BY fe.created_at DESC, fe.post_id DESC")
          .append(" ROWS ").append(size + 1);
        return sb.toString();
    }

    private String actor(String token) throws SQLException {
        if (token == null) {
            return null;
        }
        try (Connection c = dataSource.getConnection()) {
            return SessionStore.userOf(c, token, Instant.now());
        }
    }
}
