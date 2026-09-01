package sonder.shell.app;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Откуда брать соединение.
 *
 * <p>Свой интерфейс, а не {@code Supplier<Connection>}: получение
 * соединения бросает {@link SQLException}, и заворачивать её в
 * непроверяемую только ради стандартного типа значило бы прятать отказ
 * базы под видом дефекта кода.
 */
@FunctionalInterface
public interface ConnectionSource {
    Connection open() throws SQLException;
}
