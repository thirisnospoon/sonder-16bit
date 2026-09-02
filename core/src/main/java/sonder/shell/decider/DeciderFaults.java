package sonder.shell.decider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sonder.contract.ErrorCode;
import sonder.contract.decider.Decider;
import sonder.contract.decider.Decision;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Перевод отказов транспорта в решение, известное контракту.
 *
 * <p>Ядро живёт за гейтвеем и последовательной линией. Линия рвётся,
 * гейтвей перезапускается, нода думает дольше срока — всё это не дефекты
 * оболочки, а обычная жизнь распределённой системы, и контракт для неё
 * держит {@link ErrorCode#DECIDER_UNAVAILABLE}: категория UPSTREAM,
 * повторяемый, 502.
 *
 * <p><b>Почему любое исключение — именно этот код.</b> WSDL не объявляет
 * ни одного {@code wsdl:fault}: разбор конверта нода тоже сообщает
 * решением с кодом отказа, а не SOAP-фолтом. Значит, всё, что доходит
 * сюда исключением, — это транспорт, а не домен. Догадываться, какой
 * доменный код «имелся в виду», тут не из чего, и хорошо: догадка
 * означала бы решение, вынесенное оболочкой.
 *
 * <p><b>Почему динамический прокси, а не восемь методов.</b> Написанный
 * от руки декоратор придётся править при каждой новой операции, и
 * забытая операция полетит без обёртки — то есть уронит команду
 * исключением вместо отказа. Прокси покрывает интерфейс целиком по
 * построению, и забыть в нём нечего.
 *
 * <p>Метод, возвращающий не {@link Decision}, отказ пробрасывает.
 * У {@code ping} нет формы «не получилось»: выдумать её значило бы
 * ответить проверке здоровья, что всё хорошо.
 */
public final class DeciderFaults {

    private static final Logger log = LoggerFactory.getLogger(DeciderFaults.class);

    private DeciderFaults() {
    }

    /** Обёртка вокруг живого клиента. {@code where} попадает только в журнал. */
    public static Decider wrap(final Decider target, final String where) {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args)
                    throws Throwable {
                try {
                    return method.invoke(target, args);
                } catch (InvocationTargetException wrapped) {
                    Throwable cause = wrapped.getCause();
                    if (!Decision.class.equals(method.getReturnType())) {
                        throw cause;
                    }
                    // Подробность отказа — в журнал, а не клиенту: в ней
                    // адрес гейтвея и внутренности стека.
                    log.warn("ядро не ответило на {} ({}): {}",
                            method.getName(), where, cause.toString());
                    return unavailable();
                }
            }
        };

        return (Decider) Proxy.newProxyInstance(
                Decider.class.getClassLoader(),
                new Class<?>[]{Decider.class},
                handler);
    }

    private static Decision unavailable() {
        Decision d = new Decision();
        d.setAccepted(false);
        d.setErrorCode(ErrorCode.DECIDER_UNAVAILABLE.name());
        // Одна и та же строка при любой причине. Клиенту знать, что
        // именно сломалось внутри, не нужно и вредно; причина — в журнале.
        d.setErrorDetail("ядро не ответило");
        return d;
    }
}
