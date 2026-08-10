package dev.frostguard.tasks.alliance;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.helper.AllianceChampionshipHelper;
import dev.frostguard.engine.helper.NavigationHelper.EventMenu;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.helper.TimeWindowHelper;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import static dev.frostguard.api.configs.TemplatesEnum.*;

public class AllianceChampionshipRoutine extends DelayedTask {

private static final PointData INFANTRY_INPUT_TL_VALUE = new PointData(583, 519);

private static final PointData INFANTRY_INPUT_BR_VALUE = new PointData(603, 531);

private static final PointData LANCERS_INPUT_TL_VALUE = new PointData(583, 666);

private static final PointData LANCERS_INPUT_BR_VALUE = new PointData(603, 685);

private static final PointData MARKSMEN_INPUT_TL_VALUE = new PointData(583, 815);

private static final PointData MARKSMEN_INPUT_BR_VALUE = new PointData(603, 829);

private static final PointData BALANCE_BUTTON_TL_VALUE = new PointData(308, 1170);

private static final PointData BALANCE_BUTTON_BR_VALUE = new PointData(336, 1209);

private static final PointData CONFIRM_TROOPS_BUTTON_TL_VALUE = new PointData(304, 965);

private static final PointData CONFIRM_TROOPS_BUTTON_BR_VALUE = new PointData(423, 996);

private static final PointData DEPLOY_BUTTON_TL_VALUE = new PointData(500, 1200);

private static final PointData DEPLOY_BUTTON_BR_VALUE = new PointData(600, 1230);

private static final int TEMPLATE_SEARCH_RETRIES_VALUE = 3;

private static final int TEXT_CLEAR_BACKSPACE_COUNT_VALUE = 4;

private static final int NAVIGATION_FAILURE_RETRY_MINUTES_VALUE = 5;

private static final boolean DEFAULT_OVERRIDE_DEPLOY_VALUE = false;

private static final int DEFAULT_INFANTRY_PERCENTAGE_VALUE = 50;

private static final int DEFAULT_LANCERS_PERCENTAGE_VALUE = 20;

private static final int DEFAULT_MARKSMEN_PERCENTAGE_VALUE = 30;

private static final DeploymentPosition DEFAULT_POSITION_VALUE = DeploymentPosition.CENTER;

private static final String DEFAULT_FLAG_VALUE = "No Flag";

private boolean overrideDeploy;

private int infantryPercentage;

private int lancersPercentage;

private int markmenPercentage;

private DeploymentPosition position;

private Integer flagNumber;

public AllianceChampionshipRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

@Override
    protected void execute() {

        if (!confirmExecutionWindow()) {


            deferNextWindow();
            return;
        }

        hydrateConfiguration();

        logWindowInformationFlow();

        if (!reachChampionshipEvent()) {
            manageNavigationFailure("Failed to navigate to championship event");
            return;
        }

        DeploymentStatusShape status = inspectDeploymentStatus();

        if (status == null) {
            manageNavigationFailure("Failed to determine deployment status");
            return;
        }

        manageDeploymentByStatus(status);
    }

@Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.ANY;
    }

@Override
    public boolean provideDailyMissionProgress() {
        return false;
    }

@Override
    protected boolean consumesStamina() {
        return false;
    }

private enum DeploymentStatusShape {

        NEW_DEPLOYMENT,


        EXISTING_DEPLOYMENT
    }

public enum DeploymentPosition {

        LEFT("LEFT"),


        CENTER("CENTER"),


        RIGHT("RIGHT");

        private final String value;


        DeploymentPosition(String value) {
            this.value = value;
        }


        public static DeploymentPosition fromString(String value) {
            if (value == null || value.trim().isEmpty()) {
                return CENTER;
            }

            for (DeploymentPosition position : DeploymentPosition.values()) {
                if (position.value.equalsIgnoreCase(value.trim())) {
                    return position;
                }
            }

            return CENTER;
        }


        public String getValue() {
            return value;
        }

        @Override
        public String toString() {
            return value;
        }
    }

private String routineLogAllianceChampionshipLine(String note) {
        return "AllianceChampionshipRoutine | " + note;
    }

private boolean openUpDeploymentConfiguration(boolean isUpdate) {
        TemplatesEnum buttonTemplate = isUpdate
                ? ALLIANCE_CHAMPIONSHIP_UPDATE_TROOPS_BUTTON
                : ALLIANCE_CHAMPIONSHIP_DISPATCH_TROOPS_BUTTON;

        String buttonName = isUpdate ? "Update" : "Dispatch";

        ImageSearchResultData configButton = templateSearchHelper.locatePattern(
                buttonTemplate,
                SearchConfig.builder()
                        .withThreshold(90)
                        .withMaxAttempts(TEMPLATE_SEARCH_RETRIES_VALUE)
                        .withDelay(100L)
                        .build());

        if (!configButton.isFound()) {
            logWarning(routineLogAllianceChampionshipLine(buttonName + " button not detected. Cannot proceed with deployment."));
            return false;
        }

        tapInside(configButton);
        sleepTask(200);


        return true;
    }

