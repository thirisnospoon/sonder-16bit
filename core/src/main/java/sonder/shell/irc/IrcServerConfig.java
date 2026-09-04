package sonder.shell.irc;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

import javax.sql.DataSource;
import java.io.IOException;

/**
 * Подъём шлюза IRC — только когда порт назван.
 *
 * <p>Тот же приём, что у линии к ноде: пустой порт означает, что шлюза
 * нет вовсе и порт не занимается. Умолчание вида 6667 однажды открыло бы
 * порт там, где его не ждут, — а порт, о котором не знают, не охраняют.
 *
 * <p>Поднимается СРАЗУ, а не отложенно: занятый порт обязан уронить
 * запуск. Приложение, поднявшееся без обещанного шлюза, выглядит
 * работающим, а клиенты просто не могут подключиться — и разбираться в
 * этом будут не там.
 *
 * <p>Ноль как порт означает «любой свободный»: так шлюз поднимает
 * проверка, не занимая 6667 на машине, где он может быть занят чужим
 * ircd. Настоящий порт спрашивают потом у {@link IrcServer#boundPort()}.
 */
@Configuration
public class IrcServerConfig {

    @Bean(destroyMethod = "close")
    @Conditional(PortNamed.class)
    public IrcServer ircServer(
            DataSource dataSource,
            @Value("${sonder.irc.port}") int port,
            @Value("${sonder.irc.max-connections:64}") int maxConnections,
            @Value("${sonder.irc.handshake-timeout-ms:60000}") int handshakeTimeoutMs)
            throws IOException {
        IrcServer server =
                new IrcServer(dataSource, port, maxConnections, handshakeTimeoutMs);
        server.start();
        return server;
    }

    /** Порт назван — шлюз есть; пусто — нет. */
    static final class PortNamed implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata md) {
            String value = context.getEnvironment().getProperty("sonder.irc.port");
            return value != null && !value.trim().isEmpty();
        }
    }
}
