package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Objects;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.match.OpenCvPatternLocator;

class MarchQueuePatternEvidenceTest {

    private static final PointData STATUS_SEARCH_TOP_LEFT = new PointData(120, 0);
    private static final PointData STATUS_SEARCH_BOTTOM_RIGHT = new PointData(330, 490);
    private static final PointData ICON_SEARCH_TOP_LEFT = new PointData(10, 35);
    private static final PointData ICON_SEARCH_BOTTOM_RIGHT = new PointData(90, 180);

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // The app and other tests may already have loaded the native library in this JVM.
        }
    }

    @Test
    void statusTextPatternsAreClearOnRealMarchQueueFrames() throws IOException {
        byte[] idleFrame = resource("/marchqueue/idle-gather-unlock-20260713.png");
        byte[] unavailableFrame = resource("/marchqueue/unavailable-reinforced-20260709.png");

        double idleOnIdle = score(idleFrame, "/templates/marchqueue/marchQueueStatusIdle.png", STATUS_SEARCH_TOP_LEFT,
                STATUS_SEARCH_BOTTOM_RIGHT);
        double unlockOnIdleFrame = score(idleFrame, "/templates/marchqueue/marchQueueStatusUnlock.png",
                STATUS_SEARCH_TOP_LEFT, STATUS_SEARCH_BOTTOM_RIGHT);
        double unavailableOnUnavailable = score(unavailableFrame, "/templates/marchqueue/marchQueueStatusUnavailable.png",
                STATUS_SEARCH_TOP_LEFT, STATUS_SEARCH_BOTTOM_RIGHT);
        double idleOnUnavailable = score(unavailableFrame, "/templates/marchqueue/marchQueueStatusIdle.png",
                STATUS_SEARCH_TOP_LEFT, STATUS_SEARCH_BOTTOM_RIGHT);

        assertTrue(idleOnIdle >= 95, "Idle pattern should match the idle frame strongly: " + idleOnIdle);
        assertTrue(unlockOnIdleFrame >= 95, "Unlock pattern should match the unlock row strongly: " + unlockOnIdleFrame);
        assertTrue(unavailableOnUnavailable >= 95,
                "Unavailable pattern should match the unavailable frame strongly: " + unavailableOnUnavailable);
        assertTrue(idleOnIdle - idleOnUnavailable >= 20,
                "Idle pattern should separate idle from unavailable rows: idle=" + idleOnIdle
                        + " unavailable=" + idleOnUnavailable);
    }

    @Test
    void queueResourceIconPatternsSeparateCoalAndIron() throws IOException {
        byte[] frame = resource("/marchqueue/coal-iron-outbound-20260714.png");

        double coal = score(frame, "/templates/marchqueue/marchQueueCoal.png",
                ICON_SEARCH_TOP_LEFT, ICON_SEARCH_BOTTOM_RIGHT);
        double iron = score(frame, "/templates/marchqueue/marchQueueIron.png",
                ICON_SEARCH_TOP_LEFT, ICON_SEARCH_BOTTOM_RIGHT);
        double meat = score(frame, "/templates/marchqueue/marchQueueMeat.png",
                ICON_SEARCH_TOP_LEFT, ICON_SEARCH_BOTTOM_RIGHT);
        double wood = score(frame, "/templates/marchqueue/marchQueueWood.png",
                ICON_SEARCH_TOP_LEFT, ICON_SEARCH_BOTTOM_RIGHT);

        assertTrue(coal >= 95, "Coal icon should match the coal outbound row: " + coal);
        assertTrue(iron >= 95, "Iron icon should match the iron outbound row: " + iron);
        assertTrue(meat < 90, "Meat icon should not match coal/iron rows strongly: " + meat);
        assertTrue(wood < 90, "Wood icon should not match coal/iron rows strongly: " + wood);
    }

    @Test
    void slotFlagPatternSeparatesEmptySlotsFromResourceActivityIcons() throws IOException {
        byte[] frame = resource("/marchqueue/coal-iron-outbound-20260714.png");

        double slotFlagOnEmptySlots = score(frame, "/templates/marchqueue/marchQueueSlotFlag.png",
                new PointData(10, 180), new PointData(90, 490));
        double slotFlagOnResourceRows = score(frame, "/templates/marchqueue/marchQueueSlotFlag.png",
                ICON_SEARCH_TOP_LEFT, ICON_SEARCH_BOTTOM_RIGHT);

        assertTrue(slotFlagOnEmptySlots >= 95, "Slot flag should match empty march rows: " + slotFlagOnEmptySlots);
        assertTrue(slotFlagOnResourceRows < 75,
                "Slot flag should not match coal/iron activity rows strongly: " + slotFlagOnResourceRows);
    }

    @Test
    void activityIconPatternsSeparateKnownNonGatherRows() throws IOException {
        byte[] mixedFrame = resource("/marchqueue/mixed-march-queue-20260709.png");
        byte[] reinforcedFrame = resource("/marchqueue/unavailable-reinforced-20260709.png");
        byte[] garrisonedFrame = resource("/marchqueue/garrisoned-returning-20260714.png");

        double encampment = score(mixedFrame, "/templates/marchqueue/marchQueueEncampment.png",
                new PointData(10, 35), new PointData(90, 115));
        double attackTarget = score(mixedFrame, "/templates/marchqueue/marchQueueAttackTarget.png",
                new PointData(10, 180), new PointData(90, 260));
        double rally = score(mixedFrame, "/templates/marchqueue/marchQueueRally.png",
                new PointData(10, 255), new PointData(90, 335));
        double reinforcement = score(reinforcedFrame, "/templates/marchqueue/marchQueueReinforcement.png",
                new PointData(10, 35), new PointData(90, 115));
        double garrisoned = score(garrisonedFrame, "/templates/marchqueue/marchQueueGarrisoned.png",
                new PointData(10, 180), new PointData(90, 260));

        assertTrue(encampment >= 95, "Encampment icon should match the encamped row: " + encampment);
        assertTrue(attackTarget >= 95, "Attack target icon should match the attack row: " + attackTarget);
        assertTrue(rally >= 95, "Rally icon should match the waiting rally row: " + rally);
        assertTrue(reinforcement >= 95,
                "Reinforcement icon should match the reinforced row: " + reinforcement);
        assertTrue(garrisoned >= 95, "Garrisoned icon should match the garrisoned row: " + garrisoned);
    }

    @Test
    void titlePrefixPatternsIdentifyGatherPhaseAndGenericAttacks() throws IOException {
        byte[] outboundGatherFrame = resource("/marchqueue/coal-iron-outbound-20260714.png");
        byte[] workingGatherFrame = resource("/marchqueue/idle-gather-unlock-20260713.png");
        byte[] cityAttackFrame = resource("/marchqueue/city-attack-outbound-20260709.png");

        double goTo = score(outboundGatherFrame, "/templates/marchqueue/marchQueueTextGoTo.png",
                new PointData(70, 35), new PointData(340, 180));
        double gathering = score(workingGatherFrame, "/templates/marchqueue/marchQueueTextGathering.png",
                new PointData(70, 35), new PointData(340, 260));
        double attack = score(cityAttackFrame, "/templates/marchqueue/marchQueueTextAttack.png",
                new PointData(70, 35), new PointData(340, 115));

        assertTrue(goTo >= 95, "Go to text should match outbound gather rows: " + goTo);
        assertTrue(gathering >= 95, "Gathering text should match working gather rows: " + gathering);
        assertTrue(attack >= 95, "Attack text should match city attack rows: " + attack);
    }

    private static double score(byte[] frame, String template, PointData topLeft, PointData bottomRight) {
        ImageSearchResultData result = OpenCvPatternLocator.locatePattern(frame, template, topLeft, bottomRight, 0);
        return result.getMatchScore();
    }

    private static byte[] resource(String path) throws IOException {
        try (var stream = MarchQueuePatternEvidenceTest.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + path).readAllBytes();
        }
    }
}
