package sonder.shell.fixture;

import sonder.contract.ErrorCode;

/**
 * НАМЕРЕННЫЙ НАРУШИТЕЛЬ. Не используется нигде, кроме самопроверки правил.
 *
 * <p>Он существует затем, что правило, которому нечего ловить, зелено
 * всегда и не значит ничего. Здесь оболочка сама решает, что ник занят, —
 * ровно то, что запрещено ADR-0011 и что должно ломать сборку.
 *
 * <p>Лежит в тестовых исходниках, поэтому в производственный скан
 * (DO_NOT_INCLUDE_TESTS) не попадает и настоящее правило им не портит.
 */
public final class ShellThatDecides {

    private ShellThatDecides() {
    }

    public static ErrorCode decideNickTaken(boolean taken) {
        return taken ? ErrorCode.NICK_TAKEN : null;
    }
}
