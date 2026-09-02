package sonder.rest;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Маршруты REST против контракта OpenAPI.
 *
 * <p>Контракт — источник правды и для веба, который по нему порождает
 * клиента. Маршрут, которого нет в контракте, клиенту недоступен и потому
 * бесполезен; операция, объявленная в контракте и не реализованная, — это
 * обещание, которого никто не сдержит, и узнают о нём по 404 в проде.
 *
 * <p><b>Список нереализованного лежит здесь, в коде, и это намеренно.</b>
 * Проверка «реализовано ∪ отложено = объявлено» строгая с обеих сторон:
 * новая операция в контракте красит сборку, пока её не реализуют или явно
 * не отложат; реализованная и забытая в списке отложенных — тоже. Список,
 * который нельзя не заметить, честнее счётчика покрытия.
 *
 * <p>Маршруты читаются отражением по аннотациям, а не через поднятый
 * Spring: проверке не нужна ни база, ни сеть, и она обязана оставаться
 * быстрой, иначе её начнут пропускать.
 */
class RestContractTest {

    /**
     * Объявлено в контракте, но ещё не реализовано.
     *
     * <p>Каждая строка — обещание, за которое кто-то отвечает. Пустой
     * список означает, что оболочка сдержала контракт целиком.
     */
    private static final Set<String> PENDING = new LinkedHashSet<>(java.util.Arrays.asList(
            // subscribe ждёт SSE: сама лента уже строится проекцией из
            // событий, а вот доставка изменений в открытое соединение —
            // отдельная работа фазы 7.
            "subscribe"));

    /** Пара «метод, путь» в том виде, в каком её объявляют обе стороны. */
    private static String route(String method, String path) {
        return method.toUpperCase() + " " + path;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> openapi() throws Exception {
        try (InputStream in = new FileInputStream("../contracts/openapi/social-v1.yaml")) {
            return (Map<String, Object>) new Yaml().load(in);
        }
    }

    /** Объявленные контрактом маршруты: operationId → «МЕТОД путь». */
    @SuppressWarnings("unchecked")
    private static Map<String, String> declaredRoutes() throws Exception {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        Map<String, Object> paths = (Map<String, Object>) openapi().get("paths");
        for (Map.Entry<String, Object> path : paths.entrySet()) {
            Map<String, Object> ops = (Map<String, Object>) path.getValue();
            for (Map.Entry<String, Object> op : ops.entrySet()) {
                if (!(op.getValue() instanceof Map)) {
                    continue;
                }
                Map<String, Object> spec = (Map<String, Object>) op.getValue();
                Object id = spec.get("operationId");
                if (id != null) {
                    out.put(id.toString(), route(op.getKey(), path.getKey()));
                }
            }
        }
        return out;
    }

    /** Маршруты, реализованные контроллерами. */
    private static Set<String> implementedRoutes() {
        JavaClasses controllers = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("sonder.shell.rest");

        Set<String> routes = new TreeSet<>();
        for (com.tngtech.archunit.core.domain.JavaClass type : controllers) {
            for (JavaMethod method : type.getMethods()) {
                collect(routes, method, "GET",
                        org.springframework.web.bind.annotation.GetMapping.class);
                collect(routes, method, "POST",
                        org.springframework.web.bind.annotation.PostMapping.class);
                collect(routes, method, "PUT",
                        org.springframework.web.bind.annotation.PutMapping.class);
                collect(routes, method, "DELETE",
                        org.springframework.web.bind.annotation.DeleteMapping.class);
            }
        }
        return routes;
    }

    private static void collect(Set<String> routes, JavaMethod method,
                                String verb, Class<? extends java.lang.annotation.Annotation> ann) {
        if (!method.isAnnotatedWith(ann)) {
            return;
        }
        Object value = method.getAnnotationOfType(ann);
        String[] paths = pathsOf(value);
        for (String p : paths) {
            routes.add(route(verb, p));
        }
    }

    private static String[] pathsOf(Object annotation) {
        try {
            Object value = annotation.getClass().getMethod("value").invoke(annotation);
            return (String[]) value;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("не прочитать путь из аннотации", e);
        }
    }

    @Test
    @DisplayName("контракт объявляет маршруты, и их есть что проверять")
    void contractIsNotEmpty() throws Exception {
        assertFalse(declaredRoutes().isEmpty(),
                "в OpenAPI нет ни одной операции — проверка была бы пустой");
    }

    /**
     * Строгая проверка с обеих сторон: реализовано ∪ отложено = объявлено.
     */
    @Test
    @DisplayName("реализованное и отложенное вместе покрывают контракт")
    void implementedPlusPendingCoversContract() throws Exception {
        Map<String, String> declared = declaredRoutes();
        Set<String> implemented = implementedRoutes();

        Set<String> declaredNotImplemented = new TreeSet<>();
        Set<String> implementedIds = new TreeSet<>();
        for (Map.Entry<String, String> e : declared.entrySet()) {
            if (implemented.contains(e.getValue())) {
                implementedIds.add(e.getKey());
            } else {
                declaredNotImplemented.add(e.getKey());
            }
        }

        assertEquals(new TreeSet<>(PENDING), declaredNotImplemented,
                "список отложенного разошёлся с действительностью. Если "
                        + "операцию реализовали — уберите её из PENDING; если "
                        + "в контракте появилась новая — реализуйте или "
                        + "внесите в PENDING осознанно");

        assertFalse(implementedIds.isEmpty(),
                "не реализовано ни одной операции — проверка вырождена");
    }

    /**
     * Маршрут, которого нет в контракте, недоступен клиенту: веб порождает
     * клиента из OpenAPI и о таком маршруте не узнает.
     */
    @Test
    @DisplayName("нет маршрутов, которых не объявляет контракт")
    void noUndeclaredRoutes() throws Exception {
        Set<String> declared = new TreeSet<>(declaredRoutes().values());
        Set<String> orphans = new TreeSet<>(implementedRoutes());
        orphans.removeAll(declared);

        assertTrue(orphans.isEmpty(),
                "маршруты вне контракта: " + orphans
                        + ". Клиент порождается из OpenAPI и о них не узнает");
    }

    /**
     * Список отложенного сокращается, а не растёт. Проверка слабая — она
     * не знает, что было вчера, — но зафиксированное здесь число делает
     * рост заметным на ревью.
     */
    @Test
    @DisplayName("отложенного не больше, чем было зафиксировано")
    void pendingDoesNotGrow() {
        assertTrue(PENDING.size() <= 1,
                "список отложенного вырос до " + PENDING.size()
                        + ": контракт обещает больше, чем оболочка делает");
    }
}
