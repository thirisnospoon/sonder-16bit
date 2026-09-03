package sonder.shell.rest;

import javax.servlet.http.HttpServletResponse;

/**
 * Кука сессии.
 *
 * <p><b>Кука, а не токен в теле ответа, и это требование контракта с
 * объяснением.</b> {@code social-v1.yaml} говорит прямо: «Сессия выдаётся
 * кукой HttpOnly; Secure; SameSite=Strict. Токенов в localStorage нет: их
 * читает любой скрипт на странице». Токен, отданный в теле, клиенту
 * приходится где-то держать, и всякое такое место читается сценарием,
 * попавшим на страницу. Кука с {@code HttpOnly} не читается ничем.
 *
 * <p>Оболочка отвечала токеном в теле, то есть делала ровно то, что
 * контракт запрещает, и держалось это до первого настоящего подъёма
 * системы: проверка маршрутов сверяет пути и методы, а не то, чем
 * оканчивается вход.
 *
 * <p><b>Заголовок собирается руками.</b> {@code javax.servlet.http.Cookie}
 * из Servlet 3.1 не знает атрибута {@code SameSite}, а он здесь и есть
 * главная защита: с {@code Strict} браузер не приложит куку к запросу,
 * пришедшему с чужой страницы, и подделка межсайтового запроса
 * закрывается без единого токена в разметке.
 */
public final class SessionCookie {

    /** Имя куки. Одно на всё приложение и на веб. */
    public static final String NAME = "sonder_session";

    private SessionCookie() {
    }

    /**
     * Выдать сессию.
     *
     * @param secure отдавать ли куку только по HTTPS. По умолчанию да;
     *               выключается на локальном подъёме по обычному HTTP —
     *               браузер иначе просто выбросит куку, и вход не
     *               заработает вовсе. Признак вынесен в настройку
     *               намеренно: незаметно выключенный {@code Secure}
     *               однажды уехал бы в бой
     * @param maxAge сколько кука живёт, в секундах
     */
    public static void issue(
            HttpServletResponse response,
            String token,
            boolean secure,
            long maxAge) {

        response.addHeader("Set-Cookie", build(token, secure, maxAge));
    }

    /** Снять сессию: та же кука с нулевым сроком. */
    public static void clear(HttpServletResponse response, boolean secure) {
        response.addHeader("Set-Cookie", build("", secure, 0));
    }

    private static String build(String token, boolean secure, long maxAge) {
        StringBuilder header = new StringBuilder();
        header.append(NAME).append('=').append(token);
        header.append("; Path=/");
        header.append("; Max-Age=").append(maxAge);
        header.append("; HttpOnly");
        header.append("; SameSite=Strict");
        if (secure) {
            header.append("; Secure");
        }
        return header.toString();
    }
}
