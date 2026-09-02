package sonder.shell.enrichment;

import org.omg.CORBA.ORB;
import org.omg.PortableServer.POA;
import org.omg.PortableServer.POAHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sonder.enrichment.Enrichment;
import sonder.enrichment.EnrichmentHelper;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Публикация обогащения по IIOP.
 *
 * <p><b>Адрес приходит извне и зашивается в IOR.</b> Это главное, что здесь
 * есть, и главное, на чём IIOP ломается в контейнерах: ORB по умолчанию
 * кладёт в ссылку тот адрес, по которому он видит сам себя, — внутри
 * контейнера это его собственный идентификатор, снаружи недостижимый.
 * Клиент читает ссылку, идёт по указанному адресу и не приходит никуда.
 * Лечится {@code com.sun.CORBA.ORBServerHost}, и спайк S5 это подтвердил
 * прогоном в сети compose.
 *
 * <p><b>Ссылка публикуется файлом, а не через службу имён.</b> Служба имён —
 * ещё один процесс, который надо поднять, дождаться и починить, когда он
 * упадёт. Файл в общем томе решает ту же задачу тем, что уже есть, и
 * записывается атомарно: сначала во временный, потом переименованием. Иначе
 * потребитель однажды прочитает половину ссылки.
 */
public final class EnrichmentServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentServer.class);

    private final ORB orb;
    private final Thread runner;
    private final String ior;

    private EnrichmentServer(ORB orb, Thread runner, String ior) {
        this.orb = orb;
        this.runner = runner;
        this.ior = ior;
    }

    /** Ссылка на опубликованный объект. */
    public String getIor() {
        return ior;
    }

    /**
     * Поднять ORB, опубликовать сервант и записать ссылку.
     *
     * @param host адрес, по которому потребитель увидит сервер: он и
     *             попадёт в IOR
     * @param port порт ORB; ноль означает «любой свободный», и тогда адрес
     *             в ссылке всё равно верен — его туда кладёт ORB
     * @param iorFile куда записать ссылку; {@code null} — не записывать
     */
    public static EnrichmentServer start(EnrichmentServant servant,
                                         String host, int port, File iorFile)
            throws Exception {
        Properties props = new Properties();
        props.setProperty("com.sun.CORBA.ORBServerHost", host);
        if (port > 0) {
            props.setProperty("com.sun.CORBA.ORBServerPort", String.valueOf(port));
        }

        ORB orb = ORB.init(new String[0], props);
        POA rootPoa = POAHelper.narrow(orb.resolve_initial_references("RootPOA"));
        rootPoa.the_POAManager().activate();

        org.omg.CORBA.Object ref = rootPoa.servant_to_reference(servant);
        Enrichment service = EnrichmentHelper.narrow(ref);
        String ior = orb.object_to_string(service);

        if (iorFile != null) {
            writeAtomically(iorFile, ior);
        }

        Thread runner = new Thread(orb::run, "enrichment-orb");
        runner.setDaemon(true);
        runner.start();

        log.info("обогащение опубликовано: host={} port={} ссылка в {}",
                host, port, iorFile);
        return new EnrichmentServer(orb, runner, ior);
    }

    /**
     * Запись через временный файл и переименование.
     *
     * <p>Прямая запись означает, что потребитель может прочитать половину
     * ссылки: файл виден с первого байта, а пишется он не мгновенно.
     * Половина IOR — это не «ошибка чтения», а строка, которая разберётся
     * во что-то неправильное.
     */
    private static void writeAtomically(File target, String content)
            throws IOException {
        File parent = target.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("не создать каталог для ссылки: " + parent);
        }
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        try (Writer w = new OutputStreamWriter(
                new FileOutputStream(tmp), StandardCharsets.US_ASCII)) {
            // IOR по определению ASCII: это шестнадцатеричная запись.
            w.write(content);
        }
        if (!tmp.renameTo(target)) {
            // На некоторых файловых системах переименование поверх
            // существующего не проходит. Удалить и повторить — всё ещё
            // лучше, чем писать поверх живого файла.
            if (!target.delete() || !tmp.renameTo(target)) {
                throw new IOException("не опубликовать ссылку в " + target);
            }
        }
    }

    @Override
    public void close() {
        orb.shutdown(true);
        orb.destroy();
        runner.interrupt();
    }
}
