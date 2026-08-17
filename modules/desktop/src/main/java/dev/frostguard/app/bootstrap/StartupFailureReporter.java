package dev.frostguard.app.bootstrap;

import java.awt.GraphicsEnvironment;

import javax.swing.JOptionPane;

final class StartupFailureReporter {
    private StartupFailureReporter() {
    }

    static void report(String title, String message, boolean headless) {
        if (message == null || message.isBlank()) {
            message = "An unknown startup error occurred.";
        }
        System.err.println(title + ": " + message.replace(System.lineSeparator(), " "));
        if (headless || GraphicsEnvironment.isHeadless()) {
            return;
        }
        try {
            JOptionPane.showMessageDialog(null, message, title, JOptionPane.WARNING_MESSAGE);
        } catch (RuntimeException dialogFailure) {
            System.err.println("Could not display the startup error dialog: " + dialogFailure.getMessage());
        }
    }
}
