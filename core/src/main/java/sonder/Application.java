package sonder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Оболочка Sonder.
 *
 * <p>Всё, чего нет в доменном ядре: HTTP, сессии, база, проекции. Решения
 * принимает NODE-7 за последовательной линией (ADR-0011); здесь — загрузка
 * состояния, вызов и запись результата.
 */
@SpringBootApplication
// Расписание — свойство приложения, а не очереди. Пока оно включалось
// в конфигурации outbox, выключение дренажа заодно гасило и удары
// сердца в открытых соединениях, к очереди отношения не имеющие.
@EnableScheduling
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
