package sonder.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sonder.contract.decider.ActorContext;
import sonder.contract.decider.BanUserCommand;
import sonder.contract.decider.BanUserRequest;
import sonder.contract.decider.CommandMeta;
import sonder.contract.decider.CreateCommentCommand;
import sonder.contract.decider.CreateCommentRequest;
import sonder.contract.decider.CreatePostCommand;
import sonder.contract.decider.CreatePostRequest;
import sonder.contract.decider.DeletePostCommand;
import sonder.contract.decider.DeletePostRequest;
import sonder.contract.decider.FollowContext;
import sonder.contract.decider.FollowUserCommand;
import sonder.contract.decider.FollowUserRequest;
import sonder.contract.decider.NickContext;
import sonder.contract.decider.PingRequest;
import sonder.contract.decider.PostContext;
import sonder.contract.decider.PostStatus;
import sonder.contract.decider.RegisterUserCommand;
import sonder.contract.decider.RegisterUserRequest;
import sonder.contract.decider.Role;
import sonder.contract.decider.TargetUserContext;
import sonder.contract.decider.UnfollowUserCommand;
import sonder.contract.decider.UnfollowUserRequest;
import sonder.contract.decider.UserStatus;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.Marshaller;
import javax.xml.namespace.QName;
import java.io.File;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Эталонные конверты: то, что Java на самом деле кладёт в линию.
 *
 * <p>Смысл файлов не в самом тесте, а в том, что они ФИКСИРУЮТ БАЙТЫ. Обе
 * стороны contract-first порождаются из одного WSDL, но «порождаются из
 * одного» и «понимают друг друга» — разные утверждения, и первое не влечёт
 * второго. Разойтись они могут на порядке элементов, на префиксах, на том,
 * как JAXB решит записать перечисление.
 *
 * <p>Эталоны коммитятся и читаются тестом паскалевского ядра: оно
 * разбирает ровно эти байты своим tcsoap. Так вопрос «а поймут ли друг
 * друга?» перестаёт быть вопросом веры.
 *
 * <p><b>Эталон нужен КАЖДОЙ операции.</b> Долгое время он был один, на
 * CreatePost, а остальные семь операций держались на том самом допущении,
 * которое этот файл объявляет негодным. Проверка
 * {@link #everyOperationHasGolden()} закрывает дыру механически: операция,
 * добавленная в WSDL без эталона, красит сборку.
 *
 * <p>Если конверт изменился — тест падает, эталон надо перезаписать
 * осознанно, а не молча. Это ломающее изменение протокола, и выглядеть оно
 * должно как ломающее.
 */
class EnvelopeGoldenTest {

    private static final String SOAP_NS =
            "http://schemas.xmlsoap.org/soap/envelope/";
    private static final String DECIDER_NS = "urn:sonder:decider:v1";

    /** Эталоны лежат в contracts/generated: они такой же артефакт
     *  контракта, как сгенерированные типы, и живут рядом с ними. */
    private static final Path ENVELOPES =
            Paths.get("..", "contracts", "generated", "envelopes");

    /**
     * Образец конверта одной операции.
     *
     * <p>{@code fields} — сколько ЛИСТОВЫХ полей объявляет контракт для
     * этой операции: сумма полей меты, команды и каждого контекста.
     * Число проверяется с двух сторон независимо — здесь по разметке, в
     * tstgold по числу разобранных полей. Пропавшее из конверта поле
     * ядро получило бы нулевым и решило бы по неполным данным (R5), а
     * так оно роняет сборку.
     */
    private static final class Sample {
        final String file;
        final String element;
        final Class<?> type;
        final Object value;
        final int fields;

        Sample(String file, String element, Class<?> type, Object value, int fields) {
            this.file = file;
            this.element = element;
            this.type = type;
            this.value = value;
            this.fields = fields;
        }
    }

    private static CommandMeta meta(long millis) {
        CommandMeta m = new CommandMeta();
        m.setTraceId("t-1");
        m.setCommandId("c-1");
        // Заведомо больше 2^31: на шестнадцати битах это Int64, и
        // разбор такого числа — отдельный риск, а не формальность.
        m.setIssuedAtMillis(millis);
        return m;
    }

    private static ActorContext actor(Role role, int posts, int comments) {
        ActorContext a = new ActorContext();
        a.setUserId("u-andrey");
        a.setRole(role);
        a.setStatus(UserStatus.ACTIVE);
        a.setPostsLastHour(posts);
        a.setCommentsLastHour(comments);
        return a;
    }

    private static TargetUserContext target(UserStatus status, int version) {
        TargetUserContext t = new TargetUserContext();
        t.setExists(true);
        t.setUserId("u-boris");
        t.setRole(Role.USER);
        t.setStatus(status);
        t.setVersion(version);
        return t;
    }

    private static PostContext post(PostStatus status, int version) {
        PostContext p = new PostContext();
        p.setExists(true);
        p.setPostId("p-1001");
        p.setAuthorId("u-andrey");
        p.setStatus(status);
        p.setVersion(version);
        return p;
    }

    private static RegisterUserRequest registerUser() {
        RegisterUserCommand c = new RegisterUserCommand();
        c.setUserId("u-andrey");
        c.setNick("andrey");
        c.setDisplayName("Андрей & Ко");

        NickContext n = new NickContext();
        n.setTaken(false);

        RegisterUserRequest r = new RegisterUserRequest();
        r.setMeta(meta(1756684800000L));
        r.setCommand(c);
        r.setNick(n);
        return r;
    }

    private static CreatePostRequest createPost() {
        CreatePostCommand c = new CreatePostCommand();
        c.setPostId("p-1001");
        // Кириллица и амперсанд намеренно: первое проверяет кодировку,
        // второе — экранирование. И то и другое пишет пользователь.
        c.setBody("Первый пост & последний");

        CreatePostRequest r = new CreatePostRequest();
        r.setMeta(meta(1756684800000L));
        r.setCommand(c);
        r.setActor(actor(Role.USER, 0, 0));
        return r;
    }

    private static CreateCommentRequest createComment() {
        CreateCommentCommand c = new CreateCommentCommand();
        c.setCommentId("k-2002");
        c.setPostId("p-1001");
        c.setBody("Ответ < ответа");

        CreateCommentRequest r = new CreateCommentRequest();
        r.setMeta(meta(1756684800000L));
        r.setCommand(c);
        r.setActor(actor(Role.USER, 3, 7));
        r.setPost(post(PostStatus.VISIBLE, 4));
        return r;
    }

    private static DeletePostRequest deletePost() {
        DeletePostCommand c = new DeletePostCommand();
        c.setPostId("p-1001");

        DeletePostRequest r = new DeletePostRequest();
        r.setMeta(meta(1756684800000L));
        r.setCommand(c);
        r.setActor(actor(Role.MODERATOR, 0, 0));
        r.setPost(post(PostStatus.VISIBLE, 9));
        return r;
    }

    private static FollowUserRequest followUser() {
        FollowUserCommand c = new FollowUserCommand();
        c.setTargetUserId("u-boris");

        FollowContext f = new FollowContext();
        f.setAlreadyFollowing(false);

        FollowUserRequest r = new FollowUserRequest();
        r.setMeta(meta(1756684800000L));
        r.setCommand(c);
        r.setActor(actor(Role.USER, 1, 2));
        r.setTarget(target(UserStatus.ACTIVE, 5));
        r.setFollow(f);
        return r;
    }

    private static UnfollowUserRequest unfollowUser() {
        UnfollowUserCommand c = new UnfollowUserCommand();
        c.setTargetUserId("u-boris");

        FollowContext f = new FollowContext();
        // Зеркало подписки: там ребра ещё нет, здесь оно есть.
        f.setAlreadyFollowing(true);

        UnfollowUserRequest r = new UnfollowUserRequest();
        r.setMeta(meta(1756684800000L));
        r.setCommand(c);
        r.setActor(actor(Role.USER, 0, 11));
        r.setTarget(target(UserStatus.ACTIVE, 6));
        r.setFollow(f);
        return r;
    }

    private static BanUserRequest banUser() {
        BanUserCommand c = new BanUserCommand();
        c.setTargetUserId("u-boris");
        c.setReason("Спам & брань");

        BanUserRequest r = new BanUserRequest();
        r.setMeta(meta(1756684800000L));
        r.setCommand(c);
        r.setActor(actor(Role.MODERATOR, 0, 0));
        r.setTarget(target(UserStatus.ACTIVE, 12));
        return r;
    }

    private static PingRequest ping() {
        PingRequest r = new PingRequest();
        r.setNonce(20260901);
        return r;
    }

    /**
     * Все операции контракта. Порядок — как в WSDL.
     *
     * <p>Ping стоит особняком: у него нет ни меты, ни контекстов, поле
     * лежит прямо под операцией. Конверт получается на уровень мельче
     * остальных, и это ровно та форма, на которой разборщик мог бы
     * сломаться, если бы считал группу обязательной.
     */
    private static List<Sample> samples() {
        List<Sample> all = new ArrayList<>();
        all.add(new Sample("register-user.xml", "RegisterUserRequest",
                RegisterUserRequest.class, registerUser(), 7));
        all.add(new Sample("create-post.xml", "CreatePostRequest",
                CreatePostRequest.class, createPost(), 10));
        all.add(new Sample("create-comment.xml", "CreateCommentRequest",
                CreateCommentRequest.class, createComment(), 16));
        all.add(new Sample("delete-post.xml", "DeletePostRequest",
                DeletePostRequest.class, deletePost(), 14));
        all.add(new Sample("follow-user.xml", "FollowUserRequest",
                FollowUserRequest.class, followUser(), 15));
        all.add(new Sample("unfollow-user.xml", "UnfollowUserRequest",
                UnfollowUserRequest.class, unfollowUser(), 15));
        all.add(new Sample("ban-user.xml", "BanUserRequest",
                BanUserRequest.class, banUser(), 15));
        all.add(new Sample("ping.xml", "PingRequest",
                PingRequest.class, ping(), 1));
        return all;
    }

    /**
     * Конверт собирается вручную вокруг тела, как это делает гейтвей.
     *
     * <p>Гейтвей терминирует SOAP и переупаковывает вызов в кадры линии
     * (ADR-0007), поэтому конверт для ноды собирает именно он, а не
     * транспорт CXF. Тело при этом маршалит тот же JAXB, что и в бою, — а
     * именно тело и содержит всё, на чём стороны могут разойтись.
     */
    private static String marshalEnvelope(Sample s) throws Exception {
        JAXBContext ctx = JAXBContext.newInstance(s.type);
        Marshaller m = ctx.createMarshaller();
        m.setProperty(Marshaller.JAXB_FRAGMENT, Boolean.TRUE);
        m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.FALSE);

        StringWriter body = new StringWriter();
        @SuppressWarnings({"unchecked", "rawtypes"})
        JAXBElement<?> el = new JAXBElement(
                new QName(DECIDER_NS, s.element), s.type, s.value);
        m.marshal(el, body);

        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<soap:Envelope xmlns:soap=\"" + SOAP_NS + "\">"
                + "<soap:Body>"
                + body
                + "</soap:Body>"
                + "</soap:Envelope>";
    }

    /**
     * Механическая проверка покрытия: у каждой операции WSDL есть эталон.
     *
     * <p>Без неё дыра затягивается сама собой — операцию добавляют, а
     * эталон «потом». Так было семь раз подряд, и обнаружилось это не
     * тестом, а чтением.
     */
    @Test
    @DisplayName("у каждой операции контракта есть эталонный конверт")
    void everyOperationHasGolden() throws Exception {
        JsonNode manifest = new ObjectMapper().readTree(
                new File("../contracts/generated/operations.json"));

        TreeSet<String> declared = new TreeSet<>();
        for (JsonNode op : manifest.get("operations")) {
            declared.add(op.get("request").asText());
        }
        assertFalse(declared.isEmpty(),
                "манифест не объявил ни одной операции — проверка была бы пустой");

        TreeSet<String> covered = new TreeSet<>();
        for (Sample s : samples()) {
            covered.add(s.element);
        }

        TreeSet<String> missing = new TreeSet<>(declared);
        missing.removeAll(covered);
        assertTrue(missing.isEmpty(),
                "у операции нет эталонного конверта: " + missing
                        + ". Значит, никто не проверял, что ядро на Pascal "
                        + "разбирает то, что шлёт Java");

        TreeSet<String> stale = new TreeSet<>(covered);
        stale.removeAll(declared);
        assertTrue(stale.isEmpty(),
                "эталон есть, а операции в контракте нет: " + stale);
    }

    @Test
    @DisplayName("конверты совпадают с зафиксированными эталонами")
    void envelopesMatchGolden() throws Exception {
        Map<String, String> written = new LinkedHashMap<>();

        for (Sample s : samples()) {
            String actual = marshalEnvelope(s);
            Path golden = ENVELOPES.resolve(s.file);

            if (!Files.exists(golden)) {
                Files.createDirectories(ENVELOPES);
                Files.write(golden, actual.getBytes(StandardCharsets.UTF_8));
                written.put(s.file, golden.toAbsolutePath().toString());
                continue;
            }

            String expected =
                    new String(Files.readAllBytes(golden), StandardCharsets.UTF_8)
                            .replace("\r\n", "\n").trim();

            assertEquals(expected, actual.trim(),
                    "конверт " + s.element + " разошёлся с эталоном. Это ломающее "
                            + "изменение протокола: ядро на Pascal разбирает ИМЕННО "
                            + "эти байты. Перезаписать эталон можно, но осознанно");
        }

        assertTrue(written.isEmpty(),
                "эталонов не было, они записаны: " + written.keySet()
                        + " — проверь содержимое и закоммить");
    }

    /**
     * Свойства, за которые отвечает не эталон, а разбор на той стороне.
     * Проверяются отдельно, чтобы падение говорило, ЧТО именно сломалось.
     */
    @Test
    @DisplayName("конверты по силам разборщику ядра")
    void envelopesFitCoreParser() throws Exception {
        for (Sample s : samples()) {
            String xml = marshalEnvelope(s);
            String who = " (" + s.element + ")";

            assertTrue(xml.startsWith("<?xml"), "нет объявления" + who);
            assertTrue(xml.contains("<soap:Envelope"), "нет конверта" + who);
            assertTrue(xml.contains("<soap:Body>"), "нет тела" + who);
            assertTrue(xml.contains("<" + s.element), "нет элемента операции" + who);

            // DTD ядро отвергает намеренно, и его тут быть не должно.
            assertFalse(xml.contains("<!DOCTYPE"),
                    "в конверте DTD, ядро его отвергнет" + who);

            // Глубина: Envelope, Body, операция, группа, поле — не больше
            // пяти. Разборщик ядра глубже не заходит, и это его контракт.
            assertTrue(maxDepth(xml) <= 5,
                    "конверт глубже пяти уровней — разборщик ядра его отвергнет"
                            + who + ", глубина " + maxDepth(xml));

            // Столько полей, сколько объявил контракт. Меньше — значит
            // JAXB что-то не выписал, и ядро получит это поле нулевым.
            assertEquals(s.fields, leafCount(xml),
                    "в конверте не столько полей, сколько объявляет контракт"
                            + who + ". Недостающее приедет в ядро нулевым (R5)");
        }
    }

    /**
     * Экранирование. Проверяется на конкретных конвертах, а не «хоть
     * где-нибудь»: символ пишет пользователь, и неэкранированным он
     * сделал бы конверт неразбираемым.
     */
    @Test
    @DisplayName("опасные символы экранированы в теле и в причине")
    void dangerousCharactersAreEscaped() throws Exception {
        Map<String, String> byFile = new LinkedHashMap<>();
        for (Sample s : samples()) {
            byFile.put(s.file, marshalEnvelope(s));
        }

        assertTrue(byFile.get("create-post.xml").contains("Первый пост &amp; последний"),
                "амперсанд в теле поста не экранирован");
        assertTrue(byFile.get("create-comment.xml").contains("Ответ &lt; ответа"),
                "угловая скобка в теле комментария не экранирована");
        assertTrue(byFile.get("ban-user.xml").contains("Спам &amp; брань"),
                "амперсанд в причине блокировки не экранирован");
        assertTrue(byFile.get("register-user.xml").contains("Андрей &amp; Ко"),
                "амперсанд в отображаемом имени не экранирован");
    }

    /** Наибольшая глубина вложенности элементов. */
    private static int maxDepth(String xml) {
        int depth = 0;
        int max = 0;
        int i = 0;
        while (i < xml.length()) {
            int lt = xml.indexOf('<', i);
            if (lt < 0) {
                break;
            }
            int gt = xml.indexOf('>', lt);
            if (gt < 0) {
                break;
            }
            String tag = xml.substring(lt + 1, gt);
            if (!tag.startsWith("?") && !tag.startsWith("!")) {
                if (tag.startsWith("/")) {
                    depth--;
                } else if (tag.endsWith("/")) {
                    max = Math.max(max, depth + 1);
                } else {
                    depth++;
                    max = Math.max(max, depth);
                }
            }
            i = gt + 1;
        }
        return max;
    }

    /**
     * Число листовых элементов: тех, внутри которых нет других элементов.
     * Это и есть поля, которые доедут до ядра значениями.
     */
    private static int leafCount(String xml) {
        int leaves = 0;
        String open = null;
        int i = 0;
        while (i < xml.length()) {
            int lt = xml.indexOf('<', i);
            if (lt < 0) {
                break;
            }
            int gt = xml.indexOf('>', lt);
            if (gt < 0) {
                break;
            }
            String tag = xml.substring(lt + 1, gt);
            if (tag.startsWith("?") || tag.startsWith("!")) {
                i = gt + 1;
                continue;
            }
            if (tag.endsWith("/")) {
                // Пустой элемент — тоже лист, но значения в нём нет.
                leaves++;
                open = null;
            } else if (tag.startsWith("/")) {
                // Закрылся тот же элемент, что открылся, — значит внутри
                // элементов не было.
                if (open != null && tag.substring(1).equals(open)) {
                    leaves++;
                }
                open = null;
            } else {
                open = tag;
            }
            i = gt + 1;
        }
        return leaves;
    }
}
