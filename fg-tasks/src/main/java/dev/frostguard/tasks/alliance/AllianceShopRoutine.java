package dev.frostguard.tasks.alliance;

import dev.frostguard.api.configs.AllianceShopItemEnum;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.*;
import dev.frostguard.engine.config.PriorityConfigResolver;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.vision.convert.RegexNumberParser;
import java.awt.*;
import java.util.List;
import java.util.regex.Pattern;
import static dev.frostguard.api.configs.TemplatesEnum.*;

public class AllianceShopRoutine extends DelayedTask {

private static final PointData ALLIANCE_BUTTON_TOP_LEFT_VALUE = new PointData(493, 1187);

private static final PointData ALLIANCE_BUTTON_BOTTOM_RIGHT_VALUE = new PointData(561, 1240);

private static final PointData SHOP_DETAILS_TOP_LEFT_VALUE = new PointData(580, 30);

private static final PointData SHOP_DETAILS_BOTTOM_RIGHT_VALUE = new PointData(670, 50);

private static final PointData COINS_TOP_LEFT_VALUE = new PointData(272, 257);

private static final PointData COINS_BOTTOM_RIGHT_VALUE = new PointData(443, 285);

private static final PointData CLOSE_TOP_LEFT_VALUE = new PointData(270, 30);

private static final PointData CLOSE_BOTTOM_RIGHT_VALUE = new PointData(280, 80);

private static final PointData MAX_BUTTON_TOP_LEFT_LIMIT = new PointData(596, 690);

private static final PointData MAX_BUTTON_BOTTOM_RIGHT_LIMIT = new PointData(626, 717);

private static final PointData PLUS_BUTTON_TOP_LEFT_VALUE = new PointData(397, 691);

private static final PointData PLUS_BUTTON_BOTTOM_RIGHT_VALUE = new PointData(425, 716);

private static final PointData CONFIRM_BUTTON_TOP_LEFT_VALUE = new PointData(330, 815);

private static final PointData CONFIRM_BUTTON_BOTTOM_RIGHT_VALUE = new PointData(420, 840);

private static final PointData WEEKLY_TAB_TOP_LEFT_VALUE = new PointData(450, 1233);

private static final PointData WEEKLY_TAB_BOTTOM_RIGHT_VALUE = new PointData(590, 1263);

private static final PointData DAILY_TAB_TOP_LEFT_VALUE = new PointData(150, 1233);

private static final PointData DAILY_TAB_BOTTOM_RIGHT_VALUE = new PointData(290, 1263);

private static final int GRID_START_X_VALUE = 27;

private static final int GRID_START_Y_VALUE = 192;

private static final int ITEM_WIDTH_VALUE = 215;

private static final int ITEM_HEIGHT_VALUE = 266;

private static final int SPACING_X_VALUE = 5;

private static final int SPACING_Y_VALUE = 19;

private static final int EXPERT_Y_OFFSET_VALUE = 121;

private static final int CARDS_PER_ROW_VALUE = 3;

private static final int MAX_CARD_POSITIONS_LIMIT = 9;

private static final int PRICE_OFFSET_X_VALUE = 54;

private static final int PRICE_OFFSET_Y_VALUE = 210;

private static final int PRICE_WIDTH_VALUE = 158;

private static final int PRICE_HEIGHT_VALUE = 48;

private static final int QUANTITY_OFFSET_X_VALUE = 65;

private static final int QUANTITY_OFFSET_Y_VALUE = 165;

private static final int QUANTITY_WIDTH_VALUE = 100;

private static final int QUANTITY_HEIGHT_VALUE = 35;

private static final int THRESHOLD_SHOP_BUTTON_VALUE = 90;

private static final int DISCOUNT_TOLERANCE_VALUE = 4;

private static final int DEFAULT_QUANTITY_VALUE = 1;

private static final int RETRIES_SHOP_BUTTON_VALUE = 5;

private static final int RETRIES_SOLD_OUT_VALUE = 1;

private static final int RETRIES_ITEM_SEARCH_VALUE = 1;

private static final int RETRIES_OCR_VALUE = 5;

private Integer currentCoins;

private Integer minCoins;

private Integer minDiscountPercent;

private boolean expertUnlocked;

public AllianceShopRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
        super(profile, tpDailyTask);
        this.recurring = false;
    }

