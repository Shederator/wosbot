package dev.frostguard.api.domain;

import dev.frostguard.api.configs.TpMessageSeverityEnum;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Structured log entry emitted by the engine during routine execution.
 */
public class LogMessageData {

    private String accountTag;
    private String sourceTask;
    private String body;
    private TpMessageSeverityEnum severity;
    private LocalDateTime timestamp;

    /* ── static factories ── */

    public static LogMessageData info(String body, String task, String account) {
        return of(TpMessageSeverityEnum.INFO, body, task, account);
    }

    public static LogMessageData warning(String body, String task, String account) {
        return of(TpMessageSeverityEnum.WARNING, body, task, account);
    }

    public static LogMessageData of(TpMessageSeverityEnum sev, String body,
                                    String task, String account) {
        LogMessageData m = new LogMessageData();
        m.severity = sev;
        m.body = body;
        m.sourceTask = task;
        m.accountTag = account;
        m.timestamp = LocalDateTime.now();
        return m;
    }

    /* ── no-arg for frameworks ── */
    public LogMessageData() {}

    /** Compatibility constructor for callers that have not moved to factories yet. */
    @Deprecated(since = "2.1", forRemoval = false)
    public LogMessageData(TpMessageSeverityEnum sev, String message, String task, String profile) {
        this.severity = sev;
        this.body = message;
        this.sourceTask = task;
        this.accountTag = profile;
        this.timestamp = LocalDateTime.now();
    }

    /* ── derived ── */

    public String toFormattedString() {
        return "[" + severity + "] " + accountTag + "/" + sourceTask + ": " + body;
    }

    /* ── accessors ── */

    public String getAccountTag()                           { return accountTag; }
    public void setAccountTag(String tag)                   { this.accountTag = tag; }

    public String getSourceTask()                           { return sourceTask; }
    public void setSourceTask(String task)                  { this.sourceTask = task; }

    public String getBody()                                 { return body; }
    public void setBody(String b)                           { this.body = b; }

    public TpMessageSeverityEnum getSeverity()              { return severity; }
    public void setSeverity(TpMessageSeverityEnum s)        { this.severity = s; }

    public LocalDateTime getTimestamp()                     { return timestamp; }
    public void setTimestamp(LocalDateTime ts)              { this.timestamp = ts; }

    /* ── legacy delegates ── */

    public String getProfile()                      { return accountTag; }
    public void setProfile(String p)                { this.accountTag = p; }
    public String getRoutine()                      { return sourceTask; }
    public void setRoutine(String r)                { this.sourceTask = r; }
    public String getContent()                      { return body; }
    public void setContent(String c)                { this.body = c; }
    public String getMessage()                      { return body; }
    public void setMessage(String m)                { this.body = m; }
    public String getTask()                         { return sourceTask; }
    public void setTask(String t)                   { this.sourceTask = t; }
    public TpMessageSeverityEnum getLevel()         { return severity; }
    public void setLevel(TpMessageSeverityEnum l)   { this.severity = l; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LogMessageData that)) return false;
        return severity == that.severity
            && Objects.equals(body, that.body)
            && Objects.equals(sourceTask, that.sourceTask)
            && Objects.equals(accountTag, that.accountTag);
    }

    @Override
    public int hashCode() {
        return Objects.hash(severity, body, sourceTask, accountTag);
    }

    @Override
    public String toString() {
        return toFormattedString();
    }
}
