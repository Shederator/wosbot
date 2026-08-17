package dev.frostguard.tasks.dailies;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.vision.convert.GameTimeUtils;
import java.time.Duration;
import java.time.LocalDateTime;

public class VipRoutine extends DelayedTask {

private static final boolean DEFAULT_BUY_MONTHLY_VIP_VALUE = false;

private static final PointData VIP_MENU_BUTTON_TOP_LEFT_VALUE = new PointData(430, 48);

private static final PointData VIP_MENU_BUTTON_BOTTOM_RIGHT_VALUE = new PointData(530, 85);

private static final PointData PURCHASE_CONFIRM_TOP_LEFT_VALUE = new PointData(520, 810);

private static final PointData PURCHASE_CONFIRM_BOTTOM_RIGHT_VALUE = new PointData(650, 850);

private static final PointData PURCHASE_FINAL_CONFIRM_TOP_LEFT_VALUE = new PointData(250, 770);

private static final PointData PURCHASE_FINAL_CONFIRM_BOTTOM_RIGHT_VALUE = new PointData(480, 800);

private static final PointData VIP_EXPIRATION_TIME_TOP_LEFT_MS = new PointData(273, 1170);

private static final PointData VIP_EXPIRATION_TIME_BOTTOM_RIGHT_MS = new PointData(461, 1213);

private static final PointData DAILY_CHEST_REWARDS_TOP_LEFT_VALUE = new PointData(540, 813);

private static final PointData DAILY_CHEST_REWARDS_BOTTOM_RIGHT_VALUE = new PointData(624, 835);

private static final PointData VIP_POINT_REWARDS_TOP_LEFT_VALUE = new PointData(602, 263);

private static final PointData VIP_POINT_REWARDS_BOTTOM_RIGHT_VALUE = new PointData(650, 293);

private boolean buyMonthlyVip;

private LocalDateTime nextMonthlyVipBuyTime;

public VipRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
		super(profile, tpDailyTask);
	}

@Override
	protected void execute() {
		hydrateConfiguration();

		if (!openUpVipMenu()) {
			logWarning(routineLogVipLine("Could not open VIP menu."));
			queueNextExecution();
			return;
		}

		if (buyMonthlyVip) {
			manageMonthlyVipPurchase();
		}

		redeemDailyChestRewards();

		redeemVipPointRewards();

		dismissVipMenu();

		queueNextExecution();

	}

@Override
	protected LaunchPoint getRequiredStartLocation() {
		return LaunchPoint.ANY;
	}

private void scanAndStoreVipExpirationTime() {
		logDebug(routineLogVipLine("Reading VIP expiration time from screen"));

		OcrSettingsData timeSettings = OcrSettingsData.assembler()
				.textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)

				.charWhitelist("0123456789d")
				.build();

		Duration expirationTime = durationHelper.attemptRecognition(
				VIP_EXPIRATION_TIME_TOP_LEFT_MS,
				VIP_EXPIRATION_TIME_BOTTOM_RIGHT_MS,
				3,
				200L,
				timeSettings,
				GameTimeUtils::isAcceptedFormat,
				GameTimeUtils::parseDuration);

		if (expirationTime == null) {
			logWarning(routineLogVipLine("Could not read VIP expiration time from screen"));
			return;
		}

		LocalDateTime calculatedExpirationTime = LocalDateTime.now().plus(expirationTime);
		logDebug(routineLogVipLine("OCR result: '" + GameTimeUtils.formatCountdown(calculatedExpirationTime) + "'"));
		nextMonthlyVipBuyTime = calculatedExpirationTime;


		writeProfileSetting(
				ConfigurationKeyEnum.VIP_NEXT_MONTHLY_BUY_TIME_STRING,
				calculatedExpirationTime.toString());

		logInfo(routineLogVipLine(String.format("VIP expiration time stored: %s (expires in %s)",
				calculatedExpirationTime.format(DATETIME_FORMATTER),
				GameTimeUtils.formatCountdown(calculatedExpirationTime))));

	}

private void manageMonthlyVipPurchase() {
		logDebug(routineLogVipLine("Handling monthly VIP purchase check"));


		if (nextMonthlyVipBuyTime != null && LocalDateTime.now().isBefore(nextMonthlyVipBuyTime)) {
			logInfo(routineLogVipLine(String.format("Monthly VIP purchase on cooldown. Next purchase available at: %s (in %s)",
					nextMonthlyVipBuyTime.format(DATETIME_FORMATTER),
					GameTimeUtils.formatCountdown(nextMonthlyVipBuyTime))));
			return;
		}

		logDebug(routineLogVipLine("Cooldown expired or not set. Inspecting VIP status."));


		ImageSearchResultData unlockButton = templateSearchHelper.locatePattern(
				TemplatesEnum.VIP_UNLOCK_BUTTON,
				SearchConfigConstants.DEFAULT_SINGLE);

		if (unlockButton.isFound()) {


			logInfo(routineLogVipLine("Monthly VIP is not active. Initiating purchase."));
			purchaseMonthlyVipFlow(unlockButton.getPoint());
		} else {


			logInfo(routineLogVipLine("Monthly VIP is already active."));
		}


		scanAndStoreVipExpirationTime();
	}

