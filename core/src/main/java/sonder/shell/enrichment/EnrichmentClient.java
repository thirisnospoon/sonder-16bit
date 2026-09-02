package sonder.shell.enrichment;

import org.omg.CORBA.ORB;
import sonder.enrichment.Enrichment;
import sonder.enrichment.EnrichmentHelper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;

/**
 * Потребитель обогащения.
 *
 * <p>Читает ссылку из файла, который опубликовал {@link EnrichmentServer}, и
 * разворачивает её в вызываемый объект. Больше здесь ничего и не должно
 * быть: всё, что делает вызов вызовом, породил {@code idlj} из IDL.
 *
 * <p><b>Ссылка читается один раз при создании, а не при каждом вызове.</b>
 * Перечитывать её на каждом обращении значило бы платить обращением к
 * файловой системе за то, что меняется раз в перезапуск. Перезапуск
 * {@code core} при этом меняет ссылку, и старая перестаёт работать —
 * лечится пересозданием клиента, а не постоянным перечитыванием: отличать
 * «сервер перезапустился» от «сервер лежит» всё равно придётся по отказу
 * вызова.
 */
public final class EnrichmentClient implements AutoCloseable {

    private final ORB orb;
    private final Enrichment service;

    private EnrichmentClient(ORB orb, Enrichment service) {
        this.orb = orb;
        this.service = service;
    }

    /** Вызываемое обогащение. */
    public Enrichment service() {
        return service;
    }

    /** Подключиться по ссылке из файла. */
    public static EnrichmentClient connect(File iorFile) throws IOException {
        if (!iorFile.isFile()) {
            throw new IOException(
                    "нет ссылки на обогащение: " + iorFile
                            + ". Её публикует core при старте — значит, он ещё "
                            + "не поднялся или пишет не туда");
        }
        String ior = new String(Files.readAllBytes(iorFile.toPath()),
                StandardCharsets.US_ASCII).trim();
        return connect(ior);
    }

    /** Подключиться по готовой ссылке. */
    public static EnrichmentClient connect(String ior) {
        ORB orb = ORB.init(new String[0], new Properties());
        org.omg.CORBA.Object ref = orb.string_to_object(ior);
        return new EnrichmentClient(orb, EnrichmentHelper.narrow(ref));
    }

    @Override
    public void close() {
        orb.destroy();
    }
}
