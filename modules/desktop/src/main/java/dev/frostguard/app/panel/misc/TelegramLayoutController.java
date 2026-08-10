package dev.frostguard.app.panel.misc;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Properties;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.engine.service.ConfigService;
import dev.frostguard.engine.service.TelegramBotService;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;

public class TelegramLayoutController {

    @FXML
    private CheckBox checkBoxEnabled;

    @FXML
    private PasswordField passwordFieldToken;

    @FXML
    private CheckBox checkBoxShowToken;

    @FXML
    private TextField textFieldTokenVisible;

    @FXML
    private TextField textFieldChatId;

    @FXML
    private Label labelStatus;

    @FXML
    private Button buttonTest;

    @FXML
    private TextField textFieldBotJarPath;

    @FXML
    private Button buttonBrowseJar;

    @FXML
    private Button buttonSave;

    @FXML
    private Label labelStartupStatus;

    @FXML
    private CheckBox checkBoxAutoStart;

    /** Shortcut name placed in the Windows Startup folder. */
    private static final String SHORTCUT_NAME = "Frostguard-Telegram-Watcher.lnk";

    @FXML
    public void initialize() {
        HashMap<String, String> cfg = ConfigService.obtain().loadGlobalSettings();
        if (cfg == null) cfg = new HashMap<>();

        boolean enabled = Boolean.parseBoolean(
                cfg.getOrDefault(ConfigurationKeyEnum.TELEGRAM_BOT_ENABLED_BOOL.name(), "false"));
        String token   = cfg.getOrDefault(ConfigurationKeyEnum.TELEGRAM_BOT_TOKEN_STRING.name(), "");
        String chatId  = cfg.getOrDefault(ConfigurationKeyEnum.TELEGRAM_ALLOWED_CHAT_ID_STRING.name(), "");

        // Load bot JAR path: saved properties first, then auto-detect
        String jarPath = loadJarPathFromProperties();
        if (jarPath.isBlank()) {
            jarPath = autoDetectBotJar();
        }

        checkBoxEnabled.setSelected(enabled);
        passwordFieldToken.setText(token);
        textFieldTokenVisible.setText(token);
        textFieldChatId.setText(chatId);
        textFieldBotJarPath.setText(jarPath);

        // Keep PasswordField and visible TextField in sync
        passwordFieldToken.textProperty().bindBidirectional(textFieldTokenVisible.textProperty());

        // Show / hide token
        textFieldTokenVisible.setVisible(false);
        textFieldTokenVisible.setManaged(false);
        checkBoxShowToken.setOnAction(e -> {
            boolean show = checkBoxShowToken.isSelected();
            passwordFieldToken.setVisible(!show);
            passwordFieldToken.setManaged(!show);
            textFieldTokenVisible.setVisible(show);
            textFieldTokenVisible.setManaged(show);
        });

        refreshStatusLabel();
        refreshStartupLabel();

        // Sync toggle state to current registration status
        if (checkBoxAutoStart != null) {
            checkBoxAutoStart.setSelected(Files.exists(startupFolder().resolve(SHORTCUT_NAME)));
        }
    }

