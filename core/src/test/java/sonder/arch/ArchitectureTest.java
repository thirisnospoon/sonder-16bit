package sonder.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sonder.contract.ErrorCode;

import java.util.Set;
import java.util.stream.Collectors;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Гейт фазы 6: в Java нет ни одного доменного правила.
 *
 * <p>Утверждение это легко произнести и трудно удержать. Соблазн «ну эту-то
 * проверку сделаем здесь, она же простая» возникает на каждой второй задаче,
 * а результат — два места, где живёт одно правило, и они расходятся. Поэтому
 * правило не в документе, а здесь, и ломает сборку.
 *
 * <p>Главная проверка — по кодам отказа. Контракт для каждого кода говорит,
 * кто его выносит ({@code decided_by}), и генератор переносит это в
 * {@link ErrorCode#decidedByCore()}. Если оболочка где-то упоминает код,
 * который решает ядро, — значит она собралась вынести решение сама. Это
 * ловится точно и без толкований: обращение к константе перечисления
 * компилятор оставляет в байткоде как обращение к полю.
 */
class ArchitectureTest {

    private static JavaClasses shell;

    @BeforeAll
    static void importClasses() {
        shell = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("sonder");
    }

    /**
     * Проверка не должна быть пустой. Правило, которому нечего проверять,
     * зелено всегда и не значит ничего — та же причина, по которой у
     * фаззера есть режим самопроверки, а у валидатора контрактов —
     * намеренные дефекты.
     */
    @Test
    @DisplayName("правилам есть что проверять")
    void classesAreImported() {
        assertFalse(shell.isEmpty(), "не импортировано ни одного класса");
        assertTrue(shell.size() >= 5,
                "классов слишком мало, чтобы правила что-то значили: " + shell.size());
    }

    /** Правило вынесено отдельно: его натравливают и на производственный
     *  код, и на намеренных нарушителей в самопроверке ниже. */
    private static ArchRule coreCodesRule() {
        Set<String> coreDecided = java.util.Arrays.stream(ErrorCode.values())
                .filter(ErrorCode::decidedByCore)
                .map(Enum::name)
                .collect(Collectors.toSet());

        assertFalse(coreDecided.isEmpty(),
                "контракт не объявил ни одного кода, решаемого ядром — "
                        + "проверка была бы пустой");

        ArchCondition<com.tngtech.archunit.core.domain.JavaClass> condition =
                new ArchCondition<com.tngtech.archunit.core.domain.JavaClass>(
                        "упоминать коды отказа, которые выносит ядро") {
                    @Override
                    public void check(com.tngtech.archunit.core.domain.JavaClass item,
                                      ConditionEvents events) {
                        for (JavaFieldAccess access : item.getFieldAccessesFromSelf()) {
                            if (!access.getTargetOwner().isEquivalentTo(ErrorCode.class)) {
                                continue;
                            }
                            String field = access.getTarget().getName();
                            if (coreDecided.contains(field)) {
                                events.add(SimpleConditionEvent.satisfied(item,
                                        item.getName() + " обращается к " + field));
                            }
                        }
                    }
                };

        return noClasses()
                .that().resideInAPackage("sonder.shell..")
                .should(condition)
                .because("код с decided_by: core возвращает NODE-7. Оболочка, "
                        + "которая его упоминает, дублирует доменное правило — "
                        + "и однажды разойдётся с ядром (ADR-0011)");
    }

    private static ArchRule versionRule() {
        return classes()
                .that().areAnnotatedWith(javax.persistence.Entity.class)
                .should(new ArchCondition<com.tngtech.archunit.core.domain.JavaClass>(
                        "иметь поле, помеченное @Version") {
                    @Override
                    public void check(com.tngtech.archunit.core.domain.JavaClass item,
                                      ConditionEvents events) {
                        boolean has = item.getFields().stream()
                                .anyMatch(f -> f.isAnnotatedWith(javax.persistence.Version.class));
                        if (!has) {
                            events.add(SimpleConditionEvent.violated(item,
                                    item.getName() + " не имеет поля @Version"));
                        }
                    }
                })
                .because("оптимистическая блокировка — единственное, что не даёт "
                        + "потерять чужое изменение между загрузкой состояния и "
                        + "сохранением решения");
    }

    @Test
    @DisplayName("оболочка не выносит решений, которые выносит ядро")
    void shellDoesNotDecideCoreCodes() {
        coreCodesRule().check(shell);
    }

    /**
     * У каждого агрегата есть версия. Правило сплошное и без списка
     * исключений: список однажды пополнится тем агрегатом, которому версия
     * была нужна, и заметят это по перезаписанным данным.
     */
    @Test
    @DisplayName("у каждой сущности JPA есть поле версии")
    void everyEntityHasVersion() {
        versionRule().check(shell);
    }

    /* ==================================================================
       Самопроверка правил

       Правило, которому нечего ловить, зелено всегда и не значит ничего.
       Здесь те же правила натравливаются на намеренных нарушителей из
       sonder.shell.fixture — тот же приём, что у валидатора контрактов с
       его четырнадцатью дефектами и у фаззера с его --selftest.
       ================================================================== */

    private static JavaClasses fixtures() {
        // Без DO_NOT_INCLUDE_TESTS: нарушители живут в тестовых исходниках
        // именно затем, чтобы не портить настоящий скан.
        return new ClassFileImporter().importPackages("sonder.shell.fixture");
    }

    @Test
    @DisplayName("правило про коды ядра умеет упасть")
    void coreCodesRuleCatchesViolator() {
        JavaClasses violators = fixtures();
        assertFalse(violators.isEmpty(), "нарушители не собрались");
        assertThrows(AssertionError.class, () -> coreCodesRule().check(violators),
                "правило пропустило класс, который сам решает NICK_TAKEN");
    }

    @Test
    @DisplayName("правило про версию умеет упасть")
    void versionRuleCatchesViolator() {
        assertThrows(AssertionError.class, () -> versionRule().check(fixtures()),
                "правило пропустило сущность без @Version");
    }

    /**
     * Сущности живут в одном месте. Не ради красоты: разложенные по слоям
     * сущности рано или поздно обрастают логикой в том слое, куда попали.
     */
    @Test
    @DisplayName("сущности JPA лежат только в sonder.shell.store")
    void entitiesLiveInStore() {
        classes()
                .that().areAnnotatedWith(javax.persistence.Entity.class)
                .should().resideInAPackage("sonder.shell.store")
                .because("сущность, оказавшаяся в слое приложения, обрастает "
                        + "логикой этого слоя")
                .check(shell);
    }

    /**
     * Сгенерированный контракт не зависит от оболочки. Направление
     * зависимости здесь важнее обычного: контракт порождается из WSDL и
     * YAML, и если он начнёт ссылаться на оболочку, перегенерация станет
     * невозможной.
     */
    @Test
    @DisplayName("контракт не зависит от оболочки")
    void contractDoesNotDependOnShell() {
        noClasses()
                .that().resideInAPackage("sonder.contract..")
                .should().dependOnClassesThat().resideInAPackage("sonder.shell..")
                .because("contract порождается кодогенерацией: зависимость от "
                        + "рукописного кода сделала бы перегенерацию невозможной")
                .check(shell);
    }
}
