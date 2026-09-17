package dev.frostguard.engine.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import dev.frostguard.api.configs.TpMessageSeverityEnum;
import dev.frostguard.api.domain.LogMessageData;
import dev.frostguard.engine.listener.LogListener;

/**
 * Lightweight event bridge from engine log producers to the UI log sink.
 */
public class LoggingService {

	private static final int RECENT_ENTRY_LIMIT = 300;

	private static final class Holder {
		private static final LoggingService INSTANCE = new LoggingService();
	}

	private final AtomicReference<LogListener> observer = new AtomicReference<>();
	private final CopyOnWriteArrayList<LogListener> additionalObservers = new CopyOnWriteArrayList<>();
	private final ArrayDeque<LogMessageData> recentEntries = new ArrayDeque<>();

	private LoggingService() {
	}

	public static LoggingService obtain() {
		return Holder.INSTANCE;
	}

	public void attachObserver(LogListener listener) {
		observer.set(listener);
	}

	public void addObserver(LogListener listener) {
		if (listener != null) {
			additionalObservers.addIfAbsent(listener);
		}
	}

	public void removeObserver(LogListener listener) {
		additionalObservers.remove(listener);
	}

	public void emit(TpMessageSeverityEnum level, String origin, String accountName, String content) {
		LogMessageData message = LogMessageData.of(level, content, origin, accountName);
		remember(message);
		Optional.ofNullable(observer.get()).ifPresent(listener -> listener.onLogEntryEmitted(message));
		additionalObservers.forEach(listener -> listener.onLogEntryEmitted(message));
	}

	public synchronized List<LogMessageData> recentFor(String accountName, String taskName, int limit) {
		if (limit <= 0) {
			return List.of();
		}
		List<LogMessageData> matches = recentEntries.stream()
				.filter(entry -> java.util.Objects.equals(accountName, entry.getAccountTag()))
				.filter(entry -> "TaskQueue".equals(entry.getSourceTask())
						|| java.util.Objects.equals(taskName, entry.getSourceTask()))
				.toList();
		int from = Math.max(0, matches.size() - limit);
		return new ArrayList<>(matches.subList(from, matches.size()));
	}

	private synchronized void remember(LogMessageData message) {
		recentEntries.addLast(message);
		while (recentEntries.size() > RECENT_ENTRY_LIMIT) {
			recentEntries.removeFirst();
		}
	}

}
