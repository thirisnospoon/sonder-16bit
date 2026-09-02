package sonder.shell.enrichment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import sonder.enrichment.Enrichment;

import javax.sql.DataSource;
import java.io.File;

/**
 * Обе стороны обогащения в одном процессе — пока они в одном процессе.
 *
 * <p>`core` и `events` объявлены отдельными единицами развёртывания, но
 * живут пока в одном приложении. Вызов при этом настоящий: ORB, IOR,
 * маршалинг, петля. Смысл не в том, чтобы усложнить себе жизнь, а в том,
 * что граница либо есть, либо её нет: код, который «пока» ходит в базу
 * напрямую, а «потом» будет ходить вызовом, не переписывается никогда.
 *
 * <p><b>Ссылка берётся у сервера, если он тут же.</b> Когда единицы
 * разъедутся, {@code events} не поднимет сервер, а прочитает ссылку из
 * файла: путь задаётся {@code sonder.enrichment.ior}. Оба способа
 * приводят к одному и тому же клиенту, и выбор между ними — это выбор
 * топологии, а не кода.
 */
@Configuration
public class EnrichmentConfig {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentConfig.class);

    /**
     * Сервер обогащения.
     *
     * <p>Порт по умолчанию нулевой — «любой свободный». Так тесты и
     * несколько экземпляров на одной машине не дерутся за один номер, а
     * адрес в ссылке всё равно верен: его туда кладёт ORB.
     */
    @Bean(destroyMethod = "close")
    public EnrichmentServer enrichmentServer(
            DataSource dataSource,
            @Value("${sonder.enrichment.host:127.0.0.1}") String host,
            @Value("${sonder.enrichment.port:0}") int port,
            @Value("${sonder.enrichment.ior:}") String iorPath) throws Exception {

        File ior = iorPath.isEmpty() ? null : new File(iorPath);
        return EnrichmentServer.start(
                new EnrichmentServant(dataSource::getConnection), host, port, ior);
    }

    @Bean(destroyMethod = "close")
    public EnrichmentClient enrichmentClient(EnrichmentServer server) {
        log.info("обогащение берётся у сервера в этом же процессе");
        return EnrichmentClient.connect(server.getIor());
    }

    /** То, что зовут: интерфейс из IDL, а не обёртка над ним. */
    @Bean
    public Enrichment enrichment(EnrichmentClient client) {
        return client.service();
    }
}
