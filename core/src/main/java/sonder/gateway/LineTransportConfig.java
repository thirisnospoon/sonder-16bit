package sonder.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.io.IOException;
import java.time.Duration;

/**
 * Линия появляется в приложении только тогда, когда для неё назван порт.
 *
 * <p>Умолчания у порта нет намеренно — по тому же правилу, что у адреса
 * базы и адреса ядра. Умолчание вида 5077 однажды сработает в
 * неожиданном месте: приложение займёт порт, будет ждать ноду, а команды
 * — уходить в никуда и отваливаться по сроку. Отсутствие настройки
 * должно означать отсутствие линии, а не молчаливую линию.
 *
 * <p>Порт 0 при этом законен и значит «любой свободный»: так линию
 * поднимают тесты, спрашивая потом {@link LineTransport#getPort()}.
 */
@Configuration
public class LineTransportConfig {

    /**
     * @param timeoutMs срок команды. Тот же, что у клиента SOAP, и по той
     *                  же причине: спайк S2 намерял 11 503 Б/с и 13 мс на
     *                  круг, обмен укладывается в сотню миллисекунд, пять
     *                  секунд — полсотни таких обменов
     */
    @Bean(destroyMethod = "close")
    @Conditional(PortNamed.class)
    public LineTransport lineTransport(
            @Value("${sonder.decider.line.port}") int port,
            @Value("${sonder.decider.line.timeout-ms:5000}") long timeoutMs)
            throws IOException {

        LineTransport transport =
                new LineTransport(port, Duration.ofMillis(timeoutMs));
        // Поднимается здесь, а не отложенно: не занявшийся порт обязан
        // уронить запуск. Приложение, поднявшееся без линии, отвечало бы
        // 502 на каждую команду и выглядело бы работающим.
        transport.start();
        return transport;
    }

    /**
     * Порт назван — значит, непустой.
     *
     * <p>Готового {@code @ConditionalOnProperty} тут не хватает: он
     * считает настройку заданной, если ключ ЕСТЬ, а пустая строка —
     * обычный способ выключить настройку в этом проекте
     * ({@code ${SONDER_DECIDER_LINE_PORT:}}). Приложение поднималось бы и
     * падало на разборе пустого числа, то есть выключенная линия роняла
     * бы запуск.
     */
    static final class PortNamed implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata md) {
            String value = context.getEnvironment()
                    .getProperty("sonder.decider.line.port");
            return value != null && !value.trim().isEmpty();
        }
    }
}