private void redeemDailyChestRewards() {
		logInfo(routineLogVipLine("Collecting daily VIP chest rewards"));

		tapInside(
				DAILY_CHEST_REWARDS_TOP_LEFT_VALUE,
				DAILY_CHEST_REWARDS_BOTTOM_RIGHT_VALUE,
				3,
				300);

		sleepTask(500);


		logDebug(routineLogVipLine("Daily chest rewards collected"));
	}

private void purchaseMonthlyVipFlow(PointData unlockButtonPoint) {


		tapNear(unlockButtonPoint);
		sleepTask(1000);


		logDebug(routineLogVipLine("Confirming VIP purchase (step 1/2)"));
		tapInside(PURCHASE_CONFIRM_TOP_LEFT_VALUE, PURCHASE_CONFIRM_BOTTOM_RIGHT_VALUE);
		sleepTask(500);


		logDebug(routineLogVipLine("Confirming VIP purchase (step 2/2)"));
		tapInside(PURCHASE_FINAL_CONFIRM_TOP_LEFT_VALUE, PURCHASE_FINAL_CONFIRM_BOTTOM_RIGHT_VALUE);
		sleepTask(500);


		pressBack();
		sleepTask(500);


		logInfo(routineLogVipLine("Monthly VIP purchase completed."));
	}

private void redeemVipPointRewards() {
		logInfo(routineLogVipLine("Collecting VIP point rewards"));

		tapInside(
				VIP_POINT_REWARDS_TOP_LEFT_VALUE,
				VIP_POINT_REWARDS_BOTTOM_RIGHT_VALUE,
				3,
				300);

		sleepTask(500);


		logDebug(routineLogVipLine("VIP point rewards collected"));
	}

private void hydrateConfiguration() {
		Boolean configuredBuyVip = profile.getConfig(
				ConfigurationKeyEnum.VIP_MONTHLY_BUY_BOOL, Boolean.class);
		this.buyMonthlyVip = (configuredBuyVip != null) ? configuredBuyVip : DEFAULT_BUY_MONTHLY_VIP_VALUE;


		String nextBuyTimeStr = profile.getConfig(
				ConfigurationKeyEnum.VIP_NEXT_MONTHLY_BUY_TIME_STRING, String.class);

		if (nextBuyTimeStr != null && !nextBuyTimeStr.isEmpty()) {
			try {
				this.nextMonthlyVipBuyTime = LocalDateTime.parse(nextBuyTimeStr);
			} catch (Exception e) {
				logWarning(routineLogVipLine("Could not parse stored next monthly VIP buy time: " + e.getMessage()));
				this.nextMonthlyVipBuyTime = null;
			}
		} else {
			this.nextMonthlyVipBuyTime = null;
		}

		logDebug(routineLogVipLine(String.format("Configuration loaded - Buy monthly VIP: %s, Next buy: %s",
				buyMonthlyVip,
				(nextMonthlyVipBuyTime != null)
						? nextMonthlyVipBuyTime.format(DATETIME_FORMATTER)
						: "not set")));
	}

private void queueNextExecution() {
		LocalDateTime nextGameReset = GameTimeUtils.dailyResetTime();
		LocalDateTime nextExecutionTime = nextGameReset;

		if (buyMonthlyVip && nextMonthlyVipBuyTime != null) {


			if (nextMonthlyVipBuyTime.isBefore(nextGameReset)) {
				nextExecutionTime = nextMonthlyVipBuyTime;
				logInfo(routineLogVipLine("Next execution scheduled for monthly VIP buy at: " +
						nextMonthlyVipBuyTime.format(DATETIME_FORMATTER)));
			} else {
				logInfo(routineLogVipLine("Next execution scheduled for game reset at: " +
						nextGameReset.format(DATETIME_FORMATTER)));
			}
		} else {
			logInfo(routineLogVipLine("Next execution scheduled for game reset at: " +
					nextGameReset.format(DATETIME_FORMATTER)));
		}

		reschedule(nextExecutionTime);
	}

private String routineLogVipLine(String note) {
        return "VipRoutine | " + note;
    }

private void dismissVipMenu() {
		logDebug(routineLogVipLine("Closing VIP menu"));
		pressBack();
		sleepTask(500);

	}

private boolean openUpVipMenu() {
		logDebug(routineLogVipLine("Entering VIP menu"));

		tapInside(VIP_MENU_BUTTON_TOP_LEFT_VALUE, VIP_MENU_BUTTON_BOTTOM_RIGHT_VALUE);
		sleepTask(1000);


		ImageSearchResultData vipMenu = templateSearchHelper.locatePattern(
				TemplatesEnum.VIP_MENU,
				SearchConfigConstants.DEFAULT_SINGLE);

		if (!vipMenu.isFound()) {
			return false;
		}

		return true;
	}
}