private void configureTroopPercentagesFlow() {
        touchBalanceButton();
        resetTroopInputsFlow();
        setTroopPercentagesFlow();
        confirmTroopPercentagesFlow();
    }

private boolean configureFormationFlow() {
        if (flagNumber == null) {
            logInfo(routineLogAllianceChampionshipLine("Formation setup: no flag configured, using custom troop mix"));
            configureTroopPercentagesFlow();
            return true;
        }

        logInfo(routineLogAllianceChampionshipLine("Formation setup: selecting flag #" + flagNumber));
        if (!marchHelper.selectFlag(flagNumber)) {
            logWarning(routineLogAllianceChampionshipLine(
                    "Formation setup: configured flag #" + flagNumber + " is locked on this profile"));
            return false;
        }
        return true;
    }

private void manageDeploymentByStatus(DeploymentStatusShape status) {
        switch (status) {
            case NEW_DEPLOYMENT:
                logInfo(routineLogAllianceChampionshipLine("Proceeding with new deployment"));
                if (deployTroopsFlow(false)) {
                    logInfo(routineLogAllianceChampionshipLine("Troops deployed finished cleanly"));
                    deferNextWindow();
                } else {
                    manageNavigationFailure("Failed to deploy troops");
                }
                break;

            case EXISTING_DEPLOYMENT:
                if (overrideDeploy) {
                    logInfo(routineLogAllianceChampionshipLine("Override is enabled. Proceeding to update deployment"));
                    if (deployTroopsFlow(true)) {
                        logInfo(routineLogAllianceChampionshipLine("Troops deployment updated finished cleanly"));
                        deferNextWindow();
                    } else {
                        manageNavigationFailure("Failed to update deployment");
                    }
                } else {
                    logInfo(routineLogAllianceChampionshipLine("Override is disabled. Skipping deployment."));
                    deferNextWindow();
                }
                break;
        }
    }

private void logWindowInformationFlow() {
        TimeWindowHelper.WindowResult window = resolveWindowState();
        LocalDateTime windowStart = LocalDateTime.ofInstant(
                window.getCurrentWindowStart(),
                ZoneId.of("UTC"));
        LocalDateTime windowEnd = LocalDateTime.ofInstant(
                window.getCurrentWindowEnd(),
                ZoneId.of("UTC"));

        logInfo(routineLogAllianceChampionshipLine("Championship window: " + windowStart.format(DATETIME_FORMATTER) + " to "
                + windowEnd.format(DATETIME_FORMATTER) + " (UTC)"));
    }

private void setTroopPercentagesFlow() {
        setTroopPercentageFlow(INFANTRY_INPUT_TL_VALUE, INFANTRY_INPUT_BR_VALUE, infantryPercentage, "Infantry");
        setTroopPercentageFlow(LANCERS_INPUT_TL_VALUE, LANCERS_INPUT_BR_VALUE, lancersPercentage, "Lancers");
        setTroopPercentageFlow(MARKSMEN_INPUT_TL_VALUE, MARKSMEN_INPUT_BR_VALUE, markmenPercentage, "Marksmen");
    }

private boolean managePositionSwitching(AreaData deploymentArea) {
        ImageSearchResultData switchLine = templateSearchHelper.locatePattern(
                ALLIANCE_CHAMPIONSHIP_SWITCH_LINE_BUTTON,
                SearchConfig.builder()
                        .withThreshold(90)
                        .withMaxAttempts(TEMPLATE_SEARCH_RETRIES_VALUE)
                        .withDelay(100L)
                        .build());

        if (switchLine.isFound()) {
            logInfo(routineLogAllianceChampionshipLine("Current deployment position does not match desired. Switching position."));
            tapInside(switchLine);
            sleepTask(1000);

            tapInside(deploymentArea.topLeft(), deploymentArea.bottomRight(), 1, 500);
            sleepTask(500);

        } else {
            logInfo(routineLogAllianceChampionshipLine("Current deployment position matches desired. Zero position change needed."));
        }

        return true;
    }

private void manageNavigationFailure(String context) {
        logWarning(routineLogAllianceChampionshipLine(context + ". Retrying in " + NAVIGATION_FAILURE_RETRY_MINUTES_VALUE + " minutes."));
        reschedule(LocalDateTime.now().plusMinutes(NAVIGATION_FAILURE_RETRY_MINUTES_VALUE));
    }

