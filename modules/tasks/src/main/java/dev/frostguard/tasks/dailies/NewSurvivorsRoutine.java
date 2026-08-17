package dev.frostguard.tasks.dailies;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import java.time.LocalDateTime;

public class NewSurvivorsRoutine extends DelayedTask {

public NewSurvivorsRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

@Override
    protected void execute() {


        logInfo(routineLogNewSurvivorsLine("Scanning for the 'New Survivors' notification."));
        ImageSearchResultData newSurvivors = templateSearchHelper.locatePattern(
                TemplatesEnum.GAME_HOME_NEW_SURVIVORS, SearchConfigConstants.DEFAULT_SINGLE);
        if (newSurvivors.isFound()) {
            tapInside(newSurvivors);
            sleepTask(1000);


            logInfo(routineLogNewSurvivorsLine("New survivors detected. Welcoming them in."));
            ImageSearchResultData welcomeIn = templateSearchHelper.locatePattern(
                    TemplatesEnum.GAME_HOME_NEW_SURVIVORS_WELCOME_IN, SearchConfigConstants.DEFAULT_SINGLE);
            if (welcomeIn.isFound()) {
                tapInside(welcomeIn);
                logInfo(routineLogNewSurvivorsLine("Waiting briefly before reassigning survivors to buildings."));
                sleepTask(10000);

                tapNear(new PointData(309, 20), 3);
                sleepTask(300);


                logInfo(routineLogNewSurvivorsLine("Assigning survivors to available building slots."));
                emuManager.swipeScreen(EMULATOR_NUMBER, new PointData(340, 610), new PointData(340, 900));
                sleepTask(200);

                ImageSearchResultData plusButton = null;
                while ((plusButton = templateSearchHelper.locatePattern(
                        TemplatesEnum.GAME_HOME_NEW_SURVIVORS_PLUS_BUTTON, SearchConfigConstants.DEFAULT_SINGLE))
                        .isFound()) {
                    tapInside(plusButton);
                    sleepTask(50);
                }


                emuManager.swipeScreen(EMULATOR_NUMBER, new PointData(340, 900), new PointData(340, 610));
                sleepTask(200);
                while ((plusButton = templateSearchHelper.locatePattern(
                        TemplatesEnum.GAME_HOME_NEW_SURVIVORS_PLUS_BUTTON, SearchConfigConstants.DEFAULT_SINGLE))
                        .isFound()) {
                    tapInside(plusButton);
                    sleepTask(50);
                }

                logInfo(routineLogNewSurvivorsLine("Survivor assignment complete. Planning next run task."));
                this.reschedule(LocalDateTime.now().plusMinutes(
                        profile.getConfig(ConfigurationKeyEnum.CITY_ACCEPT_NEW_SURVIVORS_OFFSET_INT, Integer.class)));
            }

        } else {
            logInfo(routineLogNewSurvivorsLine("Zero new survivors detected. Planning next run task."));
            this.reschedule(LocalDateTime.now().plusMinutes(
                    profile.getConfig(ConfigurationKeyEnum.CITY_ACCEPT_NEW_SURVIVORS_OFFSET_INT, Integer.class)));

        }

    }

@Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

private String routineLogNewSurvivorsLine(String note) {
        return "NewSurvivorsRoutine | " + note;
    }
}
