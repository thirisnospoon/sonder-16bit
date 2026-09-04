package sonder.shell.irc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sonder.shell.outbox.OutboxRecord;
import sonder.shell.stream.FeedListener;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Лента в канал: новый пост приходит клиенту IRC сообщением автора.
 *
 * <p>Питается той же рассылкой, что и браузер, — {@code FeedStream}, — и
 * это главное свойство. Второй ответ на вопрос «чья это новость»
 * означал бы, что однажды в браузере новость есть, а в IRC её нет.
 *
 * <p><b>Тело поста берётся из базы, а не из события.</b> Событие несёт
 * то, что положило в него ядро, — идентификаторы и поля решения, — а
 * тело живёт в write-модели: копия тела в событии была бы вторым
 * источником правды. Цена — одно чтение по первичному ключу на
 * доставку; при десятке открытых соединений это десяток чтений на пост,
 * и мерить тут пока нечего.
 *
 * <p><b>Отправка идёт под тем же замком, что и ответы разговора.</b>
 * Рассылка зовёт доставку из своего потока, а разговор пишет из потока
 * соединения; без замка две строки перемешались бы посреди друг друга, и
 * получилась бы не строка, а мусор — причём изредка.
 */
final class IrcFeedListener implements FeedListener {

    private static final Logger log = LoggerFactory.getLogger(IrcFeedListener.class);

    /** Что рассылается в канал. Прочие события ленты клиента не касаются. */
    private static final String POST_CREATED = "post.created";

    private static final String POST_SQL =
            "SELECT u.nick, p.body FROM posts p"
                    + " JOIN users u ON u.id = p.author_id"
                    + " WHERE p.id = ?";

    /** Куда писать строки. Реализует соединение. */
    interface Wire {
        void write(String line) throws IOException;
    }

    private final DataSource dataSource;
    private final Wire wire;

    IrcFeedListener(DataSource dataSource, Wire wire) {
        this.dataSource = dataSource;
        this.wire = wire;
    }

    @Override
    public void deliver(OutboxRecord record) throws IOException {
        if (!POST_CREATED.equals(record.getType())) {
            return;
        }
        String nick;
        String body;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(POST_SQL)) {
            ps.setString(1, record.getAggregateId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    // Пост исчез между событием и доставкой: удалён,
                    // пока новость шла. Это не беда соединения — молчим.
                    return;
                }
                nick = rs.getString(1);
                body = rs.getString(2);
            }
        } catch (SQLException e) {
            // База недоступна — это НЕ смерть соединения. Рассылка
            // снимает слушателя со списка на IOException, и брось мы его
            // здесь, недоступная на секунду база отписала бы от ленты
            // всех, кто в этот момент сидит в канале.
            //
            // Новость при этом потеряна, и клиент увидит её, перечитав
            // ленту, — ровно как при обрыве у браузера. Молчать нельзя:
            // потеря без записи в журнале неотличима от «постов не было».
            log.warn("IRC: пост {} не доставлен в канал: {}",
                    record.getAggregateId(), e.toString());
            return;
        }

        for (String line : IrcCast.privmsgs(nick, IrcSession.CHANNEL, body)) {
            wire.write(line);
        }
    }

    @Override
    public void beat() throws IOException {
        // Своего удара сердца тут нет, и это решение, а не забывчивость.
        //
        // У SSE он нужен, потому что промежуточные прокси рвут
        // простаивающее соединение молча, а комментарий в потоке событий
        // невидим клиенту. В IRC комментариев нет: всякий байт от сервера
        // клиент показывает человеку, и «тук» раз в двадцать секунд
        // означал бы двадцать сообщений в час в канале.
        //
        // Мёртвое соединение при этом не копится: поток, читающий сокет,
        // узнаёт об обрыве первым и отписывает слушателя сам.
    }
}