private DeploymentStatusShape inspectDeploymentStatus() {
        ImageSearchResultData troopsButton = templateSearchHelper.locatePattern(
                ALLIANCE_CHAMPIONSHIP_TROOPS_BUTTON,
                SearchConfig.builder()
                        .withThreshold(90)
                        .withMaxAttempts(TEMPLATE_SEARCH_RETRIES_VALUE)
                        .withDelay(200L)
                        .build());

        if (troopsButton.isFound()) {
            logInfo(routineLogAllianceChampionshipLine("Active deployment detected"));
            if (overrideDeploy) {
                tapInside(troopsButton.getPoint(), troopsButton.getPoint(), 3, 100);
                sleepTask(1000);
            }
            return DeploymentStatusShape.EXISTING_DEPLOYMENT;
        }

        ImageSearchResultData registerButton = templateSearchHelper.locatePattern(
                ALLIANCE_CHAMPIONSHIP_REGISTER_BUTTON,
                SearchConfig.builder()
                        .withThreshold(90)
                        .withMaxAttempts(TEMPLATE_SEARCH_RETRIES_VALUE)
                        .withDelay(200L)
                        .build());

        if (registerButton.isFound()) {
            logInfo(routineLogAllianceChampionshipLine("Zero active deployment detected"));
            tapInside(registerButton.getPoint(), registerButton.getPoint(), 3, 100);
            sleepTask(1000);
            return DeploymentStatusShape.NEW_DEPLOYMENT;
        } else {
            logWarning(routineLogAllianceChampionshipLine("Neither Register nor Troops button detected"));
            return null;
        }

    }

private void setTroopPercentageFlow(PointData topLeft, PointData bottomRight, int percentage, String troopType) {
        tapInside(topLeft, bottomRight, 1, 400);
        sleepTask(100);

        emuManager.writeText(EMULATOR_NUMBER, String.valueOf(percentage));
        sleepTask(200);

        logDebug(routineLogAllianceChampionshipLine(troopType + " percentage set to " + percentage + "%"));
    }

private boolean reachChampionshipEvent() {
        logInfo(routineLogAllianceChampionshipLine("Moving to Alliance Championship event..."));

        boolean success = navigationHelper.navigateToEventMenu(EventMenu.ALLIANCE_CHAMPIONSHIP);

        if (!success) {
            logWarning(routineLogAllianceChampionshipLine("Could not navigate to Alliance Championship event"));
            return false;
        }

        sleepTask(2000);
        return true;
    }

private boolean confirmExecutionWindow() {
        if (!hasInsideWindow()) {
            logWarning(routineLogAllianceChampionshipLine("Execute called OUTSIDE valid window. Planning next run..."));
            deferNextWindow();
            return false;
        }

        logInfo(routineLogAllianceChampionshipLine("Confirmed: We are INSIDE a valid execution window"));
        return true;
    }

private AreaData resolveDeploymentArea(DeploymentPosition pos) {
        return switch (pos) {
            case LEFT -> new AreaData(new PointData(40, 900), new PointData(220, 1000));
            case CENTER -> new AreaData(new PointData(290, 900), new PointData(450, 1000));
            case RIGHT -> new AreaData(new PointData(510, 900), new PointData(680, 1000));
        };
    }

private void confirmTroopPercentagesFlow() {
        tapInside(CONFIRM_TROOPS_BUTTON_TL_VALUE, CONFIRM_TROOPS_BUTTON_BR_VALUE, 1, 500);
        sleepTask(500);
    }

private void deployFormationFlow() {
        tapInside(DEPLOY_BUTTON_TL_VALUE, DEPLOY_BUTTON_BR_VALUE, 1, 500);
        sleepTask(1000);

    }

private boolean resolveConfigBoolean(ConfigurationKeyEnum key, boolean defaultValue) {
        Boolean value = profile.getConfig(key, Boolean.class);
        return (value != null) ? value : defaultValue;
    }

private void resetTroopInputsFlow() {
        clearTroopInputFlow(INFANTRY_INPUT_TL_VALUE, INFANTRY_INPUT_BR_VALUE, "Infantry");
        clearTroopInputFlow(LANCERS_INPUT_TL_VALUE, LANCERS_INPUT_BR_VALUE, "Lancers");
        clearTroopInputFlow(MARKSMEN_INPUT_TL_VALUE, MARKSMEN_INPUT_BR_VALUE, "Marksmen");
    }

