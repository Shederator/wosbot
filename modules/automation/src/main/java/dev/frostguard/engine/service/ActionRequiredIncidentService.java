package dev.frostguard.engine.service;

import dev.frostguard.api.domain.ActionRequiredIncidentData;
import dev.frostguard.api.domain.ActionRequiredIncidentReport;
import dev.frostguard.api.domain.LogMessageData;
import dev.frostguard.data.repository.ActionRequiredIncidentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Headless-safe gateway for durable failures that need operator action.
 * Callers should report only after their bounded automatic recovery is spent.
 */
public class ActionRequiredIncidentService {

    private static final Logger logger = LoggerFactory.getLogger(ActionRequiredIncidentService.class);

    private static final int LOG_EXCERPT_LINES = 12;
    private static final int MAX_FIELD_LENGTH = 1800;
    private static final int MAX_LOG_EXCERPT_LENGTH = 5500;
    private static final DateTimeFormatter DIAGNOSTIC_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(token|password|secret|authorization|chat[_-]?id)\\s*[:=]\\s*[^\\s,;]+", Pattern.MULTILINE);
    private static final Pattern PRIVATE_ID_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(account|player|character|hero)[_-]?id\\s*[:=]\\s*[^\\s,;]+", Pattern.MULTILINE);
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[^\\s,;]+");
    private static final Pattern UUID_VALUE = Pattern.compile(
            "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b");
    private static final Pattern LONG_NUMERIC_ID = Pattern.compile("\\b\\d{6,}\\b");
    private static final Pattern EMAIL_ADDRESS = Pattern.compile(
            "(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");

    private static volatile ActionRequiredIncidentService instance;

    private final ActionRequiredIncidentRepository repository;
    private final LoggingService loggingService;
    private final CopyOnWriteArrayList<Consumer<List<ActionRequiredIncidentData>>> listeners =
            new CopyOnWriteArrayList<>();
    private final Set<String> activeTaskKeys = ConcurrentHashMap.newKeySet();

    ActionRequiredIncidentService(
            ActionRequiredIncidentRepository repository, LoggingService loggingService) {
        this.repository = repository;
        this.loggingService = loggingService;
        repository.findAll().stream()
                .filter(incident -> incident.recoveredAt() == null)
                .map(incident -> activeKey(incident.profileId(), incident.taskKey()))
                .forEach(activeTaskKeys::add);
    }

    public static ActionRequiredIncidentService obtain() {
        ActionRequiredIncidentService current = instance;
        if (current != null) {
            return current;
        }
        synchronized (ActionRequiredIncidentService.class) {
            if (instance == null) {
                instance = new ActionRequiredIncidentService(
                        ActionRequiredIncidentRepository.getRepository(), LoggingService.obtain());
            }
            return instance;
        }
    }

    public synchronized ActionRequiredIncidentData report(ActionRequiredIncidentReport rawReport) {
        ActionRequiredIncidentReport report = sanitized(rawReport);
        String logExcerpt = correlatedLogExcerpt(report.profileName(), report.taskName());
        ActionRequiredIncidentData incident = repository.recordOccurrence(
                report, logExcerpt, LocalDateTime.now());
        activeTaskKeys.add(activeKey(report.profileId(), report.taskKey()));
        publishSnapshot();
        return incident;
    }

    public synchronized boolean acknowledge(String incidentId) {
        boolean changed = repository.acknowledge(incidentId, LocalDateTime.now());
        if (changed) {
            publishSnapshot();
        }
        return changed;
    }

    public synchronized int recoverTask(long profileId, String taskKey) {
        String activeKey = activeKey(profileId, taskKey);
        if (!activeTaskKeys.remove(activeKey)) {
            return 0;
        }
        int recovered = repository.recoverTask(profileId, taskKey, LocalDateTime.now());
        if (recovered > 0) {
            publishSnapshot();
        }
        return recovered;
    }

    public List<ActionRequiredIncidentData> findAll() {
        return repository.findAll();
    }

    public void registerListener(Consumer<List<ActionRequiredIncidentData>> listener) {
        listeners.add(listener);
    }

    public void unregisterListener(Consumer<List<ActionRequiredIncidentData>> listener) {
        listeners.remove(listener);
    }

