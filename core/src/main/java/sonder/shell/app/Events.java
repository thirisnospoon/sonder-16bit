package sonder.shell.app;

import sonder.contract.decider.DomainEvent;
import sonder.contract.decider.EventField;

/**
 * Полезная нагрузка события в JSON.
 *
 * <p>Собирается вручную, а не библиотекой сериализации: поля приходят от
 * ядра парами «ключ, значение», и это ровно та форма, в которой они лежат
 * в контракте. Тянуть сюда объектную модель значило бы придумать
 * структуру, которой в решении нет, и потом гадать, откуда она взялась.
 *
 * <p>Экранирование поэтому тоже своё — и оно обязано быть полным.
 * Значение поля приходит из тела поста, то есть от пользователя, и
 * неэкранированная кавычка сделала бы строку в outbox неразбираемой, а
 * событие — потерянным.
 */
final class Events {

    private Events() {
    }

    static String payloadOf(DomainEvent event) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (EventField field : event.getField()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(field.getKey())).append("\":\"")
              .append(escape(field.getValue())).append('"');
        }
        return sb.append('}').toString();
    }

    static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    // Управляющие символы обязаны уйти в шестнадцатеричную
                    // форму: JSON их в строке не допускает, а прийти они
                    // могут — тело поста пишет пользователь.
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        return sb.toString();
    }
}
