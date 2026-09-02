package sonder.shell.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Чтение полезной нагрузки события.
 *
 * <p><b>Пишется вручную, читается библиотекой, и это не непоследовательность.</b>
 * Запись собирает объект известной формы — плоские пары «ключ-значение»
 * прямо из решения ядра, — и тянуть ради этого объектную модель значило
 * бы придумать структуру, которой в контракте нет. Чтение же разбирает
 * строку, в которой лежат экранированные кавычки, переводы строк и
 * шестнадцатеричные последовательности, пришедшие из тела поста, то есть
 * от пользователя. Свой разборщик такого — это свой разборщик JSON, а
 * писать его при готовом в зависимостях незачем.
 *
 * <p>Отсутствующее поле — это {@code null}, а не пустая строка. Разница
 * важна: «поля нет» означает событие не той формы, и потребитель обязан
 * отказаться, а не подставить пустоту и разложить пост в ленту никому.
 */
public final class Payloads {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Payloads() {
    }

    /**
     * Значение поля или {@code null}, если поля нет.
     *
     * @throws IOException если нагрузка вообще не JSON — это дефект
     *                     записи, и молчать о нём нельзя
     */
    public static String field(String payload, String key) throws IOException {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        JsonNode node = MAPPER.readTree(payload).get(key);
        return node == null || node.isNull() ? null : node.asText();
    }
}
