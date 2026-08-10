package dev.frostguard.engine.service;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import dev.frostguard.api.configs.TpMessageSeverityEnum;
import dev.frostguard.api.domain.LogMessageData;
import dev.frostguard.engine.listener.LogListener;

/**
 * Lightweight event bridge from engine log producers to the UI log sink.
 */
public class LoggingService {

	private static final class Holder {
		private static final LoggingService INSTANCE = new LoggingService();
	}

	private final AtomicReference<LogListener> observer = new AtomicReference<>();

	private LoggingService() {
	}

	public static LoggingService obtain() {
		return Holder.INSTANCE;
	}

	public void attachObserver(LogListener listener) {
		observer.set(listener);
	}

	public void emit(TpMessageSeverityEnum level, String origin, String accountName, String content) {
		LogMessageData message = LogMessageData.of(level, content, origin, accountName);
		Optional.ofNullable(observer.get()).ifPresent(listener -> listener.onLogEntryEmitted(message));
	}

}
