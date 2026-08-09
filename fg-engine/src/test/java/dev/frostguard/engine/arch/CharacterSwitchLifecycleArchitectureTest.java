package dev.frostguard.engine.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

class CharacterSwitchLifecycleArchitectureTest {

    @Test
    void characterSwitchHelperDoesNotOwnEmulatorShutdown() {
        var engineClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dev.frostguard.engine");

        DescribedPredicate<JavaAccess<?>> closesEmulator =
                new DescribedPredicate<>("EmulatorController.closeEmulator") {
                    @Override
                    public boolean test(JavaAccess<?> access) {
                        return access.getTargetOwner().getFullName()
                                .equals("dev.frostguard.engine.emulator.EmulatorController")
                                && access.getTarget().getName().equals("closeEmulator");
                    }
                };

        noClasses()
                .that().haveFullyQualifiedName("dev.frostguard.engine.helper.CharacterSwitchHelper")
                .should().accessTargetWhere(closesEmulator)
                .because("the task queue must choose shutdown or shared-emulator handover")
                .check(engineClasses);
    }
}
