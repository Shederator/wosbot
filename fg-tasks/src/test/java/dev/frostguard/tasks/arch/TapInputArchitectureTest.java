package dev.frostguard.tasks.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture conformance for the centralized tap-input layer.
 *
 * <p>All production tap input must go through the shared interaction layer
 * ({@code dev.frostguard.engine.input.TapInteractionService}, surfaced to
 * routines through the {@code DelayedTask} tap API). Repeated taps at
 * identical pixel coordinates create deterministic input patterns, so no
 * task may call the low-level emulator primitives
 * ({@code EmulatorController.touchPoint} / {@code touchArea}) directly.</p>
 *
 * <p>The {@code fg-tasks} module has been fully migrated, so this rule is
 * strict: zero low-level tap calls are allowed. New violations fail the
 * build immediately.</p>
 */
class TapInputArchitectureTest {

    private static JavaClasses taskClasses;

    @BeforeAll
    static void importClasses() {
        taskClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dev.frostguard.tasks");
    }

    /**
     * Matches both direct calls and method references (e.g.
     * {@code controller::touchPoint}), so the layer cannot be bypassed
     * through a lambda-shaped indirection either.
     */
    private static DescribedPredicate<JavaAccess<?>> tapPrimitiveOf(String ownerClass, String... methods) {
        return new DescribedPredicate<>("a low-level tap primitive of " + ownerClass) {
            @Override
            public boolean test(JavaAccess<?> access) {
                if (!access.getTargetOwner().getFullName().equals(ownerClass)) {
                    return false;
                }
                String name = access.getTarget().getName();
                for (String m : methods) {
                    if (name.equals(m)) {
                        return true;
                    }
                }
                return false;
            }
        };
    }

    @Test
    void tasksMustNotCallLowLevelEmulatorTapPrimitives() {
        ArchRule rule = noClasses()
                .should().accessTargetWhere(tapPrimitiveOf(
                        "dev.frostguard.engine.emulator.EmulatorController",
                        "touchPoint", "touchArea"))
                .because("all tap input must go through the shared input layer "
                        + "(DelayedTask.tapInside / tapNear), which applies coordinate "
                        + "randomization, preemption checks, and clamping consistently");

        rule.check(taskClasses);
    }

    @Test
    void tasksMustNotBypassTheInputLayerViaTheEmulatorInstance() {
        ArchRule rule = noClasses()
                .should().accessTargetWhere(tapPrimitiveOf(
                        "dev.frostguard.engine.emulator.EmulatorInstance",
                        "touchArea", "tap"))
                .because("emulator-level tap primitives are internal implementation "
                        + "details of the input layer");

        rule.check(taskClasses);
    }

    /**
     * The legacy {@code tapPoint} / {@code tapRandomPoint} methods have been
     * removed from {@code DelayedTask} after full migration. This rule keeps
     * them gone: if someone reintroduces same-named helpers, no task may
     * call them, enforcing the final architecture proposed in issue #38.
     */
    @Test
    void tasksMustNotUseLegacyDeprecatedTapMethods() {
        DescribedPredicate<JavaAccess<?>> legacyTap =
                new DescribedPredicate<>("a legacy tapPoint/tapRandomPoint call") {
                    @Override
                    public boolean test(JavaAccess<?> access) {
                        String name = access.getTarget().getName();
                        return name.equals("tapPoint") || name.equals("tapRandomPoint");
                    }
                };

        ArchRule rule = noClasses()
                .should().accessTargetWhere(legacyTap)
                .because("legacy fixed-coordinate tap methods were removed after the "
                        + "migration to tapInside/tapNear; they must not come back");

        rule.check(taskClasses);
    }
}
