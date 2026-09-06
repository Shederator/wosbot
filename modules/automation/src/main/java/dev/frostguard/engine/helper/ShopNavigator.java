package dev.frostguard.engine.helper;

import java.io.IOException;
import java.util.Optional;
import java.util.function.Consumer;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.input.TapInteractionService;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.nav.ShopTab;
import dev.frostguard.vision.logging.ProfileContextLogger;
import dev.frostguard.vision.ocr.OcrException;

/** Opens the Shop and selects an ordered footer tab using verified viewport state. */
public final class ShopNavigator {

    static final int MAX_SWIPE_ATTEMPTS = 10;
    private static final int SWIPE_DURATION_MS = 500;
    private static final int SWIPE_SETTLE_MS = 1_500;
    private static final int OCR_ATTEMPTS = 2;
    private static final int OCR_RETRY_MS = 300;

    private final Interactions interactions;
    private final Consumer<String> info;
    private final Consumer<String> warn;

    public ShopNavigator(EmulatorController emu, String device, AccountDescriptor profile) {
        ProfileContextLogger logger = new ProfileContextLogger(ShopNavigator.class, profile);
        this.interactions = new EmulatorInteractions(emu, device, profile, logger);
        this.info = logger::info;
        this.warn = logger::warn;
    }

    ShopNavigator(Interactions interactions) {
        this.interactions = interactions;
        this.info = ignored -> { };
        this.warn = ignored -> { };
    }

    public boolean navigateTo(ShopTab target) {
        if (target == null) {
            throw new IllegalArgumentException("target shop tab must not be null");
        }
        if (!interactions.openShop()) {
            warn.accept("Shop button was not found on Home");
            return false;
        }

        Optional<ShopTab> initial = interactions.readLeftmostTab(true);
        if (initial.isEmpty()) {
            warn.accept("Shop opened, but the initial leftmost tab could not be identified");
            return false;
        }
        if (initial.get() != ShopTab.MYSTERY_SHOP) {
            warn.accept("Fresh Shop did not open at Mystery Shop: observed=" + initial.get().displayName());
            return false;
        }

        ShopTab leftmost = initial.get();
        for (int attempt = 0; attempt <= MAX_SWIPE_ATTEMPTS; attempt++) {
            int slot = visibleSlot(leftmost, target);
            if (slot >= 0) {
                info.accept("Selecting shop tab: target=" + target.displayName()
                        + " leftmost=" + leftmost.displayName() + " slot=" + slot);
                interactions.tapSlot(slot);
                return true;
            }
            if (attempt == MAX_SWIPE_ATTEMPTS) {
                break;
            }

            SwipeDirection direction = directionTo(leftmost, target);
            info.accept("Shop tab swipe: target=" + target.displayName()
                    + " leftmost=" + leftmost.displayName() + " direction=" + direction
                    + " attempt=" + (attempt + 1) + "/" + MAX_SWIPE_ATTEMPTS);
            if (!interactions.swipe(direction)) {
                warn.accept("Shop tab swipe was interrupted: direction=" + direction);
                return false;
            }

            Optional<ShopTab> observed = interactions.readLeftmostTab(false);
            if (observed.isEmpty()) {
                warn.accept("Leftmost shop tab was unreadable after swipe: direction=" + direction);
                return false;
            }
            ShopTab next = observed.get();
            if (next == leftmost) {
                warn.accept("Shop tab viewport did not move toward " + direction
                        + ": leftmost=" + leftmost.displayName());
                return false;
            }
            if (!movedInDirection(leftmost, next, direction)) {
                warn.accept("Shop tab viewport moved contrary to the requested non-wrapping direction: before="
                        + leftmost.displayName() + " after=" + next.displayName()
                        + " direction=" + direction);
                return false;
            }
            leftmost = next;
        }

        warn.accept("Shop tab navigation exceeded " + MAX_SWIPE_ATTEMPTS
                + " swipes: target=" + target.displayName());
        return false;
    }

    static int visibleSlot(ShopTab leftmost, ShopTab target) {
        int slot = target.position() - leftmost.position();
        return slot >= 0 && slot < CommonGameAreas.SHOP_TAB_VISIBLE_COUNT ? slot : -1;
    }

    static SwipeDirection directionTo(ShopTab leftmost, ShopTab target) {
        return target.position() > leftmost.position()
                ? SwipeDirection.LATER
                : SwipeDirection.EARLIER;
    }

