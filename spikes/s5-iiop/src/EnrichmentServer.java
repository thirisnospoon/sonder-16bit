// Спайк S5 — серверная сторона IIOP.
//
// Публикует объект и записывает его IOR в общий том. Клиент из соседнего
// контейнера читает IOR и вызывает метод.
//
// Проверяемое опасение: ORB зашивает в IOR адрес, по которому он сам себя
// видит. Внутри контейнера это может оказаться loopback или внутренний
// идентификатор, недостижимый снаружи. Лечится свойством ORBServerHost,
// и именно это здесь и проверяется.

import java.io.File;
import java.io.PrintWriter;
import java.util.Properties;

import org.omg.CORBA.ORB;
import org.omg.PortableServer.POA;
import org.omg.PortableServer.POAHelper;

import sonder.enrichment.Enrichment;
import sonder.enrichment.NotFound;
import sonder.enrichment.PostView;
import sonder.enrichment.EnrichmentPOA;

public class EnrichmentServer {

    static class Impl extends EnrichmentPOA {
        private int calls = 0;

        @Override
        public PostView loadPost(String postId) throws NotFound {
            calls++;
            if (postId == null || postId.startsWith("missing")) {
                throw new NotFound(postId);
            }
            return new PostView(
                    postId,
                    "andrey",
                    "Пост номер " + postId + ", проверка кириллицы через IIOP",
                    1756684800000L);
        }

        @Override
        public int ping(int seq) {
            calls++;
            return seq + 1;
        }

        @Override
        public String echoText(String text) {
            calls++;
            return text;
        }
    }

    public static void main(String[] args) throws Exception {
        String host = System.getenv().getOrDefault("ORB_HOST", "core");
        String port = System.getenv().getOrDefault("ORB_PORT", "1050");
        String iorPath = System.getenv().getOrDefault("IOR_PATH", "/shared/enrichment.ior");

        Properties props = new Properties();
        // Без этого в IOR попадает адрес, по которому контейнер видит себя сам.
        props.setProperty("com.sun.CORBA.ORBServerHost", host);
        props.setProperty("com.sun.CORBA.ORBServerPort", port);

        ORB orb = ORB.init(args, props);

        POA rootPoa = POAHelper.narrow(orb.resolve_initial_references("RootPOA"));
        rootPoa.the_POAManager().activate();

        Impl impl = new Impl();
        org.omg.CORBA.Object ref = rootPoa.servant_to_reference(impl);
        Enrichment service = sonder.enrichment.EnrichmentHelper.narrow(ref);

        String ior = orb.object_to_string(service);

        File out = new File(iorPath);
        File tmp = new File(iorPath + ".tmp");
        PrintWriter pw = new PrintWriter(tmp, "UTF-8");
        pw.print(ior);
        pw.close();
        // Переименование атомарно: клиент не увидит файл наполовину записанным.
        if (!tmp.renameTo(out)) {
            System.err.println("не удалось опубликовать IOR в " + iorPath);
            System.exit(1);
        }

        System.out.println("сервер поднят, ORBServerHost=" + host + " порт=" + port);
        System.out.println("IOR опубликован в " + iorPath + ", длина " + ior.length());
        System.out.flush();

        orb.run();
    }
}