    @FXML
    private void handleBrowseJar() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select bot JAR");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("JAR files", "*.jar"));
        String current = textFieldBotJarPath.getText().trim();
        if (!current.isBlank()) {
            File f = new File(current);
            if (f.getParentFile() != null && f.getParentFile().exists()) {
                fc.setInitialDirectory(f.getParentFile());
            }
        }
        File selected = fc.showOpenDialog(textFieldBotJarPath.getScene().getWindow());
        if (selected != null) {
            textFieldBotJarPath.setText(selected.getAbsolutePath());
        }
    }

    @FXML
    private void handleSave() {
        String token   = passwordFieldToken.getText().trim();
        String chatId  = textFieldChatId.getText().trim();
        String jarPath = textFieldBotJarPath.getText().trim();
        boolean enabled = checkBoxEnabled.isSelected();

        // Validate chat ID is numeric if present
        if (!chatId.isBlank()) {
            try {
                Long.parseLong(chatId);
            } catch (NumberFormatException e) {
                showError("Allowed Chat ID must be a numeric Telegram user/chat ID.");
                return;
            }
        }

        ConfigService.obtain().writeGlobalSetting(ConfigurationKeyEnum.TELEGRAM_BOT_ENABLED_BOOL,
                String.valueOf(enabled));
        ConfigService.obtain().writeGlobalSetting(ConfigurationKeyEnum.TELEGRAM_BOT_TOKEN_STRING, token);
        ConfigService.obtain().writeGlobalSetting(ConfigurationKeyEnum.TELEGRAM_ALLOWED_CHAT_ID_STRING, chatId);

        // Write the watcher properties file so the standalone watcher JAR can read it
        saveWatcherProperties(token, chatId, jarPath);

        // Apply in-app Telegram service immediately
        TelegramBotService svc = TelegramBotService.getInstance();
        if (enabled && !token.isBlank()) {
            long cid = chatId.isBlank() ? 0L : Long.parseLong(chatId);
            svc.start(token, cid);
            labelStatus.setText("Status: ✅ Running");
            labelStatus.setStyle("-fx-text-fill: #4caf50;");
            
            // Auto-start watcher
            dev.frostguard.engine.service.TelegramWatcherLauncher.startWatcherIfNotRunning();
        } else {
            svc.stop();
            labelStatus.setText("Status: 🔴 Stopped");
            labelStatus.setStyle("-fx-text-fill: #e57373;");
        }

        showInfo("Configuration saved successfully.\nWatcher properties written to:\n" + watcherConfigPath());
    }

    private void saveWatcherProperties(String token, String chatId, String jarPath) {
        try {
            Path cfg = watcherConfigPath();
            Files.createDirectories(cfg.getParent());
            Properties props = new Properties();
            
            // Read existing properties to preserve values like localPort
            if (Files.exists(cfg)) {
                try (java.io.FileInputStream fis = new java.io.FileInputStream(cfg.toFile())) {
                    props.load(fis);
                }
            }
            
            props.setProperty("token",      token);
            props.setProperty("chatId",     chatId);
            props.setProperty("botJarPath", jarPath);
            
            // Ensure localPort exists
            if (!props.containsKey("localPort")) {
                props.setProperty("localPort", "8765");
            }
            
            try (FileOutputStream fos = new FileOutputStream(cfg.toFile())) {
                props.store(fos, "Frostguard Telegram Watcher configuration (auto-generated by bot UI)");
            }
        } catch (IOException e) {
            showError("Could not write watcher properties file: " + e.getMessage());
        }
    }

    private String loadJarPathFromProperties() {
        try {
            Path cfg = watcherConfigPath();
            if (Files.exists(cfg)) {
                Properties props = new Properties();
                try (var fis = new java.io.FileInputStream(cfg.toFile())) {
                    props.load(fis);
                }
                String saved = props.getProperty("botJarPath", "");
                // Verify the saved path still exists; clear it if not
                if (!saved.isBlank() && new File(saved).exists()) {
                    return saved;
                }
            }
        } catch (IOException ignored) {}
        return "";
    }

    /**
     * Attempts to locate the running bot JAR automatically.
     * Priority:
     *   1. The running JAR itself (getCodeSource) — works in production.
     *   2. modules/desktop/target/frostguard-desktop-*.jar relative to the working directory works
     *      when launched from the project root (IDE / quick_build.bat).
     *   3. Any frostguard-*.jar in the current working directory.
     */
    private static String autoDetectBotJar() {
        // 1. Detect own JAR path (works when running as a packaged JAR)
        try {
            java.net.URL src = TelegramLayoutController.class
                    .getProtectionDomain().getCodeSource().getLocation();
            File self = new File(src.toURI());
            if (self.isFile() && self.getName().endsWith(".jar")) {
                return self.getAbsolutePath();
            }
        } catch (Exception ignored) {}

        // 2. Relative path from working directory (project layout: modules/desktop/target/)
        File targetDir = new File(System.getProperty("user.dir"),
                "modules" + File.separator + "desktop" + File.separator + "target");
        String found = findFrostguardJar(targetDir);
        if (found != null) return found;

        // 3. Current working directory itself
        found = findFrostguardJar(new File(System.getProperty("user.dir")));
        if (found != null) return found;

        return "";
    }

    /** Scans a directory for the newest desktop application JAR. */
    private static String findFrostguardJar(File dir) {
        if (dir == null || !dir.isDirectory()) return null;
        File[] candidates = dir.listFiles(
                f -> f.isFile() && f.getName().startsWith("frostguard-desktop-")
                        && f.getName().endsWith(".jar"));
        if (candidates != null && candidates.length > 0) {
            // Pick the lexicographically last name (highest version)
            java.util.Arrays.sort(candidates, java.util.Comparator.comparing(File::getName).reversed());
            return candidates[0].getAbsolutePath();
        }
        return null;
    }

    /** Mirrors TelegramWatcher.configFilePath() – path convention kept in sync manually. */
    private static Path watcherConfigPath() {
        return Paths.get(System.getProperty("user.home"), ".frostguard", "telegram-watcher.properties");
    }

    // ── Startup registration ──────────────────────────────────────────────────

    @FXML
    private void handleAutoStartToggle() {
        if (checkBoxAutoStart.isSelected()) {
            registerStartup();
        } else {
            removeStartup();
        }
    }

    private void registerStartup() {
        File batFile = resolveBatFile();
        if (batFile == null) {
            showError("Cannot locate the Telegram Watcher launcher.");
            checkBoxAutoStart.setSelected(false);
            return;
        }
        Path shortcut = startupFolder().resolve(SHORTCUT_NAME);
        try {
            String vbs =
                "Set oWS = WScript.CreateObject(\"WScript.Shell\")\n" +
                "Set oLink = oWS.CreateShortcut(\"" + shortcut.toString().replace("\\", "\\\\") + "\")\n" +
                "oLink.TargetPath = \"" + batFile.getAbsolutePath().replace("\\", "\\\\") + "\"\n" +
                "oLink.WorkingDirectory = \"" + batFile.getParent().replace("\\", "\\\\") + "\"\n" +
                "oLink.Description = \"Frostguard Telegram Watcher (auto-start)\"\n" +
                "oLink.WindowStyle = 0\n" +
                "oLink.Save\n";
            Path vbsFile = Files.createTempFile("frostguard_startup_", ".vbs");
            Files.writeString(vbsFile, vbs);
            vbsFile.toFile().deleteOnExit();
            Process p = new ProcessBuilder("cscript", "//NoLogo", vbsFile.toAbsolutePath().toString())
                    .redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes());
            int exit = p.waitFor();
            Files.deleteIfExists(vbsFile);
            if (exit != 0) {
                showError("Shortcut creation failed (exit " + exit + "):\n" + output);
                checkBoxAutoStart.setSelected(false);
                return;
            }
            refreshStartupLabel();
        } catch (Exception e) {
            showError("Failed to register startup shortcut: " + e.getMessage());
            checkBoxAutoStart.setSelected(false);
        }
    }

    private void removeStartup() {
        Path shortcut = startupFolder().resolve(SHORTCUT_NAME);
        try {
            Files.deleteIfExists(shortcut);
            refreshStartupLabel();
        } catch (IOException e) {
            showError("Could not remove shortcut: " + e.getMessage());
            checkBoxAutoStart.setSelected(true);
        }
    }

    private void refreshStartupLabel() {
        boolean registered = Files.exists(startupFolder().resolve(SHORTCUT_NAME));
        if (checkBoxAutoStart != null) checkBoxAutoStart.setSelected(registered);
        if (labelStartupStatus == null) return;
        if (registered) {
            labelStartupStatus.setText("Auto-start: ✅ Registered");
            labelStartupStatus.setStyle("-fx-text-fill: #4caf50;");
        } else {
            labelStartupStatus.setText("Auto-start: ❌ Not registered");
            labelStartupStatus.setStyle("-fx-text-fill: #e57373;");
        }
    }

    private static Path startupFolder() {
        return Paths.get(System.getenv("APPDATA"),
                "Microsoft", "Windows", "Start Menu", "Programs", "Startup");
    }

    /**
     * Walks up the directory tree from several anchor points to find the watcher launcher.
     * Typical layout: frostguard/modules/desktop/target/frostguard-desktop-X.jar  →  frostguard/fg-watcher.bat
     */
    private File resolveBatFile() {
        // 1. Walk up from the bot JAR path shown in the UI (most reliable)
        String jarPath = textFieldBotJarPath.getText().trim();
        if (!jarPath.isBlank()) {
            File dir = new File(jarPath).getParentFile();
            for (int i = 0; i < 5 && dir != null; i++) {
                File bat = new File(dir, "fg-watcher.bat");
                if (bat.exists()) return bat;
                File sourceLauncher = new File(dir,
                        "packaging/desktop/src/main/windows/Start-Frostguard-Watcher.bat");
                if (sourceLauncher.exists()) return sourceLauncher;
                dir = dir.getParentFile();
            }
        }

        // 2. Walk up from the running code-source location
        try {
            File dir = new File(
                    TelegramLayoutController.class.getProtectionDomain()
                            .getCodeSource().getLocation().toURI()).getParentFile();
            for (int i = 0; i < 5 && dir != null; i++) {
                File bat = new File(dir, "fg-watcher.bat");
                if (bat.exists()) return bat;
                File sourceLauncher = new File(dir,
                        "packaging/desktop/src/main/windows/Start-Frostguard-Watcher.bat");
                if (sourceLauncher.exists()) return sourceLauncher;
                dir = dir.getParentFile();
            }
        } catch (Exception ignored) {}

        // 3. Walk up from user.dir (works when launched from IDE / project root)
        File dir = new File(System.getProperty("user.dir"));
        for (int i = 0; i < 5 && dir != null; i++) {
            File bat = new File(dir, "fg-watcher.bat");
            if (bat.exists()) return bat;
            File sourceLauncher = new File(dir,
                    "packaging/desktop/src/main/windows/Start-Frostguard-Watcher.bat");
            if (sourceLauncher.exists()) return sourceLauncher;
            dir = dir.getParentFile();
        }

        return null;
    }

    @FXML
    private void handleTest() {
        String token = passwordFieldToken.getText().trim();
        if (token.isBlank()) {
            showError("Please enter a Bot Token before testing.");
            return;
        }
        buttonTest.setDisable(true);
        labelStatus.setText("Status: Testing…");
        labelStatus.setStyle("-fx-text-fill: #ffb74d;");

        Thread.ofVirtual().start(() -> {
            String result = TelegramBotService.getInstance().testToken(token);
            Platform.runLater(() -> {
                buttonTest.setDisable(false);
                if (result.startsWith("ERROR:")) {
                    labelStatus.setText("Status: ❌ " + result);
                    labelStatus.setStyle("-fx-text-fill: #e57373;");
                } else {
                    labelStatus.setText("Status: ✅ Connected as " + result);
                    labelStatus.setStyle("-fx-text-fill: #4caf50;");
                }
            });
        });
    }

    private void refreshStatusLabel() {
        boolean running = TelegramBotService.getInstance().isRunning();
        if (running) {
            labelStatus.setText("Status: ✅ Running");
            labelStatus.setStyle("-fx-text-fill: #4caf50;");
        } else {
            labelStatus.setText("Status: 🔴 Stopped");
            labelStatus.setStyle("-fx-text-fill: #e57373;");
        }
    }

    private void showError(String msg) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Telegram Bot");
        alert.setHeaderText(null);
        alert.setContentText(msg);
        alert.showAndWait();
    }

    private void showInfo(String msg) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Telegram Bot");
        alert.setHeaderText(null);
        alert.setContentText(msg);
        alert.showAndWait();
    }
}
