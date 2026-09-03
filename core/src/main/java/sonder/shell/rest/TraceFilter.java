package sonder.shell.rest;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Заводит идентификатор трассировки на каждый запрос.
 *
 * <p>ПЕРВЫМ В ЦЕПОЧКЕ, и это не вкусовщина: строка лога, написанная до
 * фильтра, окажется без идентификатора — а именно такие строки и пишет
 * то, что падает раньше обработчика. Лог без следа там, где всё в
 * порядке, бесполезен; лог без следа там, где сломалось, вреден.
 *
 * <p>ЗАГОЛОВОК СТАВИТСЯ СРАЗУ, до передачи дальше. Поставить его после
 * нельзя: к тому времени ответ уже мог уйти — поток событий отдаёт
 * заголовки и держит соединение часами, — и заголовок молча пропал бы
 * ровно у того запроса, который дольше всех живёт.
 *
 * <p>ИДЕНТИФИКАТОР ВЫЗЫВАЮЩЕГО НЕ ПРИНИМАЕТСЯ. Заголовок из запроса
 * пришёл бы снаружи, то есть от кого угодно: им можно склеить свои
 * записи с чужими или засорить поиск любым значением. Доверять чужому
 * идентификатору можно только внутри своего периметра, а этот фильтр
 * стоит на публичной границе.
 *
 * <p>MDC ОБЯЗАТЕЛЬНО СНИМАЕТСЯ. Пул потоков переиспользует поток, и
 * оставленное значение досталось бы следующему запросу — тот писал бы
 * в лог ЧУЖОЙ идентификатор. Такой дефект не ломает ничего видимого и
 * обнаруживается только тогда, когда по следу приходят разбираться.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(TraceFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {
        String traceId = Trace.create();
        MDC.put(Trace.MDC_KEY, traceId);
        long started = System.nanoTime();
        try {
            if (response instanceof HttpServletResponse) {
                ((HttpServletResponse) response).setHeader(Trace.HEADER, traceId);
            }
            chain.doFilter(request, response);
        } finally {
            record(request, response, started);
            MDC.remove(Trace.MDC_KEY);
        }
    }

    /**
     * Строка о самом запросе.
     *
     * <p>БЕЗ НЕЁ СЛЕДУ НЕ НА ЧЁМ ДЕРЖАТЬСЯ. Идентификатор в MDC
     * украшает строки лога — но успешный запрос не пишет ни одной:
     * обработчик молча делает своё дело и возвращает 201. Искать такой
     * запрос по следу было бы не в чем, и «трассировка есть» означало
     * бы «трассировка есть у ошибок».
     *
     * <p>Пишется в {@code finally}: запрос, оборвавшийся исключением,
     * интереснее удавшегося, и потерять именно его было бы обиднее
     * всего.
     *
     * <p>Строка запроса БЕЗ ПАРАМЕТРОВ. В них уезжают курсоры и ники, а
     * лог — это то, что копируют в переписку и складывают в общий
     * сборщик; чем меньше в нём чужих данных, тем лучше. Метод, путь и
     * код отвечают на вопрос «что это было», а подробности — дело
     * следа.
     */
    private static void record(ServletRequest request, ServletResponse response,
                               long started) {
        if (!(request instanceof HttpServletRequest)) {
            return;
        }
        HttpServletRequest http = (HttpServletRequest) request;
        int status = response instanceof HttpServletResponse
                ? ((HttpServletResponse) response).getStatus()
                : 0;
        long millis = (System.nanoTime() - started) / 1_000_000L;
        log.info("{} {} -> {} за {} мс",
                http.getMethod(), http.getRequestURI(), status, millis);
    }
}
