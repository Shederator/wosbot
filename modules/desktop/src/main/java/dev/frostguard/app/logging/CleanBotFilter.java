package dev.frostguard.app.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

/**
 * Filter that dynamically silences known high-noise configuration and search 
 * events specifically for the CleanBot log stream, without affecting bot.log.
 */
public class CleanBotFilter extends Filter<ILoggingEvent> {

    @Override
    public FilterReply decide(ILoggingEvent event) {
        String msg = event.getMessage();
        if (msg == null) {
            return FilterReply.NEUTRAL;
        }

        String loggerName = event.getLoggerName();

        // 1. Silence massive batch of configuration seeding logs
        if (loggerName != null && loggerName.contains("ConfigService")) {
            if (msg.contains("Seeded default global configuration")) {
                return FilterReply.DENY;
            }
        }

        // 2. We Can Silence failing template searches and OCR idk

        return FilterReply.NEUTRAL;
    }
}
