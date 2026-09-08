package dev.frostguard.app.panel.social;

import java.time.ZoneId;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;

/**
 * The clock every chat time is drawn against.
 *
 * <p>Messages are stored as instants, which is the only sane thing to store -- an alliance is spread
 * across a dozen countries and the moment a thing was said is not negotiable. But the moment is not
 * what anybody reads. What they read is a time of day, and which time of day depends on whose clock
 * the question is being asked from: the officer scheduling a rally wants it in the game's hours, the
 * person catching up over breakfast wants it in their own.
 *
 * <p>Held in one place because the answer has to be the same everywhere at once. The transcript and
 * the digest each render times in several spots, and a setting that moved some of them and not
 * others would be worse than no setting -- two panels disagreeing about when a message arrived is
 * indistinguishable from the capture being broken.
 */
public final class ChatClock {

    private ChatClock() {
    }

    private static final ReadOnlyObjectWrapper<ZoneId> ZONE =
            new ReadOnlyObjectWrapper<>(ZoneId.systemDefault());

    /** What the machine itself is set to. What the setting means by "same as this computer". */
    public static final String SYSTEM = "";

    /** The zone chat times are currently drawn in. Never null. */
    public static ZoneId zone() {
        return ZONE.get();
    }

    /** Fires when the zone changes, so an open panel can redraw rather than go stale. */
    public static ReadOnlyObjectProperty<ZoneId> zoneProperty() {
        return ZONE.getReadOnlyProperty();
    }

    /**
     * Points the clock at a stored setting.
     *
     * <p>Takes the setting's raw string rather than a {@link ZoneId} so that every caller does not
     * have to repeat the same two guards. Blank means the machine's own zone, and an unrecognised
     * name falls back to it as well: a profile carrying a zone this JDK has never heard of should
     * show times that are merely in the wrong zone, not refuse to draw the panel.
     */
    public static void useSetting(String stored) {
        ZONE.set(parse(stored));
    }

    private static ZoneId parse(String stored) {
        if (stored == null || stored.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(stored.trim());
        } catch (RuntimeException notAZone) {
            return ZoneId.systemDefault();
        }
    }
}
