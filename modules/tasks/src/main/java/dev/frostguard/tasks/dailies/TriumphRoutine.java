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
import dev.frostguard.vision.convert.GameTimeUtils;
import java.time.LocalDateTime;

public class TriumphRoutine extends DelayedTask {

public TriumphRoutine(AccountDescriptor profile, TpDailyTaskEnum dailyMission) {
		super(profile, dailyMission);
	}

@Override
	public LaunchPoint getRequiredStartLocation() {
		return LaunchPoint.HOME;
	}

@Override
	protected void execute() {;


		logInfo(routineLogTriumphLine("Pressing alliance button at bottom of screen"));
		tapInside(new PointData(493, 1187), new PointData(561, 1240));
		sleepTask(3000);


		ImageSearchResultData result = templateSearchHelper.locatePattern(
				TemplatesEnum.ALLIANCE_TRIUMPH_BUTTON, SearchConfigConstants.DEFAULT_SINGLE);
		if (result.isFound()) {
			logInfo(routineLogTriumphLine("Alliance Triumph button detected. Pressing to open the menu."));
			tapInside(result);
			sleepTask(2000);

			logInfo(routineLogTriumphLine("Inspecting daily Triumph rewards status"));


			result = templateSearchHelper.locatePattern(
					TemplatesEnum.ALLIANCE_TRIUMPH_DAILY_CLAIMED, SearchConfigConstants.DEFAULT_SINGLE);

			if (result.isFound()) {
				logInfo(routineLogTriumphLine("Daily Triumph rewards already collected - planning next run for next game reset"));
				this.reschedule(GameTimeUtils.dailyResetTime());
			} else {


				logInfo(routineLogTriumphLine("Daily rewards not collected yet, inspecting if they are available"));
				result = templateSearchHelper.locatePattern(
						TemplatesEnum.ALLIANCE_TRIUMPH_DAILY, SearchConfigConstants.DEFAULT_SINGLE);

				if (result.isFound()) {
					logInfo(routineLogTriumphLine("Daily Triumph rewards are available - collecting now"));
					tapInside(result.getPoint(), result.getPoint(), 10, 50);
					sleepTask(1000);

					logInfo(routineLogTriumphLine("Daily rewards collected finished cleanly"));
					reschedule(GameTimeUtils.dailyResetTime());
				} else {


					int offset = profile.getConfig(ConfigurationKeyEnum.ALLIANCE_TRIUMPH_OFFSET_INT, Integer.class);
					LocalDateTime proposedSchedule = LocalDateTime.now().plusMinutes(offset);


					LocalDateTime nextSchedule = GameTimeUtils.clampToResetWindow(proposedSchedule);

					if (!nextSchedule.equals(proposedSchedule)) {
						logInfo(routineLogTriumphLine("Next scheduled time would be after game reset. Adjusting to 5 minutes before reset."));
					}

					logInfo(routineLogTriumphLine("Daily Triumph rewards not available - planning next run for " + nextSchedule));
					reschedule(nextSchedule);
				}
			}


			logInfo(routineLogTriumphLine("Inspecting weekly Triumph rewards status"));
			result = templateSearchHelper.locatePattern(TemplatesEnum.ALLIANCE_TRIUMPH_WEEKLY,
					SearchConfigConstants.DEFAULT_SINGLE);

			if (result.isFound()) {
				logInfo(routineLogTriumphLine("Weekly Triumph rewards are available - collecting now"));
				tapInside(result);
				sleepTask(1500);

				pressBack();
				logInfo(routineLogTriumphLine("Weekly Triumph collected finished cleanly"));
			} else {
				logInfo(routineLogTriumphLine("Weekly Triumph rewards not available or already collected"));
			}


			logDebug(routineLogTriumphLine("Returning to home screen"));
			pressBack();
			sleepTask(300);
			pressBack();
		} else {
			int offset = profile.getConfig(ConfigurationKeyEnum.ALLIANCE_TRIUMPH_OFFSET_INT, Integer.class);
			logError(routineLogTriumphLine("Alliance Triumph button not detected - unable to claim rewards"));

			LocalDateTime proposedSchedule = LocalDateTime.now().plusMinutes(offset);
			LocalDateTime nextSchedule = dev.frostguard.vision.convert.GameTimeUtils.clampToResetWindow(proposedSchedule);

			if (!nextSchedule.equals(proposedSchedule)) {
				logInfo(routineLogTriumphLine("Next scheduled time would be after game reset. Adjusting to 5 minutes before reset."));
			}

			logInfo(routineLogTriumphLine("Planning next run task for " + nextSchedule));
			pressBack();
			sleepTask(500);
			reschedule(nextSchedule);
		}

	}

private String routineLogTriumphLine(String note) {
        return "TriumphRoutine | " + note;
    }
}
