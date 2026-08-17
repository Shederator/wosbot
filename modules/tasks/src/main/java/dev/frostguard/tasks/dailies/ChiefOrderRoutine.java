package dev.frostguard.tasks.dailies;

import java.time.LocalDateTime;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.vision.convert.GameTimeUtils;

public class ChiefOrderRoutine extends DelayedTask {

public enum ChiefOrderType {

		RUSH_JOB("Rush Job", TemplatesEnum.CHIEF_ORDER_RUSH_JOB, 24),


		URGENT_MOBILIZATION("Urgent Mobilization", TemplatesEnum.CHIEF_ORDER_URGENT_MOBILISATION, 8),


		PRODUCTIVITY_DAY("Productivity Day", TemplatesEnum.CHIEF_ORDER_PRODUCTIVITY_DAY, 12);

		private final String description;
		private final TemplatesEnum template;
		private final int cooldownHours;

		ChiefOrderType(String description, TemplatesEnum template, int cooldownHours) {
			this.description = description;
			this.template = template;
			this.cooldownHours = cooldownHours;
		}

		public String getDescription() {
			return description;
		}

		public TemplatesEnum getTemplate() {
			return template;
		}

		public int getCooldownHours() {
			return cooldownHours;
		}
	}

private static final int ERROR_RETRY_MINUTES_VALUE = 10;

/** Pulls the remaining time out of an "On cooldown hh:mm:ss" banner. */
private static final java.util.regex.Pattern COOLDOWN_REMAINING =
        java.util.regex.Pattern.compile("(\\d{1,2}:\\d{2}:\\d{2})");

private final ChiefOrderType chiefOrderType;

public ChiefOrderRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask, ChiefOrderType chiefOrderType) {
		super(profile, tpTask);
		this.chiefOrderType = chiefOrderType;
	}

@Override
	public LaunchPoint getRequiredStartLocation() {
		return LaunchPoint.HOME;
	}

@Override
	protected void execute() {
		logInfo(routineLogChiefOrderLine("Initiating Chief Order : " + chiefOrderType.getDescription() +
				" (Cooldown: " + chiefOrderType.getCooldownHours() + " hours)"));

		if (!openUpChiefOrderMenu()) {
			manageTaskFailure("Failed to open Chief Order menu");
			return;
		}

		switch (chooseOrderType()) {
			case SCHEDULED_FROM_SCREEN -> {
				// The shelf told us exactly when this order frees up and that time is already
				// recorded. Falling into manageTaskFailure here would immediately overwrite it
				// with a blind 10-minute retry — which is how a 10-hour Rush Job cooldown turned
				// into sixty pointless trips to the shelf.
				pressBack();
				return;
			}
			case UNAVAILABLE -> {
				manageTaskFailure("Order type not available (likely on cooldown)");
				return;
			}
			default -> { /* AVAILABLE — the order is open, carry on and enact it. */ }
		}

		if (!enactOrderFlow()) {
			manageTaskFailure("Failed to enact order");
			return;
		}

		queueNextRun();
	}

private String routineLogChiefOrderLine(String note) {
        return "ChiefOrderRoutine | " + note;
    }

private boolean openUpChiefOrderMenu() {
		logInfo(routineLogChiefOrderLine("Looking for Chief Order menu access button"));

		ImageSearchResultData menuButton = templateSearchHelper.locatePattern(
				TemplatesEnum.CHIEF_ORDER_MENU_BUTTON,
				SearchConfigConstants.DEFAULT_SINGLE);

		if (!menuButton.isFound()) {
			logError(routineLogChiefOrderLine("Chief Order menu button not detected"));
			return false;
		}

		logInfo(routineLogChiefOrderLine("Chief Order menu button detected. Pressing to open menu"));
		tapInside(menuButton);
		sleepTask(2000);


		return true;
	}

