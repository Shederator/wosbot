package dev.frostguard.engine.arch;

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
 * Architecture conformance for the centralized tap-input layer inside
 * the automation module.
 *
 * <p>The public controller exposes only the randomized interaction service.
 * This module rule protects the remaining package-private emulator backend
 * boundary.</p>
 */
class TapInputArchitectureTest {

    private static JavaClasses engineClasses;

    @BeforeAll
    static void importClasses() {
        engineClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dev.frostguard.engine");
    }

    @Test
    void nothingOutsideEmulatorPackageMayCallEmulatorInstanceTapsDirectly() {
        DescribedPredicate<JavaAccess<?>> instanceTap =
                new DescribedPredicate<>("a tap primitive of EmulatorInstance") {
                    @Override
                    public boolean test(JavaAccess<?> access) {
                        if (!access.getTargetOwner().getFullName()
                                .equals("dev.frostguard.engine.emulator.EmulatorInstance")) {
                            return false;
                        }
                        String name = access.getTarget().getName();
                        return name.equals("touchArea") || name.equals("tap");
                    }
                };

        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("dev.frostguard.engine.emulator..")
                .should().accessTargetWhere(instanceTap)
                .because("EmulatorInstance tap primitives are internal to the emulator layer");

        rule.check(engineClasses);
    }
}
