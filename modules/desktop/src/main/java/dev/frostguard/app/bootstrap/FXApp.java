package dev.frostguard.app.bootstrap;

import atlantafx.base.theme.PrimerDark;
import dev.frostguard.app.panel.launcher.ILauncherConstants;
import dev.frostguard.app.panel.launcher.LauncherLayoutController;
import dev.frostguard.app.panel.misc.GiftCodeAutomationService;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Objects;
import java.util.prefs.Preferences;

public class FXApp extends Application {

    private static final Logger logger = LoggerFactory.getLogger(FXApp.class);
    private static final String KEY_X = "windowX";
    private static final String KEY_Y = "windowY";
    private static final String KEY_W = "windowWidth";
    private static final String KEY_H = "windowHeight";
    private static final double DEFAULT_W = 960;
    private static final double DEFAULT_H = 560;
    private static final double MIN_VISIBLE_AREA = 100;

    private Preferences preferences;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) throws IOException {
        preferences = Preferences.userRoot().node(FXApp.class.getName());
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());

        LauncherLayoutController controller = new LauncherLayoutController(stage);
        Parent root = loadLauncherRoot(controller);
        Scene scene = createMainScene(root);

        configureStage(stage, scene);
        stage.show();
        GiftCodeAutomationService.getInstance().start();
        restoreWindowBounds(stage);
        maybeAutoStart(controller);
        stage.setOnCloseRequest(event -> shutdownFromUi(stage));
    }

    private Parent loadLauncherRoot(LauncherLayoutController controller) throws IOException {
        URL launcherLayout = Objects.requireNonNull(getClass().getResource("/layout/LauncherLayout.fxml"));
        FXMLLoader loader = new FXMLLoader(launcherLayout);
        loader.setController(controller);
        return loader.load();
    }

    private Scene createMainScene(Parent root) {
        Scene scene = new Scene(root, DEFAULT_W, DEFAULT_H);
        scene.setFill(Color.web("#12161f"));
        scene.getStylesheets().add(ILauncherConstants.getCssPath());
        return scene;
    }

    private void configureStage(Stage stage, Scene scene) {
        stage.initStyle(StageStyle.UNDECORATED);
        stage.setScene(scene);
        stage.setMinWidth(680);
        stage.setMinHeight(460);
        stage.setTitle("Frostguard Control Center");
        stage.getIcons().add(loadAppIcon());
        WindowResizer.makeResizable(stage);
    }

    private Image loadAppIcon() {
        InputStream iconStream = Objects.requireNonNull(getClass().getResourceAsStream("/icons/appIcon.png"));
        return new Image(iconStream);
    }

    private void restoreWindowBounds(Stage stage) {
        double width = preferences.getDouble(KEY_W, DEFAULT_W);
        double height = preferences.getDouble(KEY_H, DEFAULT_H);
        double x = preferences.getDouble(KEY_X, Double.NaN);
        double y = preferences.getDouble(KEY_Y, Double.NaN);

        stage.setWidth(width);
        stage.setHeight(height);

        if (!Double.isNaN(x) && !Double.isNaN(y) && isWindowRecoverable(x, y, width, height)) {
            stage.setX(x);
            stage.setY(y);
            return;
        }

        centerOnPrimaryScreen(stage, width, height);
    }

    private void maybeAutoStart(LauncherLayoutController controller) {
        if (getParameters().getRaw().contains("--autostart")) {
            Platform.runLater(controller::forceStartBot);
        }
    }

    private void shutdownFromUi(Stage stage) {
        rememberWindowBounds(stage);
        terminateAdbProcess();
        System.exit(0);
    }

    private void rememberWindowBounds(Stage stage) {
        preferences.putDouble(KEY_X, stage.getX());
        preferences.putDouble(KEY_Y, stage.getY());
        preferences.putDouble(KEY_W, stage.getWidth());
        preferences.putDouble(KEY_H, stage.getHeight());
    }

    private boolean isWindowRecoverable(double x, double y, double width, double height) {
        return Screen.getScreens().stream()
            .map(Screen::getVisualBounds)
            .anyMatch(bounds -> visibleArea(bounds, x, y, width, height) >= MIN_VISIBLE_AREA * MIN_VISIBLE_AREA);
    }

    private double visibleArea(Rectangle2D bounds, double x, double y, double width, double height) {
        double visibleLeft = Math.max(x, bounds.getMinX());
        double visibleTop = Math.max(y, bounds.getMinY());
        double visibleRight = Math.min(x + width, bounds.getMaxX());
        double visibleBottom = Math.min(y + height, bounds.getMaxY());
        double visibleWidth = Math.max(0, visibleRight - visibleLeft);
        double visibleHeight = Math.max(0, visibleBottom - visibleTop);
        return visibleWidth * visibleHeight;
    }

    private void centerOnPrimaryScreen(Stage stage, double width, double height) {
        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        double x = bounds.getMinX() + (bounds.getWidth() - width) / 2;
        double y = bounds.getMinY() + (bounds.getHeight() - height) / 2;
        stage.setX(Math.max(bounds.getMinX(), Math.min(x, bounds.getMaxX() - width)));
        stage.setY(Math.max(bounds.getMinY(), Math.min(y, bounds.getMaxY() - height)));
        logger.info("Window restored to the primary display");
    }

    private void terminateAdbProcess() {
        try {
            new ProcessBuilder("taskkill", "/F", "/IM", "adb.exe").start();
            logger.info("adb.exe shutdown requested");
        } catch (IOException e) {
            logger.warn("Unable to stop adb.exe cleanly", e);
        }
    }
}