private void manageTaskFailure(String reason) {
		logWarning(routineLogChiefOrderLine("Routine pass did not complete: " + reason));

		LocalDateTime retryTime = LocalDateTime.now().plusMinutes(ERROR_RETRY_MINUTES_VALUE);
		reschedule(retryTime);

		logInfo(routineLogChiefOrderLine("Task rescheduled to retry in " + ERROR_RETRY_MINUTES_VALUE + " minutes"));
	}

	/**
	 * Finds this order on the shelf and opens it when it is actually available.
	 *
	 * <p>matt, 2026-08-08, watching a live run: Urgent Mobilization was plainly available — red
	 * dot, no cooldown banner — and the bot skipped it twice in one pass. Cause was the icon
	 * template: a 40px crop of dark book art that does not match reliably. Worse, a miss was
	 * logged as "not detected or currently on cooldown", so a broken template read exactly like
	 * normal cooldown behaviour and hid itself.</p>
	 *
	 * <p>This reads the shelf the way a person does. Each order occupies a fixed slot in a 2x3
	 * grid, the label beneath it OCRs cleanly ("Urgent Mobilization", "Rush Job"), and an
	 * unavailable order wears an "On cooldown hh:mm:ss" banner across its cover. So the slot is
	 * located by its label and availability comes from the banner, not from whether an icon
	 * happened to match.</p>
	 */
	/** What the shelf told us about this order. */
	private enum ShelfVerdict {
		/** Cover is clean — the order can be opened and enacted right now. */
		AVAILABLE,
		/** Cover carried a countdown; the next run is already booked from it. */
		SCHEDULED_FROM_SCREEN,
		/** Nothing usable was read — fall back to the generic retry. */
		UNAVAILABLE
	}

	private ShelfVerdict chooseOrderType() {
		sleepTask(1500);

		logInfo(routineLogChiefOrderLine("Scanning for Chief Order type: " + chiefOrderType.getDescription()));

		OrderSlot slot = locateSlotByLabel();
		if (slot == null) {
			logWarning(routineLogChiefOrderLine(chiefOrderType.getDescription()
					+ " label not found on the shelf — the menu may not have opened."));
			return ShelfVerdict.UNAVAILABLE;
		}

		String cover = readCoverText(slot.coverTopLeft(), slot.coverBottomRight());

		// matt, 2026-08-09: an "Active hh:mm:ss" cover is the order currently running, not one
		// waiting to be used. The old check only looked for the word "cooldown", so an active
		// order read as available: the routine opened the book, found no Enact button (the page
		// says "On cooldown: 07:35:22"), and logged a failure. Come back when the effect ends —
		// the cover will have flipped to a cooldown banner by then and can be read properly.
		if (cover != null && cover.toLowerCase().contains("active")) {
			LocalDateTime recheck = remainingFrom(cover)
					.map(d -> LocalDateTime.now().plus(d).plusSeconds(30))
					.orElseGet(() -> LocalDateTime.now().plusMinutes(ERROR_RETRY_MINUTES_VALUE));
			reschedule(recheck);
			logInfo(routineLogChiefOrderLine(chiefOrderType.getDescription()
					+ " is currently active — re-reading the shelf at " + recheck + "."));
			return ShelfVerdict.SCHEDULED_FROM_SCREEN;
		}

		if (cover != null && cover.toLowerCase().contains("cooldown")) {
			// matt, 2026-08-08: the banner states exactly when this order frees up, so use it
			// instead of the hard-coded cooldownHours guess. Previously a cooled-down order was
			// retried every 10 minutes regardless, and an available one could sit behind a stale
			// fixed interval for hours — the timer was on screen the whole time, unread.
			java.util.Optional<java.time.Duration> remaining = remainingFrom(cover);
			if (remaining.isPresent()) {
				LocalDateTime readyAt = LocalDateTime.now().plus(remaining.get());
				reschedule(readyAt);
				logInfo(routineLogChiefOrderLine(chiefOrderType.getDescription()
						+ " on cooldown — next run scheduled from the screen at " + readyAt + "."));
				return ShelfVerdict.SCHEDULED_FROM_SCREEN;
			}

			logInfo(routineLogChiefOrderLine(chiefOrderType.getDescription()
					+ " is on cooldown (" + cover.replaceAll("\\s+", " ").trim() + ")."));
			return ShelfVerdict.UNAVAILABLE;
		}

		logInfo(routineLogChiefOrderLine(chiefOrderType.getDescription() + " is available — opening it."));
		tapNear(slot.tapPoint());
		sleepTask(1500);

		return ShelfVerdict.AVAILABLE;
	}

	/** Pulls the countdown out of a shelf banner, whether it reads "Active" or "On cooldown". */
	private java.util.Optional<java.time.Duration> remainingFrom(String cover) {
		java.util.regex.Matcher m = COOLDOWN_REMAINING.matcher(cover.replace('-', ':'));
		if (!m.find()) {
			return java.util.Optional.empty();
		}
		String stamp = m.group(1);
		if (stamp.indexOf(':') == 1) {
			stamp = "0" + stamp;
		}
		try {
			return java.util.Optional.of(GameTimeUtils.parseDuration(stamp));
		} catch (RuntimeException ex) {
			return java.util.Optional.empty();
		}
	}

	/** One position on the 2x3 Chief Order shelf. */
	private record OrderSlot(PointData coverTopLeft, PointData coverBottomRight,
			PointData labelTopLeft, PointData labelBottomRight, PointData tapPoint) {
	}

	/**
	 * The six shelf positions, calibrated from a live 720x1280 capture. Left-column books span
	 * x 120-320, right column x 395-600; rows sit ~320px apart.
	 */
	private static final java.util.List<OrderSlot> SHELF_SLOTS = java.util.List.of(
			new OrderSlot(new PointData(120, 290), new PointData(320, 370),
					new PointData(110, 452), new PointData(335, 494), new PointData(220, 330)),
			new OrderSlot(new PointData(395, 290), new PointData(600, 370),
					new PointData(385, 452), new PointData(610, 494), new PointData(497, 330)),
			new OrderSlot(new PointData(120, 610), new PointData(320, 690),
					new PointData(110, 775), new PointData(335, 817), new PointData(220, 650)),
			new OrderSlot(new PointData(395, 610), new PointData(600, 690),
					new PointData(385, 775), new PointData(610, 817), new PointData(497, 650)),
			new OrderSlot(new PointData(120, 930), new PointData(320, 1010),
					new PointData(105, 1098), new PointData(335, 1140), new PointData(220, 970)),
			new OrderSlot(new PointData(395, 930), new PointData(600, 1010),
					new PointData(385, 1098), new PointData(610, 1140), new PointData(497, 970)));

	private OrderSlot locateSlotByLabel() {
		// Match the first distinctive word so a partial OCR still resolves the slot — "Urgent",
		// "Rush" and "Productivity" are each unique across the six shelf entries.
		String needle = chiefOrderType.getDescription().split(" ")[0].toLowerCase();

		for (OrderSlot slot : SHELF_SLOTS) {
			String label = readSlotText(slot.labelTopLeft(), slot.labelBottomRight());
			if (label != null && label.toLowerCase().contains(needle)) {
				return slot;
			}
		}
		return null;
	}

	/**
	 * Reads a book cover, which stacks the state over the countdown on two lines.
	 *
	 * <p>The default OCR path is single-line (page-segmentation mode 7) and returns nothing at all
	 * for a two-line crop — so a cooled-down order looked exactly like an available one and the
	 * routine walked straight into an enact attempt that could not succeed. Uniform-block mode
	 * reads the same crop cleanly.</p>
	 */
	private String readCoverText(PointData topLeft, PointData bottomRight) {
		try {
			return stringHelper.attemptRecognition(topLeft, bottomRight, 2, 200L,
					dev.frostguard.api.domain.OcrSettingsData.forTextBlock(),
					s -> s != null && !s.isBlank(), String::trim);
		} catch (Exception ex) {
			return null;
		}
	}

	private String readSlotText(PointData topLeft, PointData bottomRight) {
		try {
			return stringHelper.attemptRecognition(topLeft, bottomRight, 2, 200L, null,
					s -> s != null && !s.isBlank(), String::trim);
		} catch (Exception ex) {
			return null;
		}
	}

private boolean enactOrderFlow() {
		sleepTask(1500);


		logInfo(routineLogChiefOrderLine("Scanning for Chief Order Enact button"));

		ImageSearchResultData enactButton = templateSearchHelper.locatePattern(
				TemplatesEnum.CHIEF_ORDER_ENACT_BUTTON,
				SearchConfigConstants.DEFAULT_SINGLE);

		if (!enactButton.isFound()) {
			logError(routineLogChiefOrderLine("Chief Order Enact button not detected"));
			return false;
		}

		logInfo(routineLogChiefOrderLine("Enact button detected. Pressing to enact order"));
		tapInside(enactButton);
		sleepTask(1000);


		pressBack();
		sleepTask(5000);


		logInfo(routineLogChiefOrderLine(chiefOrderType.getDescription() + " activated finished cleanly"));
		return true;
	}

private void queueNextRun() {
		LocalDateTime nextExecutionTime = LocalDateTime.now()
				.plusHours(chiefOrderType.getCooldownHours());

		reschedule(nextExecutionTime);

		logInfo(routineLogChiefOrderLine("Task completed finished cleanly. Next execution in " +
				chiefOrderType.getCooldownHours() + " hours"));
	}
}
