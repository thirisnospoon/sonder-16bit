package sonder.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.yaml.snakeyaml.Yaml;
import sonder.Application;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Доступ гостя — против того, что объявляет контракт.
 *
 * <p>КОНТРАКТ ДОЛГО МОЛЧАЛ О ДОСТУПЕ ВООБЩЕ: ни схемы аутентификации, ни
 * 401 в ответах. Оболочка при этом отвечала гостю отказом на всё, кроме
 * входа, выхода и регистрации, — то есть решала сама, а контракт об этом
 * решении не знал. Клиент, порождённый по такому контракту, не знает,
 * какие операции требуют сессии, и узнаёт это отказом: страница
 * спрашивала личную ленту у гостя и рисовала ему «Нужно войти заново» на
 * первом экране.
 *
 * <p>Сверка маршрутов такого не ловит по устройству — она смотрит пути и
 * методы. Ровно на этом же держалось расхождение по входу: контракт
 * требовал 204 и куку, оболочка отдавала 200 с токеном в теле, и жило
 * это до первого подъёма всей системы.
 *
 * <p>Поэтому проверка ЧИТАЕТ КОНТРАКТ и идёт по нему: каждая операция
 * вызывается без сессии, и ответ обязан совпасть с объявленным. Список
 * операций не переписывается сюда руками — переписанный, он разошёлся
 * бы с контрактом молча, а молчаливое расхождение и есть то, от чего
 * проверка защищает.
 */