@Override
    protected void execute() {
        hydrateConfiguration();

        if (!reachShopAndReadCoins()) {
            logWarning(routineLogAllianceShopLine("Could not navigate to shop or read coins. Task ending."));
            setRecurring(false);
            return;
        }

        if (!validateMinimumCoinsFlow()) {
            logInfo(routineLogAllianceShopLine("Current coins below minimum threshold. Task ending."));
            setRecurring(false);
            return;
        }

        List<PriorityItemData> enabledPriorities = hydrateEnabledPriorities();
        if (enabledPriorities.isEmpty()) {
            logWarning(routineLogAllianceShopLine("Zero enabled purchase priorities configured. Please enable items in the Alliance Shop ocrPreset."));
            setRecurring(false);
            return;
        }

        logPrioritiesFlow(enabledPriorities);
        detectExpertUnlockFlow();
        handlePurchases(enabledPriorities);

        setRecurring(false);

    }

@Override
    public boolean provideDailyMissionProgress() {
        return false;
    }

@Override
    protected boolean consumesStamina() {
        return false;
    }

private enum BuyResult {

        PURCHASED,


        SOLD_OUT,


        INSUFFICIENT_DISCOUNT,


        CANT_AFFORD,


        ERROR
    }

private static class PriceCheck {
        private final BuyResult outcome;
        private final int price;
        private final int availableQuantity;


        PriceCheck(BuyResult outcome, int price, int availableQuantity) {
            this.outcome = outcome;
            this.price = price;
            this.availableQuantity = availableQuantity;
        }


        boolean isValid() {
            return outcome == BuyResult.PURCHASED;
        }


        BuyResult getOutcome() {
            return outcome;
        }


        int getPrice() {
            return price;
        }


        int getAvailableQuantity() {
            return availableQuantity;
        }
    }

private String routineLogAllianceShopLine(String note) {
        return "AllianceShopRoutine | " + note;
    }

private boolean meetsDiscountThresholdFlow(AllianceShopItemEnum shopItem, int itemPrice) {
        int basePrice = shopItem.getBasePrice();
        if (basePrice <= itemPrice) {


            logInfo(routineLogAllianceShopLine("Item " + shopItem.getDisplayName() +
                    " has no discount (base: " + basePrice + ", current: " + itemPrice + ")"));
            return false;
        }

        double discountPercent = ((basePrice - itemPrice) / (double) basePrice) * 100;
        double minDiscountThreshold = minDiscountPercent - DISCOUNT_TOLERANCE_VALUE;

        logInfo(routineLogAllianceShopLine("Item base price: " + basePrice +
                ", current price: " + itemPrice +
                ", discount: " + String.format("%.2f", discountPercent) + "%"));

        if (discountPercent < minDiscountThreshold) {
            logInfo(routineLogAllianceShopLine("Discount insufficient (required: " + minDiscountThreshold + "%), skipping purchase"));
            return false;
        }

        return true;
    }

