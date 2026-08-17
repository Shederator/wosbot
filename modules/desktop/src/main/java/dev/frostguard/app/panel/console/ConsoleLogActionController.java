package dev.frostguard.app.panel.console;

import dev.frostguard.engine.listener.LogListener;
import dev.frostguard.engine.service.LoggingService;

public final class ConsoleLogActionController {

	private final LogListener consoleBridge;

	public ConsoleLogActionController(ConsoleLogLayoutController controller) {
		this.consoleBridge = controller::appendMessage;
		LoggingService.obtain().attachObserver(consoleBridge);
	}
}
