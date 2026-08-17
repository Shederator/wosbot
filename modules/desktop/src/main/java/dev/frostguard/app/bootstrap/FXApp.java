package dev.frostguard.app.bootstrap;

import atlantafx.base.theme.PrimerDark;
import dev.frostguard.app.ApplicationTitle;
import dev.frostguard.app.bootstrap.WindowBoundsPolicy.WindowBounds;
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
import java.util.List;
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
    private WindowBounds normalWindowBounds;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) throws IOException {
        if (!ChannelOnboarding.showBeforeStartup()) {
            Platform.exit();
            return;
        }
        Main.initializeRuntimeServices(false);
        preferences = WorkspacePreferences.currentNode("window");
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());

        LauncherLayoutController controller = new LauncherLayoutController(stage);
        Parent root = loadLauncherRoot(controller);
        Scene scene = createMainScene(root);

        configureStage(stage, scene);
        stage.show();
        GiftCodeAutomationService.getInstance().start();
        restoreWindowBounds(stage);
        trackNormalWindowBounds(stage);
        WindowsWindowManager.install(stage).ifPresent(controller::enableNativeWindowing);
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
        stage.setTitle(ApplicationTitle.current());
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

        width = Double.isFinite(width) && width > 0 ? width : DEFAULT_W;
        height = Double.isFinite(height) && height > 0 ? height : DEFAULT_H;

        WindowBounds saved = new WindowBounds(x, y, width, height);
        List<Rectangle2D> screens = Screen.getScreens().stream().map(Screen::getVisualBounds).toList();
        var recovered = WindowBoundsPolicy.recover(saved, screens, MIN_VISIBLE_AREA);
        if (recovered.isPresent()) {
            applyWindowBounds(stage, recovered.get());
            persistWindowBounds(recovered.get());
            return;
        }

        stage.setWidth(Math.min(width, Screen.getPrimary().getVisualBounds().getWidth()));
        stage.setHeight(Math.min(height, Screen.getPrimary().getVisualBounds().getHeight()));
        centerOnPrimaryScreen(stage, stage.getWidth(), stage.getHeight());
    }

    private void maybeAutoStart(LauncherLayoutController controller) {
        if (getParameters().getRaw().contains("--autostart")) {
            Platform.runLater(controller::forceStartBot);
        }
    }

    private void shutdownFromUi(Stage stage) {
        rememberWindowBounds(stage);
        ApplicationLifecycle.exitNormally(0);
    }

    private void rememberWindowBounds(Stage stage) {
        captureNormalWindowBounds(stage);
        if (normalWindowBounds != null) {
            persistWindowBounds(normalWindowBounds);
        }
    }

    private void trackNormalWindowBounds(Stage stage) {
        captureNormalWindowBounds(stage);
        stage.xProperty().addListener((observable, oldValue, newValue) -> captureNormalWindowBounds(stage));
        stage.yProperty().addListener((observable, oldValue, newValue) -> captureNormalWindowBounds(stage));
        stage.widthProperty().addListener((observable, oldValue, newValue) -> captureNormalWindowBounds(stage));
        stage.heightProperty().addListener((observable, oldValue, newValue) -> captureNormalWindowBounds(stage));
    }

    private void captureNormalWindowBounds(Stage stage) {
        if (!stage.isMaximized() && !stage.isIconified()) {
            normalWindowBounds = new WindowBounds(
                    stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
        }
    }

    private void applyWindowBounds(Stage stage, WindowBounds bounds) {
        stage.setWidth(bounds.width());
        stage.setHeight(bounds.height());
        stage.setX(bounds.x());
        stage.setY(bounds.y());
    }

    private void persistWindowBounds(WindowBounds bounds) {
        preferences.putDouble(KEY_X, bounds.x());
        preferences.putDouble(KEY_Y, bounds.y());
        preferences.putDouble(KEY_W, bounds.width());
        preferences.putDouble(KEY_H, bounds.height());
    }

    private void centerOnPrimaryScreen(Stage stage, double width, double height) {
        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        double x = bounds.getMinX() + (bounds.getWidth() - width) / 2;
        double y = bounds.getMinY() + (bounds.getHeight() - height) / 2;
        stage.setX(Math.max(bounds.getMinX(), Math.min(x, bounds.getMaxX() - stage.getWidth())));
        stage.setY(Math.max(bounds.getMinY(), Math.min(y, bounds.getMaxY() - stage.getHeight())));
        logger.info("Window restored to the primary display");
    }

}
