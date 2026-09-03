package sonder.shell.decider;

import org.apache.cxf.frontend.ClientProxy;
import org.apache.cxf.jaxws.JaxWsProxyFactoryBean;
import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.cxf.transports.http.configuration.HTTPClientPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import sonder.contract.ErrorCode;
import sonder.contract.decider.Decider;
import sonder.gateway.LineTransport;
import sonder.gateway.soap.LineDecider;

/**
 * Какое ядро окажется в контексте.
 *
 * <p>Вариантов три, и все три реализуют один интерфейс — вся остальная
 * оболочка разницы не видит (ADR-0011).
 *
 * <ul>
 *   <li>Поднята линия ({@code sonder.decider.line.port}) — настоящая
 *       NODE-7 за нульмодемом. Так система работает в бою.</li>
 *   <li>Задан адрес ({@code sonder.decider.endpoint}) — клиент SOAP по
 *       HTTP, порождённый из того же WSDL. Так ядро подменяют в стендах,
 *       где эмулятора нет.</li>
 *   <li>Не задано ничего — {@link UnavailableDecider}, честно отвечающий
 *       {@link ErrorCode#DECIDER_UNAVAILABLE}.</li>
 * </ul>
 *
 * <p><b>Заданы оба — запуск падает.</b> Догадка о том, какой из двух
 * «настоящее», была бы решением, принятым за человека, а цена ошибки
 * несимметрична: команды тихо уехали бы не туда и вернулись бы
 * правдоподобными решениями от чужого ядра.
 *
 * <p>Умолчаний нет ни у адреса, ни у порта. Умолчание вида
 * {@code http://localhost:8081} однажды сработает в неожиданном месте и
 * пошлёт команды не туда — тому же правилу подчиняется адрес базы.
 */
@Configuration
public class DeciderConfig {

    private static final Logger log = LoggerFactory.getLogger(DeciderConfig.class);

    /**
     * Клиент к ядру.
     *
     * <p>Отказы транспорта переводит в решение {@link DeciderFaults}:
     * оболочка обязана увидеть отказ ядра исходом команды, а не
     * исключением, потому что для исключения у контракта формы нет.
     */
    @Bean
    public Decider decider(
            ObjectProvider<LineTransport> lineTransport,
            @Value("${sonder.decider.endpoint:}") String endpoint,
            @Value("${sonder.decider.connect-timeout-ms:2000}") long connectMs,
            @Value("${sonder.decider.receive-timeout-ms:5000}") long receiveMs) {

        LineTransport line = lineTransport.getIfAvailable();
        boolean hasEndpoint = endpoint != null && !endpoint.trim().isEmpty();

        if (line != null && hasEndpoint) {
            throw new IllegalStateException(
                    "заданы и линия (порт " + line.getPort() + "), и адрес "
                            + endpoint + ": какое из двух ядер настоящее, "
                            + "решать не мне. Оставьте одно");
        }

        if (line != null) {
            log.info("ядро за линией на порту {} (срок команды {})",
                    line.getPort(), line.getTimeout());
            // Срок берётся у транспорта, а не свой: у ждущего и у
            // уборщика каналов он обязан быть одним числом.
            return DeciderFaults.wrap(
                    new LineDecider(line.getMux(), line.getTimeout()),
                    "линия :" + line.getPort());
        }

        if (!hasEndpoint) {
            log.warn("ядро не задано ни линией, ни адресом: команды будут "
                    + "отвечать {}", ErrorCode.DECIDER_UNAVAILABLE.name());
            return new UnavailableDecider();
        }

        log.info("ядро по адресу {} (соединение {} мс, ответ {} мс)",
                endpoint, connectMs, receiveMs);
        return DeciderFaults.wrap(
                soapClient(endpoint, connectMs, receiveMs), endpoint);
    }

    /**
     * Голый клиент CXF: без обёртки отказов, зато с обязательными сроками.
     *
     * <p><b>Сроки не по вкусу, а по измерению.</b> Спайк S2 намерял на
     * линии 11 503 Б/с в направление и 13 мс на круговой обмен. Конверт
     * команды — около 700 байт, ответ короче; на провод уходит порядка
     * сотни миллисекунд, решение ядра есть чистая функция и считается
     * микросекунды. Пять секунд — полсотни таких обменов: если за это
     * время ответа нет, ждать дальше нечего, сломалось что-то другое.
     *
     * <p>Срок соединения отдельный и вдвое короче: не открывшийся сокет —
     * это «гейтвея нет», а не «нода думает», и различать их полезно.
     *
     * <p>Клиента <b>без</b> срока ответа здесь быть не может. Умолчание
     * CXF замерено и равно 60 000 мс — не бесконечность, как думалось, но
     * в двенадцать раз больше нужного: залипшая команда держала бы поток
     * сервлета минуту, и полусотни таких хватило бы, чтобы приложение
     * перестало отвечать вовсе.
     */
    public static Decider soapClient(String endpoint, long connectMs, long receiveMs) {
        JaxWsProxyFactoryBean factory = new JaxWsProxyFactoryBean();
        factory.setServiceClass(Decider.class);
        factory.setAddress(endpoint);

        Decider client = (Decider) factory.create();

        HTTPClientPolicy policy = new HTTPClientPolicy();
        policy.setConnectionTimeout(connectMs);
        policy.setReceiveTimeout(receiveMs);

        HTTPConduit conduit =
                (HTTPConduit) ClientProxy.getClient(client).getConduit();
        conduit.setClient(policy);

        return client;
    }
}