private AllianceShopItemEnum locateShopItemByIdentifier(String identifier) {
        try {
            return AllianceShopItemEnum.valueOf(identifier);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

private boolean hasReachedMinimumCoinsFlow() {
        return currentCoins < minCoins;
    }

private boolean validateMinimumCoinsFlow() {
        if (currentCoins < minCoins) {
            logInfo(routineLogAllianceShopLine("Current alliance coins (" + currentCoins +
                    ") are less than the minimum required (" + minCoins + "). Skipping purchases."));
            return false;
        }
        return true;
    }

private List<PriorityItemData> hydrateEnabledPriorities() {
        return PriorityConfigResolver.activeRankings(
                profile,
                ConfigurationKeyEnum.ALLIANCE_SHOP_PRIORITIES_STRING);
    }

private int computePurchaseQuantity(int cardIndex, AllianceShopItemEnum shopItem, int itemPrice) {
        Integer availableQuantity = scanAvailableQuantity(cardIndex, shopItem);
        if (availableQuantity == null) {
            availableQuantity = DEFAULT_QUANTITY_VALUE;
        }

        return computeBuyQtyFlow(currentCoins, minCoins, itemPrice, availableQuantity);
    }

private void detectExpertUnlockFlow() {
        ImageSearchResultData expertIcon = templateSearchHelper.locatePattern(
                TemplatesEnum.ALLIANCE_SHOP_EXPERT_ICON,
                SearchConfig.builder()
                        .withThreshold(90)
                        .withMaxAttempts(3)
                        .build());

        if (expertIcon.isFound()) {
            expertUnlocked = true;
            logInfo(routineLogAllianceShopLine("Expert unlocked detected in Alliance Shop. Adjusting item coordinates accordingly."));
        } else {
            logDebug(routineLogAllianceShopLine("Expert not unlocked. Using standard coordinates."));
        }
    }

private PriceCheck validateItemPriceFlow(int cardIndex, AllianceShopItemEnum shopItem) {
        Integer itemPrice = scanItemPrice(cardIndex, shopItem);

        if (itemPrice == null) {
            return new PriceCheck(BuyResult.ERROR, 0, 0);
        }

        if (!meetsDiscountThresholdFlow(shopItem, itemPrice)) {
            return new PriceCheck(BuyResult.INSUFFICIENT_DISCOUNT, itemPrice, 0);
        }

        Integer availableQty = scanAvailableQuantity(cardIndex, shopItem);
        if (availableQty == null) {
            availableQty = DEFAULT_QUANTITY_VALUE;
        }

        return new PriceCheck(BuyResult.PURCHASED, itemPrice, availableQty);
    }

private Integer scanAvailableQuantity(int cardIndex, AllianceShopItemEnum shopItem) {
        AreaData quantityArea = resolveQuantityArea(cardIndex);

        Integer availableQuantity = integerHelper.attemptRecognition(
                quantityArea.topLeft(),
                quantityArea.bottomRight(),
                RETRIES_OCR_VALUE,
                1000L,

                TesseractSettingsData.assembler()
                        .charWhitelist("0123456789")
                        .setTextColor(Color.white)
                        .stripBackground(true)
                        .build(),
                text -> RegexNumberParser.conformsTo(text, Pattern.compile(".*?(\\d+).*")),
                text -> RegexNumberParser.extractByPattern(text, Pattern.compile(".*?(\\d+).*")));

        if (availableQuantity == null) {
            logWarning(routineLogAllianceShopLine("Could not read available quantity for item: " +
                    shopItem.getDisplayName() + ". Assuming quantity of " + DEFAULT_QUANTITY_VALUE + "."));
        }

        return availableQuantity;
    }

private void refreshRemainingCoins(int qty, int itemPrice) {
        currentCoins -= qty * itemPrice;
    }

private boolean reachShopAndReadCoins() {
        logDebug(routineLogAllianceShopLine("Moving to Alliance Shop..."));

        tapInside(ALLIANCE_BUTTON_TOP_LEFT_VALUE, ALLIANCE_BUTTON_BOTTOM_RIGHT_VALUE);
        sleepTask(3000);


        ImageSearchResultData shopButton = templateSearchHelper.locatePattern(
                TemplatesEnum.ALLIANCE_SHOP_BUTTON,
                SearchConfig.builder()
                        .withThreshold(THRESHOLD_SHOP_BUTTON_VALUE)
                        .withMaxAttempts(RETRIES_SHOP_BUTTON_VALUE)
                        .build());

        if (!shopButton.isFound()) {
            logWarning(routineLogAllianceShopLine("Could not find Alliance Shop button"));
            return false;
        }

        logDebug(routineLogAllianceShopLine("Shop button detected at: " + shopButton.getPoint()));
        tapInside(shopButton.getPoint(), shopButton.getPoint(), 1, 1000);

        logDebug(routineLogAllianceShopLine("Entering shop details to read coins..."));
        tapInside(SHOP_DETAILS_TOP_LEFT_VALUE, SHOP_DETAILS_BOTTOM_RIGHT_VALUE, 1, 1000);

        currentCoins = scanCurrentCoins();

        if (currentCoins == null) {
            logWarning(routineLogAllianceShopLine("Could not read current alliance coins."));
            return false;
        }

        logInfo(routineLogAllianceShopLine("Current alliance coins: " + currentCoins + ". Minimum to save: " + minCoins));


        tapInside(CLOSE_TOP_LEFT_VALUE, CLOSE_BOTTOM_RIGHT_VALUE, 3, 200);

        return true;
    }

private boolean managePurchaseOutcome(
            BuyResult outcome,
            AllianceShopItemEnum shopItem,
            AllianceShopItemEnum.Availability tab) {

        switch (outcome) {
            case PURCHASED:
                logDebug(routineLogAllianceShopLine("Successfully purchased item"));
                StatisticsService.obtain().addToCounter(profile, "Alliance Shop Purchases", 1);
                break;
            case CANT_AFFORD:
                logInfo(routineLogAllianceShopLine("Cannot afford item " + shopItem.getDisplayName() +
                        ". Stopping further purchases."));
                return false;

            case SOLD_OUT:
                logInfo(routineLogAllianceShopLine("Item " + shopItem.getDisplayName() + " is sold out in " + tab +
                        " tab. Continuing with next tab/priority."));
                break;
            case INSUFFICIENT_DISCOUNT:
                logInfo(routineLogAllianceShopLine("Item " + shopItem.getDisplayName() +
                        " does not meet discount threshold in " + tab +
                        " tab. Continuing."));
                break;
            case ERROR:
            default:
                logWarning(routineLogAllianceShopLine("Unexpected error while attempting to purchase " +
                        shopItem.getDisplayName() + " in " + tab +
                        " tab. Continuing."));
                break;
        }

        return true;

    }

private void dismissPurchaseDialog() {
        tapInside(CLOSE_TOP_LEFT_VALUE, CLOSE_BOTTOM_RIGHT_VALUE, 3, 200);
    }

private void openUpPurchaseDialog(int cardIndex) {
        AreaData priceArea = resolvePriceArea(cardIndex);
        tapInside(priceArea.topLeft(), priceArea.bottomRight(), 1, 1500);
    }

private void hydrateConfiguration() {
        this.minCoins = profile.getConfig(
                ConfigurationKeyEnum.ALLIANCE_SHOP_MIN_COINS_INT,
                Integer.class);

        this.minDiscountPercent = profile.getConfig(
                ConfigurationKeyEnum.ALLIANCE_SHOP_MIN_PERCENTAGE_INT,
                Integer.class);


        this.expertUnlocked = false;

        logDebug(routineLogAllianceShopLine(String.format(
                "Configuration loaded - Min coins: %d, Min discount: %d%%",
                minCoins, minDiscountPercent)));
    }

private AreaData resolveQuantityArea(int cardNumber) {
        return resolveCardArea(cardNumber, QUANTITY_OFFSET_X_VALUE, QUANTITY_OFFSET_Y_VALUE, QUANTITY_WIDTH_VALUE, QUANTITY_HEIGHT_VALUE);
    }

private TemplatesEnum resolveItemTemplate(AllianceShopItemEnum item) {
        return switch (item) {
            case VIP_XP_100 -> ALLIANCE_SHOP_100_VIP_XP;
            case VIP_XP_10 -> ALLIANCE_SHOP_10_VIP_XP;
            case MARCH_RECALL -> ALLIANCE_SHOP_RECALL_MARCH;
            case ADVANCED_TELEPORT -> ALLIANCE_SHOP_ADVANCED_TELEPORT;
            case TERRITORY_TELEPORT -> ALLIANCE_SHOP_TERRITORY_TELEPORT;
            default -> null;
        };
    }

private void confirmPurchaseFlow() {
        tapInside(CONFIRM_BUTTON_TOP_LEFT_VALUE, CONFIRM_BUTTON_BOTTOM_RIGHT_VALUE, 1, 1000);
    }

private List<AllianceShopItemEnum.Availability> resolveTabsForItem(AllianceShopItemEnum item) {
        if (item.availability() == AllianceShopItemEnum.Availability.BOTH) {
            return List.of(AllianceShopItemEnum.Availability.WEEKLY, AllianceShopItemEnum.Availability.DAILY);
        }
        return List.of(item.availability());
    }

private boolean handlePriorityItem(AllianceShopItemEnum shopItem) {
        List<AllianceShopItemEnum.Availability> tabsToCheck = resolveTabsForItem(shopItem);

        for (AllianceShopItemEnum.Availability tab : tabsToCheck) {
            if (hasReachedMinimumCoinsFlow()) {
                logInfo(routineLogAllianceShopLine("Reached minimum coins threshold during multi-tab processing."));
                return true;

            }

            logInfo(routineLogAllianceShopLine("Inspecting " + shopItem.getDisplayName() + " in " + tab + " tab"));
            switchToTabFlow(tab);

            BuyResult outcome = handleItemInTab(shopItem, tab);

            if (!managePurchaseOutcome(outcome, shopItem, tab)) {


                return false;
            }
        }

        return true;
    }

private void logPrioritiesFlow(List<PriorityItemData> priorities) {
        logInfo(routineLogAllianceShopLine("Detected " + priorities.size() + " enabled purchase priorities:"));
        for (PriorityItemData priority : priorities) {
            logInfo(routineLogAllianceShopLine(" Priority " + priority.getPriority() + ": " +
                    priority.getName() + " (ID: " + priority.getIdentifier() + ")"));
        }
    }

private int computeBuyQtyFlow(int currentCoins, int minCoins, int itemPrice, int availableQuantity) {
        if (itemPrice <= 0) {
            return 0;
        }

        int affordable = (currentCoins - minCoins) / itemPrice;
        return Math.max(0, Math.min(availableQuantity, affordable));
    }

private Integer scanCurrentCoins() {
        return integerHelper.attemptRecognition(
                COINS_TOP_LEFT_VALUE,
                COINS_BOTTOM_RIGHT_VALUE,
                RETRIES_OCR_VALUE,
                200L,

                TesseractSettingsData.assembler()
                        .charWhitelist("0123456789")
                        .pageAnalysis(TesseractSettingsData.PageAnalysis.SINGLE_LINE)
                        .build(),
                text -> RegexNumberParser.conformsTo(text, Pattern.compile(".*?(\\d+).*")),
                text -> RegexNumberParser.extractByPattern(text, Pattern.compile(".*?(\\d+).*")));
    }

private AreaData resolvePriceArea(int cardNumber) {
        return resolveCardArea(cardNumber, PRICE_OFFSET_X_VALUE, PRICE_OFFSET_Y_VALUE, PRICE_WIDTH_VALUE, PRICE_HEIGHT_VALUE);
    }

private Integer resolveCardIndex(AllianceShopItemEnum item) {
        TemplatesEnum template = resolveItemTemplate(item);

        if (template != null) {
            Integer foundIndex = seekForItemCard(template);
            if (foundIndex != null) {
                return foundIndex;
            }
        }


        return switch (item) {
            case MYTHIC_HERO_SHARDS -> 1;
            case PET_FOOD -> 2;
            case PET_CHEST -> 3;
            case TRANSFER_PASS -> 4;
            default -> null;
        };
    }

private void switchToTabFlow(AllianceShopItemEnum.Availability tab) {
        switch (tab) {
            case WEEKLY -> tapInside(WEEKLY_TAB_TOP_LEFT_VALUE, WEEKLY_TAB_BOTTOM_RIGHT_VALUE, 3, 200);
            case DAILY -> tapInside(DAILY_TAB_TOP_LEFT_VALUE, DAILY_TAB_BOTTOM_RIGHT_VALUE, 3, 200);
            case BOTH -> {


                logDebug(routineLogAllianceShopLine("Item available in both tabs"));
            }
        }
        sleepTask(1500);

    }

private Integer seekForItemCard(TemplatesEnum template) {
        for (int i = 1; i <= MAX_CARD_POSITIONS_LIMIT; i++) {
            AreaData area = resolveItemArea(i);

            ImageSearchResultData searchResult = templateSearchHelper.locatePattern(
                    template,
                    SearchConfig.builder()
                            .withMaxAttempts(RETRIES_ITEM_SEARCH_VALUE)
                            .withDelay(200L)
                            .withArea(area)
                            .build());

            if (searchResult.isFound()) {
                logDebug(routineLogAllianceShopLine("Detected " + template.name() + " at card position " + i));
                return i;
            }
        }

        logDebug(routineLogAllianceShopLine("Template " + template.name() + " not detected in any card position"));
        return null;
    }

private AreaData resolveItemArea(int cardNumber) {
        return resolveCardArea(cardNumber, 0, 0, ITEM_WIDTH_VALUE, ITEM_HEIGHT_VALUE);
    }

private AreaData resolveCardArea(int cardNumber, int offsetX, int offsetY, int width, int height) {
        if (cardNumber < 1 || cardNumber > MAX_CARD_POSITIONS_LIMIT) {
            throw new IllegalArgumentException("Card number must be between 1 and " + MAX_CARD_POSITIONS_LIMIT);
        }

        int expertOffset = expertUnlocked ? EXPERT_Y_OFFSET_VALUE : 0;

        int row = (cardNumber - 1) / CARDS_PER_ROW_VALUE;
        int col = (cardNumber - 1) % CARDS_PER_ROW_VALUE;

        int cardX = GRID_START_X_VALUE + col * (ITEM_WIDTH_VALUE + SPACING_X_VALUE);
        int cardY = GRID_START_Y_VALUE + expertOffset + row * (ITEM_HEIGHT_VALUE + SPACING_Y_VALUE);

        int x1 = cardX + offsetX;
        int y1 = cardY + offsetY;
        int x2 = x1 + width;
        int y2 = y1 + height;

        return new AreaData(new PointData(x1, y1), new PointData(x2, y2));
    }

private void performPurchase(
            AllianceShopItemEnum shopItem,
            int itemPrice,
            int availableQuantity,
            int qty,
            int cardIndex) {

        logInfo(routineLogAllianceShopLine("Buying " + qty + " of " + shopItem.getDisplayName() +
                " (Price: " + itemPrice + ", Available: " + availableQuantity +
                ", Current Coins: " + currentCoins + ")"));

        openUpPurchaseDialog(cardIndex);
        chooseQuantity(qty, availableQuantity);
        confirmPurchaseFlow();
        refreshRemainingCoins(qty, itemPrice);
        dismissPurchaseDialog();

        logInfo(routineLogAllianceShopLine("Successfully purchased " + qty + " of " + shopItem.getDisplayName() +
                ". Remaining coins: " + currentCoins));
    }

private void handlePurchases(List<PriorityItemData> enabledPriorities) {
        for (PriorityItemData priority : enabledPriorities) {
            if (hasReachedMinimumCoinsFlow()) {
                logInfo(routineLogAllianceShopLine("Reached minimum coins threshold. Stopping purchases."));
                return;
            }

            logInfo(routineLogAllianceShopLine("Processing priority " + priority.getPriority() + ": " + priority.getName()));

            AllianceShopItemEnum shopItem = locateShopItemByIdentifier(priority.getIdentifier());
            if (shopItem == null) {
                logWarning(routineLogAllianceShopLine("Could not find shop item for identifier: " + priority.getIdentifier()));
                continue;
            }

            if (!handlePriorityItem(shopItem)) {


                return;
            }

            logInfo(routineLogAllianceShopLine("Concluded processing all tabs for: " + shopItem.getDisplayName()));
        }
    }

private BuyResult handleItemInTab(AllianceShopItemEnum shopItem, AllianceShopItemEnum.Availability currentTab) {
        Integer cardIndex = resolveCardIndex(shopItem);
        AreaData cardCoords = cardIndex != null ? resolveItemArea(cardIndex) : null;

        if (cardCoords == null) {
            logWarning(routineLogAllianceShopLine("Could not determine card coordinates for item: " + shopItem.getDisplayName()));
            return BuyResult.ERROR;
        }

        if (hasSoldOut(cardCoords, shopItem)) {
            return BuyResult.SOLD_OUT;
        }

        PriceCheck priceValidation = validateItemPriceFlow(cardIndex, shopItem);
        if (!priceValidation.isValid()) {
            return priceValidation.getOutcome();
        }

        int itemPrice = priceValidation.getPrice();
        int quantity = computePurchaseQuantity(cardIndex, shopItem, itemPrice);

        if (quantity <= 0) {
            logInfo(routineLogAllianceShopLine("Cannot afford any more of item: " + shopItem.getDisplayName()));
            return BuyResult.CANT_AFFORD;
        }

        performPurchase(shopItem, itemPrice, priceValidation.getAvailableQuantity(), quantity, cardIndex);
        return BuyResult.PURCHASED;
    }

private void chooseQuantity(int qty, int availableQuantity) {
        if (qty == availableQuantity) {


            tapInside(MAX_BUTTON_TOP_LEFT_LIMIT, MAX_BUTTON_BOTTOM_RIGHT_LIMIT, 1, 300);
        } else {


            tapInside(PLUS_BUTTON_TOP_LEFT_VALUE, PLUS_BUTTON_BOTTOM_RIGHT_VALUE, qty - 1, 300);
        }
    }

private Integer scanItemPrice(int cardIndex, AllianceShopItemEnum shopItem) {
        AreaData priceArea = resolvePriceArea(cardIndex);

        Integer itemPrice = integerHelper.attemptRecognition(
                priceArea.topLeft(),
                priceArea.bottomRight(),
                RETRIES_OCR_VALUE,
                1000L,

                TesseractSettingsData.assembler()
                        .charWhitelist("0123456789")
                        .build(),
                text -> RegexNumberParser.conformsTo(text, Pattern.compile(".*?(\\d+).*")),
                text -> RegexNumberParser.extractByPattern(text, Pattern.compile(".*?(\\d+).*")));

        if (itemPrice == null) {
            logWarning(routineLogAllianceShopLine("Could not read price for item: " + shopItem.getDisplayName()));
        }

        return itemPrice;
    }

private boolean hasSoldOut(AreaData cardCoords, AllianceShopItemEnum shopItem) {
        ImageSearchResultData soldOutResult = templateSearchHelper.locatePattern(
                TemplatesEnum.ALLIANCE_SHOP_SOLD_OUT,
                SearchConfig.builder()
                        .withMaxAttempts(RETRIES_SOLD_OUT_VALUE)
                        .withDelay(100L)
                        .withArea(cardCoords)
                        .build());

        if (soldOutResult.isFound()) {
            logInfo(routineLogAllianceShopLine("Item " + shopItem.getDisplayName() + " is sold out"));
            return true;
        }
        return false;
    }
}
