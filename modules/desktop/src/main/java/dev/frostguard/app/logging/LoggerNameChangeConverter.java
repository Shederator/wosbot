package dev.frostguard.app.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Custom Logback Converter that adds a newline character before a log entry
 * whenever the logger name (i.e., the class emitting the log) changes from the previous entry.
 * This naturally groups logs visually by class in the console/file.
 */
public class LoggerNameChangeConverter extends ClassicConverter {
    private static String lastLoggerName = "";

    @Override
    public String convert(ILoggingEvent event) {
        String currentLogger = event.getLoggerName();
        if (currentLogger == null) return "";
        
        synchronized (LoggerNameChangeConverter.class) {
            if (!currentLogger.equals(lastLoggerName)) {
                boolean isFirst = lastLoggerName.isEmpty();
                lastLoggerName = currentLogger;
                
                // Only add a newline if it's not the very first log entry
                if (!isFirst) {
                    return "\n";
                }
            }
        }
        return "";
    }
}