    static boolean movedInDirection(ShopTab before, ShopTab after, SwipeDirection direction) {
        return direction == SwipeDirection.LATER
                ? after.position() > before.position()
                : after.position() < before.position();
    }

    enum SwipeDirection {
        LATER,
        EARLIER
    }

    interface Interactions {
        boolean openShop();
        Optional<ShopTab> readLeftmostTab(boolean initialViewport);
        boolean swipe(SwipeDirection direction);
        void tapSlot(int visibleSlot);
    }

    private static final class EmulatorInteractions implements Interactions {

        private final EmulatorController emu;
        private final String device;
        private final TemplateSearchHelper searcher;
        private final TapInteractionService taps;
        private final ProfileContextLogger log;

        private EmulatorInteractions(EmulatorController emu, String device,
                AccountDescriptor profile, ProfileContextLogger log) {
            this.emu = emu;
            this.device = device;
            this.searcher = new TemplateSearchHelper(emu, device, profile);
            this.taps = TapInteractionService.forController(emu, device);
            this.log = log;
        }

        @Override
        public boolean openShop() {
            ImageSearchResultData shopButton = searcher.locatePattern(
                    TemplatesEnum.GAME_HOME_BOTTOM_BAR_SHOP_BUTTON,
                    SearchConfigConstants.SINGLE_WITH_RETRIES);
            return taps.tapInside(shopButton, 1, 2_000);
        }

        @Override
        public Optional<ShopTab> readLeftmostTab(boolean initialViewport) {
            for (int attempt = 1; attempt <= OCR_ATTEMPTS; attempt++) {
                try {
                    String raw = emu.readText(
                            device,
                            CommonGameAreas.SHOP_LEFTMOST_TAB_OCR_AREA.topLeft(),
                            CommonGameAreas.SHOP_LEFTMOST_TAB_OCR_AREA.bottomRight(),
                            CommonOCRSettings.SHOP_TAB_TEXT_SETTINGS);
                    Optional<ShopTab> tab = ShopTab.fromOcr(raw);
                    log.debug("Shop left-tab OCR: raw='" + printable(raw) + "' matched="
                            + tab.map(ShopTab::displayName).orElse("none")
                            + " attempt=" + attempt + "/" + OCR_ATTEMPTS);
                    if (tab.isPresent()) {
                        return tab;
                    }
                } catch (IOException | OcrException | RuntimeException exception) {
                    log.debug("Shop left-tab OCR failed at attempt " + attempt + ": "
                            + exception.getMessage());
                }
                if (attempt < OCR_ATTEMPTS && !interruptibleWait(OCR_RETRY_MS)) {
                    return Optional.empty();
                }
            }

            if (initialViewport) {
                ImageSearchResultData mystery = searcher.locatePattern(
                        TemplatesEnum.SHOP_MYSTERY_BUTTON,
                        TemplateSearchHelper.SearchConfig.builder()
                                .withMaxAttempts(1)
                                .withThreshold(90)
                                .withArea(CommonGameAreas.SHOP_LEFTMOST_TAB_OCR_AREA)
                                .build());
                if (mystery != null && mystery.isFound()) {
                    log.debug("Mystery Shop template established the fresh initial shop viewport");
                    return Optional.of(ShopTab.MYSTERY_SHOP);
                }
            }
            return Optional.empty();
        }

        @Override
        public boolean swipe(SwipeDirection direction) {
            if (direction == SwipeDirection.LATER) {
                emu.swipeScreen(device,
                        CommonGameAreas.SHOP_TABS_TOWARD_LATER_FROM,
                        CommonGameAreas.SHOP_TABS_TOWARD_LATER_TO,
                        SWIPE_DURATION_MS);
            } else {
                emu.swipeScreen(device,
                        CommonGameAreas.SHOP_TABS_TOWARD_EARLIER_FROM,
                        CommonGameAreas.SHOP_TABS_TOWARD_EARLIER_TO,
                        SWIPE_DURATION_MS);
            }
            return interruptibleWait(SWIPE_SETTLE_MS);
        }

        @Override
        public void tapSlot(int visibleSlot) {
            taps.tapInside(CommonGameAreas.shopTabTapArea(visibleSlot), 1, 1_000);
        }

        private static boolean interruptibleWait(long milliseconds) {
            try {
                Thread.sleep(milliseconds);
                return true;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private static String printable(String raw) {
            return raw == null ? "null" : raw.trim().replace("\n", "\\n");
        }
    }
}
