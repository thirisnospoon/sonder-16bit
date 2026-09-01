package sonder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Оболочка Sonder.
 *
 * <p>Всё, чего нет в доменном ядре: HTTP, сессии, база, проекции. Решения
 * принимает NODE-7 за последовательной линией (ADR-0011); здесь — загрузка
 * состояния, вызов и запись результата.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
