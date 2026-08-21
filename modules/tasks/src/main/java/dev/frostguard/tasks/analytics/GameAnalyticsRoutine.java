package dev.frostguard.tasks.analytics;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.engine.emulator.EmulatorType;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.ranking.GameAnalyticsRunRegistry;
import dev.frostguard.engine.ranking.capture.AllianceRankingCaptureResult;
import dev.frostguard.engine.ranking.capture.GameAnalyticsCollectionType;
import dev.frostguard.engine.ranking.capture.MuMuTcpdumpAnalyticsCapture;
import dev.frostguard.engine.ranking.history.GameAnalyticsHistoryService;
import dev.frostguard.engine.ranking.history.GameAnalyticsSnapshot;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.tasks.lifecycle.InitializeRoutine;

/** One-shot analytics task executed through the regular profile lifecycle. */
public final class GameAnalyticsRoutine extends DelayedTask {

    private final GameAnalyticsCollectionType collectionType;
    private final MuMuTcpdumpAnalyticsCapture capture = new MuMuTcpdumpAnalyticsCapture();
    private final GameAnalyticsHistoryService history = new GameAnalyticsHistoryService();

    public GameAnalyticsRoutine(AccountDescriptor profile, TpDailyTaskEnum task) {
        super(profile, task);
        collectionType = switch (task) {
            case GAME_ANALYTICS_LABYRINTH -> GameAnalyticsCollectionType.LABYRINTH;
            case GAME_ANALYTICS_POWER -> GameAnalyticsCollectionType.POWER;
            default -> throw new IllegalArgumentException("Unsupported analytics task: " + task);
        };
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

    @Override
    protected boolean acceptsInjections() {
        return false;
    }

    @Override
    public void run() {
        try {
            if (collectionType == GameAnalyticsCollectionType.POWER) {
                GameAnalyticsRunRegistry.publish(new GameAnalyticsRunRegistry.Event(
                        profile.getId(), collectionType, GameAnalyticsRunRegistry.State.RUNNING,
                        "Capturing startup and ranking traffic...", null, null));
                startCapture();
                emuManager.forceStopApp(EMULATOR_NUMBER, EmulatorController.GAME.getPackageName());
                new InitializeRoutine(profile, TpDailyTaskEnum.INITIALIZE).run();
            }
            super.run();
        } catch (RuntimeException exception) {
            capture.cancel();
            GameAnalyticsRunRegistry.publish(new GameAnalyticsRunRegistry.Event(
                    profile.getId(), collectionType, GameAnalyticsRunRegistry.State.FAILED,
                    exception.getMessage(), null, null));
            throw exception;
        }
    }

    @Override
    protected void execute() {
        GameAnalyticsRunRegistry.publish(new GameAnalyticsRunRegistry.Event(
                profile.getId(), collectionType, GameAnalyticsRunRegistry.State.RUNNING,
                "Collecting " + collectionType.name().toLowerCase() + " analytics...", null, null));
        try {
            if (!capture.isRunning()) {
                startCapture();
            }
            if (collectionType == GameAnalyticsCollectionType.LABYRINTH) {
                navigationHelper.navigateToLabyrinthRanking();
            } else {
                navigationHelper.navigateToPowerRanking();
            }
            AllianceRankingCaptureResult result = capture.stopAndDecode();
            GameAnalyticsSnapshot snapshot = history.save(
                    WorkspacePaths.current().root(), profile, EmulatorType.MUMU, collectionType, result);
            logInfo("Collected " + entryCount(result) + " "
                    + collectionType.name().toLowerCase() + " analytics entries");
            GameAnalyticsRunRegistry.publish(new GameAnalyticsRunRegistry.Event(
                    profile.getId(), collectionType, GameAnalyticsRunRegistry.State.SUCCEEDED,
                    "Collection completed", snapshot.result(), snapshot));
        } catch (Exception exception) {
            capture.cancel();
            throw new IllegalStateException("Could not collect "
                    + collectionType.name().toLowerCase() + " analytics: "
                    + exception.getMessage(), exception);
        }
    }

    private void startCapture() {
        try {
            capture.start(WorkspacePaths.current().root(), collectionType,
                    emuManager.getAdbPath(), emuManager.getDeviceSerial(EMULATOR_NUMBER));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not start analytics traffic capture: "
                    + exception.getMessage(), exception);
        }
    }

    private int entryCount(AllianceRankingCaptureResult result) {
        return collectionType == GameAnalyticsCollectionType.POWER
                ? result.power().size() : result.labyrinth().size();
    }
}
