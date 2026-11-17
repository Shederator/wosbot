package dev.frostguard.tasks.economy;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.api.runtime.WorkspaceSession;
import dev.frostguard.engine.helper.TemplateSearchHelper;
import dev.frostguard.engine.schedule.TaskOutcome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BankDepositOutcomeTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 10, 12, 0);

    @BeforeAll
    static void initializeWorkspace() {
        WorkspaceSession.initializeLayout(WorkspacePaths.current());
    }

    @Test
    void unconfirmedDepositGetsShortUnknownRetry() {
        var bank = bankWithActiveDeposit(ImageSearchResultData.miss());

        bank.recordDepositOutcome(15, NOW);

        assertEquals(TaskOutcome.UNKNOWN, bank.getExecutionOutcome());
        assertEquals(NOW.plusMinutes(5), bank.getScheduled());
    }

    @Test
    void visibleActiveDepositKeepsConfiguredMaturity() {
        var bank = bankWithActiveDeposit(ImageSearchResultData.hit(310, 775, 97));

        bank.recordDepositOutcome(15, NOW);

        assertEquals(TaskOutcome.SUCCESS, bank.getExecutionOutcome());
        assertEquals(NOW.plusDays(15), bank.getScheduled());
    }

    private static BankRoutine bankWithActiveDeposit(ImageSearchResultData result) {
        var profile = new AccountDescriptor(912502L, "Synthetic bank", "0", false, 100L, 30L);
        return new BankRoutine(profile, TpDailyTaskEnum.BANK) {
            {
                templateSearchHelper = new TemplateSearchHelper(emuManager, EMULATOR_NUMBER, profile) {
                    @Override
                    public ImageSearchResultData locatePattern(TemplatesEnum template, SearchConfig config) {
                        assertEquals(TemplatesEnum.EVENTS_DEALS_BANK_INDEPOSIT, template);
                        assertEquals(3, config.getMaxAttempts());
                        return result;
                    }
                };
            }

            @Override
            RawImageData captureDepositDiagnosticFrame() {
                return RawImageData.capture(new byte[16], 2, 2, 32);
            }
        };
    }
}
