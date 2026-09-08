package dev.frostguard.engine.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Keeps Back presses behind the quit-dialog guard.
 *
 * <p>On a bare screen this game answers the Back button with a native "Quit game?" confirmation,
 * one accidental tap from ending the run. A Back that raises it and walks away leaves the dialog
 * sitting open for whatever executes next, so every Back has to be followed by a dismissal check.
 *
 * <p>Fixing the call sites alone does not hold: this was found by review AFTER the guard shipped,
 * because IntelScreenHelper still pressed Back directly twice while clearing the Lighthouse
 * tutorial -- a path that runs immediately after opening the Lighthouse, which is exactly where a
 * bare screen is likely. The guard's own coverage claim was wrong, and nothing failed to say so.
 * This rule is what makes it stay true: a new direct call site fails the build instead of quietly
 * reopening the hole.
 *
 * <p>Three classes are allowed to call the primitive, because they are the ones that implement the
 * guarding:
 * <ul>
 *   <li>{@code QuitDialogGuard} -- {@code pressBackSafely} is press-then-dismiss.</li>
 *   <li>{@code NavigationHelper} -- its unknown-screen recovery presses Back and then calls
 *       {@code dismissQuitGameDialogIfPresent()}.</li>
 *   <li>{@code DelayedTask} -- the shared {@code pressBack()} every routine uses, which also calls
 *       {@code dismissQuitGameDialogIfPresent()} straight afterwards.</li>
 * </ul>
 * Anything else must go through {@code QuitDialogGuard.pressBackSafely} or
 * {@code DelayedTask.pressBack}.
 */
class BackPressGuardArchitectureTest {

    private static JavaClasses engineClasses;

    @BeforeAll
    static void importClasses() {
        engineClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dev.frostguard.engine");
    }

    @Test
    void onlyTheGuardingClassesMayPressBackDirectly() {
        DescribedPredicate<JavaAccess<?>> rawBackPress =
                new DescribedPredicate<>("a raw Back press on the emulator layer") {
                    @Override
                    public boolean test(JavaAccess<?> access) {
                        if (!access.getTarget().getName().equals("pressBack")) {
                            return false;
                        }
                        String owner = access.getTargetOwner().getFullName();
                        // Only the emulator-facing primitives count. A call to
                        // DelayedTask.pressBack() is the guarded wrapper and is what callers
                        // are supposed to use, so it must not trip this rule.
                        return owner.equals("dev.frostguard.engine.emulator.EmulatorController")
                                || owner.equals("dev.frostguard.engine.emulator.EmulatorInstance")
                                || owner.equals("dev.frostguard.engine.emulator.EmulatorManager");
                    }
                };

        ArchRule rule = noClasses()
                .that().doNotHaveFullyQualifiedName("dev.frostguard.engine.helper.QuitDialogGuard")
                .and().doNotHaveFullyQualifiedName("dev.frostguard.engine.helper.NavigationHelper")
                .and().doNotHaveFullyQualifiedName("dev.frostguard.engine.schedule.DelayedTask")
                .and().resideOutsideOfPackage("dev.frostguard.engine.emulator..")
                .should().accessTargetWhere(rawBackPress)
                .because("a bare-screen Back raises the quit-game dialog, so every Back must be "
                        + "followed by a dismissal check -- use QuitDialogGuard.pressBackSafely or "
                        + "DelayedTask.pressBack instead of the emulator primitive");

        rule.check(engineClasses);
    }
}
