package dev.frostguard.app.panel.launcher;

import java.net.URL;
import java.util.List;

public interface ILauncherConstants {

	String GLOBAL_CSS = "/styles/style.css";

	static String getCssPath() {
		return cssResource().toExternalForm();
	}

	static List<String> defaultStylesheets() {
		return List.of(getCssPath());
	}

	static URL cssResource() {
		URL resource = ILauncherConstants.class.getResource(GLOBAL_CSS);
		if (resource == null) {
			throw new IllegalStateException("Missing JavaFX stylesheet resource: " + GLOBAL_CSS);
		}
		return resource;
	}

}