@SpringBootTest(
        classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GuestAccessIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> System.getProperty("sonder.it.jdbcUrl", ""));
        registry.add("spring.datasource.username",
                () -> System.getProperty("sonder.it.user", "sysdba"));
        registry.add("spring.datasource.password",
                () -> System.getProperty("sonder.it.password", "masterkey"));
        registry.add("sonder.outbox.enabled", () -> "false");
    }

    @Autowired
    private TestRestTemplate http;

    @BeforeEach
    void требуетсяБаза() {
        assumeTrue(!System.getProperty("sonder.it.jdbcUrl", "").isEmpty(),
                "нет sonder.it.jdbcUrl — запускать через ./sonder java-it");
        безПотоковойОтправки();
    }

    /**
     * Тело запроса отправляется целиком, а не потоком.
     *
     * <p>{@code HttpURLConnection} под капотом умеет отвечать на 401
     * повторной отправкой — и не умеет, когда тело уже утекло в поток:
     * получается {@code HttpRetryException} вместо ответа. Проверка,
     * которая ЖДЁТ 401 на POST с телом, напарывается на это первой же
     * операцией. Не свойство системы, а свойство клиента, и лечится
     * здесь же.
     */
    private void безПотоковойОтправки() {
        SimpleClientHttpRequestFactory фабрика = new SimpleClientHttpRequestFactory();
        фабрика.setOutputStreaming(false);
        http.getRestTemplate().setRequestFactory(фабрика);
    }

    /** Одна операция контракта в том виде, в каком её надо позвать. */
    private static final class Операция {
        final String id;
        final HttpMethod метод;
        final String путь;
        final boolean закрыта;

        Операция(String id, HttpMethod метод, String путь, boolean закрыта) {
            this.id = id;
            this.метод = метод;
            this.путь = путь;
            this.закрыта = закрыта;
        }

        @Override
        public String toString() {
            return метод + " " + путь + " (" + id + ")";
        }
    }

    /**
     * Операции из контракта с их требованием доступа.
     *
     * <p>Требование по умолчанию стоит на верхнем уровне документа;
     * операция может отменить его своим {@code security: []}. Читается
     * именно так, а не «есть ли 401 в ответах»: 401 бывает и у открытой
     * операции — вход отвечает им на неверный пароль.
     */
    @SuppressWarnings("unchecked")
    private static List<Операция> операцииКонтракта() throws Exception {
        Map<String, Object> документ;
        try (InputStream in =
                     new FileInputStream("../contracts/openapi/social-v1.yaml")) {
            документ = (Map<String, Object>) new Yaml().load(in);
        }
        boolean поУмолчаниюЗакрыто = !((List<Object>) документ
                .getOrDefault("security", new ArrayList<>())).isEmpty();

        List<Операция> из = new ArrayList<>();
        Map<String, Object> пути = (Map<String, Object>) документ.get("paths");
        for (Map.Entry<String, Object> путь : пути.entrySet()) {
            Map<String, Object> операции = (Map<String, Object>) путь.getValue();
            for (Map.Entry<String, Object> о : операции.entrySet()) {
                if (!(о.getValue() instanceof Map)) {
                    continue;
                }
                Map<String, Object> спец = (Map<String, Object>) о.getValue();
                Object id = спец.get("operationId");
                if (id == null) {
                    continue;
                }
                boolean закрыта = поУмолчаниюЗакрыто;
                if (спец.containsKey("security")) {
                    закрыта = !((List<Object>) спец.get("security")).isEmpty();
                }
                из.add(new Операция(
                        id.toString(),
                        HttpMethod.valueOf(о.getKey().toUpperCase()),
                        путь.getKey(),
                        закрыта));
            }
        }
        return из;
    }

    /**
     * Подстановка в путь.
     *
     * <p>Значения заведомо несуществующие, и это осознанно: проверяется
     * ОТКАЗ ПО ДОСТУПУ, а он обязан наступать раньше поиска. Система,
     * отвечающая гостю «нет такого поста», уже рассказала ему, чего в
     * ней нет.
     */
    private static String подставить(String шаблон) {
        return шаблон
                .replace("{nick}", "никого")
                .replace("{postId}", "00000000-0000-0000-0000-000000000000");
    }

    /** Тело для операций, которые без него не дойдут до проверки доступа. */
    private static Object тело(String id) {
        Map<String, Object> м = new LinkedHashMap<>();
        switch (id) {
            case "login":
                м.put("nick", "никого");
                м.put("password", "достаточно-длинный-пароль");
                return м;
            case "register":
                м.put("nick", "guestprobe");
                м.put("displayName", "Гость");
                м.put("password", "достаточно-длинный-пароль");
                return м;
            case "createPost":
                м.put("body", "текст");
                return м;
            case "banUser":
                м.put("reason", "причина");
                return м;
            default:
                return null;
        }
    }

    private ResponseEntity<String> безСессии(Операция о) {
        HttpHeaders заголовки = new HttpHeaders();
        заголовки.setContentType(MediaType.APPLICATION_JSON);
        Object т = тело(о.id);
        HttpEntity<Object> запрос = new HttpEntity<>(т, заголовки);
        return http.exchange(подставить(о.путь), о.метод, запрос, String.class);
    }

    @Test
    @DisplayName("контракт объявляет и закрытые операции, и открытые")
    void контрактРазличаетДоступ() throws Exception {
        List<Операция> все = операцииКонтракта();
        assertFalse(все.isEmpty(), "в контракте нет операций — проверять нечего");

        long закрытых = все.stream().filter(о -> о.закрыта).count();
        long открытых = все.size() - закрытых;

        // Проверка вырождается, если стороны сойдутся тривиально: всё
        // закрыто или всё открыто. Тогда она подтверждает не согласие
        // контракта с оболочкой, а собственную бессодержательность.
        assertTrue(закрытых > 0, "ни одной закрытой операции: "
                + "требование доступа в контракте потерялось");
        assertTrue(открытых > 0, "ни одной открытой операции: "
                + "войти в систему было бы нечем");
    }

    @Test
    @DisplayName("закрытые операции отвечают гостю 401, и ровно они")
    void гостьВидитТоЧтоОбъявлено() throws Exception {
        List<String> расхождения = new ArrayList<>();

        for (Операция о : операцииКонтракта()) {
            ResponseEntity<String> ответ = безСессии(о);
            boolean отказано = ответ.getStatusCodeValue() == 401;

            if (о.закрыта && !отказано) {
                расхождения.add(о + ": контракт требует сессии, "
                        + "оболочка ответила " + ответ.getStatusCodeValue()
                        + " — операция открыта тому, кому не должна");
            }
            if (!о.закрыта && отказано && !"login".equals(о.id)) {
                // Вход — исключение и единственное: 401 у него означает
                // неверный пароль, а не отсутствие сессии.
                расхождения.add(о + ": контракт объявляет операцию открытой, "
                        + "оболочка ответила 401 — войти в систему нечем");
            }
        }

        assertEquals(new TreeSet<String>(), new TreeSet<>(расхождения),
                "контракт и оболочка расходятся в доступе");
    }

    /**
     * Отказ по доступу наступает РАНЬШЕ поиска.
     *
     * <p>Иначе система отвечает гостю «нет такого поста» — и этим
     * рассказывает, чего в ней нет. Перебором таких ответов чужой
     * узнаёт, какие идентификаторы существуют, не имея права видеть ни
     * один из них.
     */
    @Test
    @DisplayName("гостю отказывают по сессии, а не по отсутствию записи")
    void отказПоДоступуРаньшеПоиска() throws Exception {
        for (Операция о : операцииКонтракта()) {
            if (!о.закрыта || !о.путь.contains("{")) {
                continue;
            }
            ResponseEntity<String> ответ = безСессии(о);
            assertEquals(401, ответ.getStatusCodeValue(),
                    о + ": ответ гостю на несуществующую запись обязан быть "
                            + "401, а не 404 — иначе отказ выдаёт содержимое");
        }
    }
}