    public static String formatDiagnostics(ActionRequiredIncidentData incident) {
        StringBuilder output = new StringBuilder("Frostguard ACTION REQUIRED\n");
        append(output, "State", incident.state().name());
        append(output, "Profile", incident.profileName());
        append(output, "Task", incident.taskName());
        append(output, "Signature", incident.signature());
        append(output, "Title", incident.title());
        append(output, "Cause", incident.cause());
        append(output, "First seen", formatTime(incident.firstSeenAt()));
        append(output, "Last seen", formatTime(incident.lastSeenAt()));
        append(output, "Occurrences", String.valueOf(incident.occurrenceCount()));
        append(output, "Retry at", formatTime(incident.retryAt()));
        output.append('\n').append("Diagnostics\n");
        append(output, "Expected", incident.expectedState());
        append(output, "Observed", incident.observedState());
        append(output, "Last action", incident.lastAction());
        append(output, "Retry/fallback", incident.retryOrFallback());
        append(output, "Resources", incident.resourceOutcome());
        if (incident.logExcerpt() != null && !incident.logExcerpt().isBlank()) {
            output.append('\n').append("Correlated log excerpt (bounded)\n")
                    .append(incident.logExcerpt().strip()).append('\n');
        }
        return output.toString().stripTrailing();
    }

    static String redact(String value) {
        String redacted = value == null ? "" : value;
        redacted = SECRET_ASSIGNMENT.matcher(redacted).replaceAll("$1=<redacted>");
        redacted = PRIVATE_ID_ASSIGNMENT.matcher(redacted).replaceAll("$1Id=<redacted>");
        redacted = BEARER.matcher(redacted).replaceAll("Bearer <redacted>");
        redacted = UUID_VALUE.matcher(redacted).replaceAll("<redacted-id>");
        redacted = LONG_NUMERIC_ID.matcher(redacted).replaceAll("<redacted-number>");
        redacted = EMAIL_ADDRESS.matcher(redacted).replaceAll("<redacted-email>");
        if (redacted.length() > MAX_FIELD_LENGTH) {
            return redacted.substring(0, MAX_FIELD_LENGTH) + "…";
        }
        return redacted;
    }

    private ActionRequiredIncidentReport sanitized(ActionRequiredIncidentReport report) {
        return new ActionRequiredIncidentReport(
                report.profileId(),
                redact(report.profileName()),
                report.taskKey(),
                redact(report.taskName()),
                report.signature(),
                redact(report.title()),
                redact(report.cause()),
                redact(report.expectedState()),
                redact(report.observedState()),
                redact(report.lastAction()),
                redact(report.retryOrFallback()),
                redact(report.resourceOutcome()),
                report.retryAt());
    }

    private String correlatedLogExcerpt(String profileName, String taskName) {
        String excerpt = loggingService.recentFor(profileName, taskName, LOG_EXCERPT_LINES).stream()
                .map(ActionRequiredIncidentService::formatLogEntry)
                .map(ActionRequiredIncidentService::redact)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        return excerpt.length() > MAX_LOG_EXCERPT_LENGTH
                ? excerpt.substring(excerpt.length() - MAX_LOG_EXCERPT_LENGTH)
                : excerpt;
    }

    private static String formatLogEntry(LogMessageData entry) {
        return String.format("%s %-7s %s - %s",
                entry.getTimestamp().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                entry.getSeverity(), entry.getSourceTask(), entry.getBody());
    }

    private void publishSnapshot() {
        List<ActionRequiredIncidentData> snapshot = repository.findAll();
        listeners.forEach(listener -> {
            try {
                listener.accept(snapshot);
            } catch (RuntimeException exception) {
                logger.warn("Action-required incident listener failed: {}", exception.getMessage());
            }
        });
    }

    private static String activeKey(long profileId, String taskKey) {
        return profileId + "\n" + taskKey;
    }

    private static void append(StringBuilder output, String label, String value) {
        output.append(label).append(": ").append(value == null || value.isBlank() ? "-" : value).append('\n');
    }

    private static String formatTime(LocalDateTime value) {
        return value == null ? "-" : value.format(DIAGNOSTIC_TIME);
    }
}
