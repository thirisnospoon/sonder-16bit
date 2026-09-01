// Спайк S5 — клиентская сторона IIOP. Выносит вердикт в формате TAP.

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;

import org.omg.CORBA.ORB;

import sonder.enrichment.Enrichment;
import sonder.enrichment.EnrichmentHelper;
import sonder.enrichment.NotFound;
import sonder.enrichment.PostView;

public class EnrichmentClient {

    static int testNo = 0;
    static int failures = 0;

    static void ok(String name) {
        System.out.println("ok " + (++testNo) + " - " + name);
    }

    static void notOk(String name) {
        System.out.println("not ok " + (++testNo) + " - " + name);
        failures++;
    }

    static void diag(String s) {
        System.out.println("# " + s);
    }

    public static void main(String[] args) throws Exception {
        String iorPath = System.getenv().getOrDefault("IOR_PATH", "/shared/enrichment.ior");
        int rounds = Integer.parseInt(System.getenv().getOrDefault("ROUNDS", "200"));

        System.out.println("1..6");
        diag("spike S5 - CORBA/IIOP между контейнерами");

        // Сервер поднимается параллельно, поэтому ждём появления IOR.
        File ior = new File(iorPath);
        long deadline = System.currentTimeMillis() + 60_000L;
        while (!ior.exists() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        if (!ior.exists()) {
            notOk("IOR не опубликован за 60 с");
            System.out.println("# ИТОГ: сервер не поднялся");
            System.exit(1);
        }
        ok("IOR опубликован сервером");

        String iorText = new String(Files.readAllBytes(ior.toPath()),
                Charset.forName("UTF-8")).trim();
        diag("длина IOR: " + iorText.length());

        ORB orb = ORB.init(args, null);
        org.omg.CORBA.Object obj = orb.string_to_object(iorText);
        Enrichment svc = EnrichmentHelper.narrow(obj);

        if (svc == null) {
            notOk("narrow вернул null: IOR не удалось разобрать");
            System.out.println("# ИТОГ: провал");
            System.exit(1);
        }

        // Главная проверка спайка: вызов должен дойти до соседнего контейнера.
        // Здесь ломается ORB, зашивший в IOR адрес, по которому он видит сам
        // себя, — это и есть риск R4 из реестра.
        try {
            int r = svc.ping(41);
            if (r == 42) {
                ok("вызов дошёл до соседнего контейнера, IOR адресуем");
            } else {
                notOk("ping вернул " + r + " вместо 42");
            }
        } catch (Exception e) {
            notOk("вызов не дошёл: " + e.getClass().getSimpleName());
            diag("это ровно тот риск, ради которого спайк и делался");
            System.out.println("# ИТОГ: провал");
            System.exit(1);
        }

        // Структуры и кириллица через IIOP.
        //
        // Первый прогон спайка падал именно здесь: текстовые поля были
        // объявлены как string, а тип string в CORBA байтовый и кириллицу не
        // принимает — DATA_CONVERSION в рантайме, на реальных данных. После
        // перевода текстовых полей в wstring проверка проходит.
        try {
            PostView pv = svc.loadPost("p-1001");
            boolean okBody = pv.body.contains("кириллицы")
                    && "andrey".equals(pv.authorNick);
            if (okBody) {
                ok("структура и кириллица переживают маршалинг");
            } else {
                notOk("структура повреждена: " + pv.authorNick + " / " + pv.body);
            }
        } catch (Exception e) {
            notOk("маршалинг структуры упал: " + e.getClass().getSimpleName());
        }

        // Явная проверка границы кодировок в обе стороны.
        String probe = "Ёжик, ñ, 中文, ß";
        try {
            String back = svc.echoText(probe);
            if (probe.equals(back)) {
                ok("wstring переносит не-ASCII в обе стороны без потерь");
            } else {
                notOk("текст искажён: получено " + back);
            }
        } catch (Exception e) {
            notOk("echoText упал: " + e.getClass().getSimpleName());
        }

        // Исключения IDL должны доезжать как исключения, а не как обрыв связи.
        try {
            svc.loadPost("missing-1");
            notOk("исключение NotFound не было брошено");
        } catch (NotFound nf) {
            ok("исключение IDL доставлено как исключение");
        } catch (Exception e) {
            notOk("вместо NotFound пришло " + e.getClass().getSimpleName());
        }

        // Латентность: events дёргает core на каждое событие, и эта цифра
        // напрямую ограничивает скорость дренажа outbox.
        long[] samples = new long[rounds];
        for (int i = 0; i < rounds; i++) {
            long t0 = System.nanoTime();
            svc.ping(i);
            samples[i] = System.nanoTime() - t0;
        }
        Arrays.sort(samples);
        double p50 = samples[rounds / 2] / 1_000_000.0;
        double p99 = samples[Math.min(rounds - 1, (int) (rounds * 0.99))] / 1_000_000.0;
        diag(String.format("латентность ping: p50 %.3f мс, p99 %.3f мс, вызовов %d",
                p50, p99, rounds));
        diag(String.format("потолок дренажа outbox по этой латентности: ~%.0f событий/с",
                1000.0 / p50));

        if (p99 < 50.0) {
            ok("латентность IIOP приемлемая");
        } else {
            notOk(String.format("латентность p99 %.1f мс слишком велика", p99));
        }

        if (failures == 0) {
            System.out.println("# ИТОГ: спайк S5 пройден");
        } else {
            System.out.println("# ИТОГ: провалов " + failures);
        }
        System.exit(failures == 0 ? 0 : 1);
    }
}
