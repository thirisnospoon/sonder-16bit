package sonder.shell.enrichment;

import sonder.enrichment.EnrichmentPOA;
import sonder.enrichment.NotFound;
import sonder.enrichment.PostView;
import sonder.shell.app.ConnectionSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Обогащение событий: что `core` рассказывает про свои агрегаты.
 *
 * <p>Порождён из {@code contracts/idl/enrichment-v1.idl} тем же {@code idlj},
 * что даёт стаб потребителю. Contract-first в обе стороны, как и с WSDL.
 *
 * <p><b>Зачем вообще вызов, если база одна.</b> Затем, что она одна не
 * навсегда: {@code events} обязан строить проекции на том, чем владеет сам
 * ([ADR-0016](../../../../../../docs/adr/0016-events-owns-its-data.md)), и
 * содержимое агрегата к этому не относится. Тело поста принадлежит
 * {@code core}, и спрашивать его надо у {@code core}, а не читать из-под него
 * таблицу.
 *
 * <p><b>Про кодировки.</b> В IDL человеческий текст объявлен {@code wstring},
 * и это выяснено прогоном, а не вычитано: {@code string} в CORBA байтовый, и
 * кириллица в него не проходит — вызов падает с {@code DATA_CONVERSION} уже в
 * рантайме, на настоящих данных. Идентификаторы остаются {@code string}: они
 * ASCII по контракту.
 *
 * <p>Удалённый пост неотличим от несуществующего: {@code NotFound} в обоих
 * случаях. Иначе ответ сообщал бы, что такой пост когда-то был.
 */
public class EnrichmentServant extends EnrichmentPOA {

    private final ConnectionSource connections;

    public EnrichmentServant(ConnectionSource connections) {
        this.connections = connections;
    }

    @Override
    public PostView loadPost(String postId) throws NotFound {
        try (Connection c = connections.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT p.id, u.nick, p.body, p.created_at FROM posts p"
                             + " JOIN users u ON u.id = p.author_id"
                             + " WHERE p.id = ? AND p.status = 'VISIBLE'")) {
            ps.setString(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new NotFound(postId);
                }
                return new PostView(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getTimestamp(4).getTime());
            }
        } catch (SQLException e) {
            // Отказ базы — не «не найдено». Спутать их значило бы сказать
            // потребителю, что поста нет, и он выбросил бы событие как
            // относящееся к несуществующему агрегату.
            throw new org.omg.CORBA.TRANSIENT(
                    "база не ответила: " + e.getMessage());
        }
    }

    /** Дешёвый вызов для замера круговой задержки без полезной нагрузки. */
    @Override
    public int ping(int seq) {
        return seq;
    }

    /**
     * Отдельная проверка границы кодировок: возвращает то, что дали.
     *
     * <p>Не отладочный остаток, а часть контракта: именно на этом вызове
     * ловится расхождение кодировок между ORB, и ловится оно однозначно —
     * вернулось не то, что послали.
     */
    @Override
    public String echoText(String text) {
        return text;
    }
}