private void hydrateConfiguration() {
        this.overrideDeploy = resolveConfigBoolean(
                ConfigurationKeyEnum.ALLIANCE_CHAMPIONSHIP_OVERRIDE_DEPLOY_BOOL,
                DEFAULT_OVERRIDE_DEPLOY_VALUE);

        this.infantryPercentage = resolveConfigInt(
                ConfigurationKeyEnum.ALLIANCE_CHAMPIONSHIP_INFANTRY_PERCENTAGE_INT,
                DEFAULT_INFANTRY_PERCENTAGE_VALUE);

        this.lancersPercentage = resolveConfigInt(
                ConfigurationKeyEnum.ALLIANCE_CHAMPIONSHIP_LANCERS_PERCENTAGE_INT,
                DEFAULT_LANCERS_PERCENTAGE_VALUE);

        this.markmenPercentage = resolveConfigInt(
                ConfigurationKeyEnum.ALLIANCE_CHAMPIONSHIP_MARKSMANS_PERCENTAGE_INT,
                DEFAULT_MARKSMEN_PERCENTAGE_VALUE);

        String positionValue = resolveConfigString(
                ConfigurationKeyEnum.ALLIANCE_CHAMPIONSHIP_POSITION_STRING,
                DEFAULT_POSITION_VALUE.getValue());
        this.position = DeploymentPosition.fromString(positionValue);

        String flagValue = resolveConfigString(
                ConfigurationKeyEnum.ALLIANCE_CHAMPIONSHIP_FLAG_STRING,
                DEFAULT_FLAG_VALUE);
        this.flagNumber = parseFlagNumber(flagValue);
        if (flagNumber == null && !isNoFlagValue(flagValue)) {
            logWarning(routineLogAllianceChampionshipLine(
                    "Invalid flag configuration '" + flagValue + "'. Using custom troop mix."));
        }

        logDebug(routineLogAllianceChampionshipLine(String.format(
                "Configuration loaded - Override: %s, Position: %s, Flag: %s, Infantry: %d%%, Lancers: %d%%, Marksmen: %d%%",
                overrideDeploy, position, flagNumber == null ? DEFAULT_FLAG_VALUE : flagNumber,
                infantryPercentage, lancersPercentage, markmenPercentage)));
    }

static Integer parseFlagNumber(String value) {
        if (isNoFlagValue(value)) {
            return null;
        }
        try {
            int flag = Integer.parseInt(value.trim());
            return flag >= 1 && flag <= 8 ? flag : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

private static boolean isNoFlagValue(String value) {
        return value == null || value.isBlank() || DEFAULT_FLAG_VALUE.equalsIgnoreCase(value.trim());
    }

private String resolveConfigString(ConfigurationKeyEnum key, String defaultValue) {
        String value = profile.getConfig(key, String.class);
        return (value != null) ? value : defaultValue;
    }

private void clearTroopInputFlow(PointData topLeft, PointData bottomRight, String troopType) {
        tapInside(topLeft, bottomRight, 1, 200);
        sleepTask(100);

        emuManager.clearText(EMULATOR_NUMBER, TEXT_CLEAR_BACKSPACE_COUNT_VALUE);
        sleepTask(200);

        logDebug(routineLogAllianceChampionshipLine(troopType + " input cleared"));
    }

private void touchBalanceButton() {
        tapInside(BALANCE_BUTTON_TL_VALUE, BALANCE_BUTTON_BR_VALUE, 1, 500);
        sleepTask(300);

    }

private TimeWindowHelper.WindowResult resolveWindowState() {
        return AllianceChampionshipHelper.calculateWindow();
    }

private int resolveConfigInt(ConfigurationKeyEnum key, int defaultValue) {
        Integer value = profile.getConfig(key, Integer.class);
        return (value != null) ? value : defaultValue;
    }

private boolean deployTroopsFlow(boolean isUpdate) {
        navigationHelper.clearEventTabSelection();

        AreaData deploymentArea = resolveDeploymentArea(position);
        tapInside(deploymentArea.topLeft(), deploymentArea.bottomRight(), 1, 500);
        sleepTask(500);


        if (isUpdate) {
            if (!managePositionSwitching(deploymentArea)) {
                return false;
            }
        }

        if (!openUpDeploymentConfiguration(isUpdate)) {
            return false;
        }

        if (!configureFormationFlow()) {
            return false;
        }
        deployFormationFlow();

        return true;
    }

private boolean hasInsideWindow() {
        TimeWindowHelper.WindowResult result = AllianceChampionshipHelper.calculateWindow();
        return result.getState() == TimeWindowHelper.WindowState.INSIDE;
    }

private void deferNextWindow() {
        TimeWindowHelper.WindowResult result = resolveWindowState();
        Instant nextExecutionInstant = result.getNextWindowStart();

        LocalDateTime nextExecutionLocal = LocalDateTime.ofInstant(
                nextExecutionInstant,
                ZoneId.systemDefault());

        LocalDateTime nextExecutionUtc = LocalDateTime.ofInstant(
                nextExecutionInstant,
                ZoneId.of("UTC"));

        logInfo(routineLogAllianceChampionshipLine("Planning next run Alliance Championship for (UTC): " + nextExecutionUtc));
        logInfo(routineLogAllianceChampionshipLine("Planning next run Alliance Championship for (Local): " + nextExecutionLocal));

        reschedule(nextExecutionLocal);
    }
}
