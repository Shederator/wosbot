package dev.frostguard.app.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import dev.frostguard.engine.emulator.EmulatorController;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Repository-wide architecture conformance for the centralized tap-input
 * layer.
 *
 * <p>The per-module rules in automation and tasks only see their own classpath;
 * this suite runs in the desktop leaf module
 * that depends on every production module — so it observes the complete
 * production class graph ({@code dev.frostguard..}). Any new module or class
 * anywhere in the repository that bypasses
 * {@code dev.frostguard.engine.input.TapInteractionService} fails here. The
 * controller API is also checked directly so runtime-loaded custom tasks
 * cannot reach exact-coordinate tap primitives.</p>
 */
class RepositoryTapInputArchitectureTest {

    private static final String INSTANCE_CLASS = "dev.frostguard.engine.emulator.EmulatorInstance";

    private static JavaClasses allProductionClasses;

    @BeforeAll
    static void importClasses() {
        allProductionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dev.frostguard");
    }

    /**
     * Matches both direct calls and method references (e.g.
     * {@code controller::touchPoint}), so the layer cannot be bypassed
     * through a lambda-shaped indirection either.
     */
    private static DescribedPredicate<JavaAccess<?>> tapPrimitiveOf(String ownerClass, String... methodNames) {
        return new DescribedPredicate<>("a tap primitive of " + ownerClass) {
            @Override
            public boolean test(JavaAccess<?> access) {
                if (!access.getTargetOwner().getFullName().equals(ownerClass)) {
                    return false;
                }
                String name = access.getTarget().getName();
                for (String candidate : methodNames) {
                    if (name.equals(candidate)) {
                        return true;
                    }
                }
                return false;
            }
        };
    }

    @Test
    void emulatorControllerExposesNoRawTapPrimitive() {
        boolean exposesRawTap = Arrays.stream(EmulatorController.class.getMethods())
                .map(method -> method.getName())
                .anyMatch(name -> name.equals("touchPoint") || name.equals("touchArea") || name.equals("dispatchTap"));

        assertFalse(exposesRawTap,
                "EmulatorController must expose only tapInteractions(), never an exact-coordinate tap primitive");
    }

    @Test
    void nothingOutsideTheEmulatorPackageMayCallEmulatorInstanceTapPrimitivesAnywhereInTheRepository() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("dev.frostguard.engine.emulator..")
                .should().accessTargetWhere(tapPrimitiveOf(INSTANCE_CLASS, "touchArea", "tap"))
                .because("EmulatorInstance tap primitives are internal to the emulator layer");

        rule.check(allProductionClasses);
    }

    @Test
    void noProductionClassMayUseLegacyDeprecatedTapMethodsAnywhereInTheRepository() {
        DescribedPredicate<JavaAccess<?>> legacyTap =
                new DescribedPredicate<>("a legacy deterministic tap helper (tapPoint/tapRandomPoint)") {
                    @Override
                    public boolean test(JavaAccess<?> access) {
                        String name = access.getTarget().getName();
                        return name.equals("tapPoint") || name.equals("tapRandomPoint");
                    }
                };

        ArchRule rule = noClasses()
                .should().accessTargetWhere(legacyTap)
                .because("the deterministic tap helpers were removed; use tapNear/tapInside "
                        + "from the shared input layer instead");

        rule.check(allProductionClasses);
    }
}
