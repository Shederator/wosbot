package dev.frostguard.app.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Custom logback converter to dynamically enhance specific messages for CleanBot.log
 * keeping the core Java logic exactly the way it historically was for bot.log.
 */
public class CleanBotMessageConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        String msg = event.getFormattedMessage();
        String loggerName = event.getLoggerName();

        if (msg == null || msg.trim().isEmpty() || loggerName == null) {
            return msg;
        }

        // 1. Strip the redundant manual profile prefixes from classes like EmulatorController
        // Examples: "Default - " or "SYSTEM - "
        if (msg.matches("^[a-zA-Z0-9]+ - .*")) {
            msg = msg.substring(msg.indexOf(" - ") + 3);
        }

        // 2. Format EmulatorController Action events
        if (loggerName.endsWith("EmulatorController")) {
            if (msg.startsWith("Tapping at")) {
                msg = msg.replace("Tapping at", "👆 Tapping @").replace("for emulator", "[Emu") + "]";
            } else if (msg.startsWith("Random tapping in area")) {
                msg = msg.replace("Random tapping in area", "👆 Random Tap @").replace("for emulator", "[Emu") + "]";
            } else if (msg.startsWith("Multiple random tapping")) {
                msg = "🔄 " + msg.replace("for emulator", "[Emu") + "]";
            } else if (msg.startsWith("Swiping from")) {
                msg = msg.replace("Swiping from", "👆 Swiping from").replace("for emulator", "[Emu") + "]";
            } else if (msg.startsWith("Pressing back button")) {
                msg = msg.replace("Pressing back button", "Pressing BACK button").replace("for emulator", "[Emu") + "]";
            } else if (msg.startsWith("Writing text on emulator")) {
                msg = "⌨️ " + msg.replace("Writing text on emulator", "").replace(":", " | Typing ->").trim();
            } else if (msg.startsWith("Clearing")) {
                msg = "⌫ " + msg.replace("on emulator", "[Emu") + "]";
            }
        } 
        // 3. Format Template Search events seamlessly 
        else if (loggerName.endsWith("OpenCvPatternLocator")) {
            if (msg.contains("=== Template Search Completed ===")) {
                try {
                    String template = extractParam(msg, "Template: ", ",");
                    String total = extractParam(msg, "Total: ", " ms");
                    
                    if (msg.contains("Position:")) {
                        String match = extractParam(msg, "Match: ", "%,");
                        String position = msg.substring(msg.indexOf("Position: ") + 10);
                        msg = "🔍 Search | ✅ " + template + " @ " + position + " | " + match + "% | " + total + "ms";
                    } else if (msg.contains("BELOW threshold")) {
                        String match = extractParam(msg, "Match: ", "%");
                        msg = "🔍 Search | ❌ " + template + " (Failed) | " + match + "% | " + total + "ms";
                    }
                } catch (Exception e) {}
            }
        } 
        // 3.5 Format OCR 
        else if (loggerName.endsWith("ResilientOcrExecutor")) {
            if (msg.contains("=== OCR Completed ===")) {
                try {
                    String text = extractParam(msg, "Text: '", "',");
                    String position = msg.substring(msg.indexOf("Position: ") + 10);
                    if (msg.contains("Match: true")) {
                        msg = "📄 OCR    | ✅ '" + text + "' @ " + position;
                    } else {
                        msg = "📄 OCR    | ❌ '" + text + "' @ " + position;
                    }
                } catch (Exception e) {}
            }
        }
        
        else if (loggerName.endsWith("ConfigService")) {
            if (msg.contains("STATISTICS_JSON_STRING updated to")) {
                try {
                    String json = msg.substring(msg.indexOf("{"));
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([^\"]+)\"\\s*:\\s*\\{[^{}]*\"numberOfRuns\"\\s*:\\s*(\\d+)").matcher(json);
                    StringBuilder stats = new StringBuilder("📊 Stats Updated | Tasks: ");
                    int count = 0;
                    while (m.find() && count < 3) {
                        stats.append("[").append(m.group(1)).append(": ").append(m.group(2)).append("x] ");
                        count++;
                    }
                    
                    // Add Custom Counters parsing
                    java.util.regex.Matcher cm = java.util.regex.Pattern.compile("\"customCounters\"\\s*:\\s*\\{([^{}]+)\\}").matcher(json);
                    if (cm.find()) {
                        String countersBlock = cm.group(1);
                        if (countersBlock != null && !countersBlock.trim().isEmpty()) {
                            stats.append("| Counters: ");
                            java.util.regex.Matcher cItem = java.util.regex.Pattern.compile("\"([^\"]+)\"\\s*:\\s*(\\d+)").matcher(countersBlock);
                            int cCount = 0;
                            while (cItem.find() && cCount < 3) {
                                stats.append("[").append(cItem.group(1).replace(" Claimed", "").replace(" Used", "")).append(": ").append(cItem.group(2)).append("] ");
                                cCount++;
                            }
                        }
                    }

                    if (count > 0) {
                        msg = stats.toString().trim();
                    } else {
                        msg = "📊 Task Statistics Updated";
                    }
                } catch (Exception e) {
                    msg = "📊 Task Statistics Updated";
                }
            }
        }
        
        // 4. Global enhancements for Core Bot Loop
        else if (loggerName.endsWith("TaskQueue")) {
            if (msg.startsWith("Starting task execution:")) {
                msg = "🚀 " + msg;
            } else if (msg.startsWith("Task Completed:")) {
                msg = "✅ " + msg;
            } else if (msg.startsWith("Task removed from schedule")) {
                msg = "🗑️ " + msg;
            } else if (msg.startsWith("Next task scheduled to run in:")) {
                msg = "⏳ Next task in " + msg.replace("Next task scheduled to run in:", "").trim();
            } else if (msg.startsWith("Preempting")) {
                msg = "🛡️ " + msg;
            } else if (msg.startsWith("Enqueued") && msg.contains("Preempted by:")) {
                 msg = "➡️ " + msg;
            } else if (msg.startsWith("Ignoring preemption:")) {
                 msg = "⚠️ " + msg;
            } else if (msg.startsWith("Rescheduling") && msg.contains("Preempted by:")) {
                 msg = "🔄 " + msg;
            }
        }
        else if (loggerName.endsWith("TaskDispatcher")) {
            if (msg.startsWith("Starting queue for profile:")) {
                msg = "📋 " + msg;
            } else if (msg.startsWith("Starting queues")) {
                msg = "🚦 Starting multi-profile queues...";
            }
        }
        else if (loggerName.endsWith("EmulatorController")) {
            // Already handled action formats above, these are system slots
            if (msg.contains("is requesting queue slot")) {
                msg = "Requesting ADB Slot";
            } else if (msg.contains("acquired slot")) {
                msg = "ADB Slot Acquired";
            } else if (msg.startsWith("Current slot holders:")) {
                msg = msg.replace("Current slot holders:", "Slot holders:");
            }
        }
        else if (loggerName.endsWith("EmulatorInstance")) {
            if (msg.startsWith("App com.gof.global is not in foreground")) {
                msg = "Game not in foreground";
            } else if (msg.startsWith("Application com.gof.global launched")) {
                msg = "Application launched";
            }
        }
        else if (loggerName.endsWith("StaminaHelper")) {
            msg = "⚡ " + msg;
        }
        // 5. Enhance specific loose messages
        else if (loggerName.endsWith("GlobalMonitorService")) {
            if (msg.startsWith("Preemption rule registered")) {
                msg = "🛡️ " + msg;
            } else if (msg.startsWith("Injection rule registered")) {
                msg = "💉 " + msg;
            } else if (msg.startsWith("Preempting")) {
                msg = "🛡️ " + msg;
            } else if (msg.startsWith("Injecting")) {
                msg = "💉 " + msg;
            }
        }
        
        // 5. Enhance specific loose messages without emojis
        if (msg.startsWith("Whiteout Survival is not running")) {
            msg = "Launching game...";
        } else if (msg.startsWith("Whiteout Survival is already running")) {
            msg = "Game already running.";
        } else if (msg.startsWith("Home screen not found")) {
            msg = "Home screen not found. Retrying...";
        } else if (msg.startsWith("Emulator not found. Attempting to start it")) {
            msg = "Starting emulator...";
        } else if (msg.startsWith("Waiting 10 seconds before checking again")) {
            msg = "Waiting 10s...";
        } else if (msg.startsWith("Initialization task completed successfully")) {
            msg = "Init flow completed.";
        } else if (msg.startsWith("No character configuration found")) {
            msg = "Skipping character verification.";
        } else if (msg.startsWith("DEBUG: Loaded pool config:") || msg.startsWith("DEBUG: Saving pool config:")) {
            msg = msg.replace("DEBUG: ", "");
        }
        
        return msg;
    }

    private String extractParam(String source, String startKey, String endKey) {
        int start = source.indexOf(startKey);
        if (start == -1) return "?";
        start += startKey.length();
        int end = source.indexOf(endKey, start);
        if (end == -1) end = source.length();
        return source.substring(start, end).trim();
    }
}
