package dev.frostguard.engine.emulator.instance;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.platform.PlatformPaths;
import dev.frostguard.engine.emulator.EmulatorInstance;
import dev.frostguard.engine.service.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BlueStacks on macOS (including BlueStacks Air) — ADB automation via {@code hd-adb}
 * after enabling Settings → Advanced → Android Debug Bridge (default port 5555).
 */
public class BlueStacksAirEmulatorInstance extends EmulatorInstance {

    private static final Logger LOG = LoggerFactory.getLogger(BlueStacksAirEmulatorInstance.class);
    private static final String[] LAUNCH_BUNDLE_NAMES = {
            "BlueStacks.app",
            "BlueStacks Air.app",
    };

    private final String appBundlePath;

    public BlueStacksAirEmulatorInstance(String appBundlePath) {
        super(appBundlePath != null ? appBundlePath : "");
        this.appBundlePath = resolveBundlePath(appBundlePath);
    }

    @Override
    protected String adbPath() {
        String hdAdb = PlatformPaths.resolveBlueStacksHdAdb(appBundlePath);
        if (hdAdb != null) {
            LOG.info("Using BlueStacks hd-adb: {}", hdAdb);
            return hdAdb;
        }
        return super.adbPath();
    }

    @Override
    protected String getDeviceSerial(String emulatorNumber) {
        HashMap<String, String> config = new HashMap<>(ConfigService.obtain().loadGlobalSettings());
        String host = config.getOrDefault(ConfigurationKeyEnum.BLUESTACKS_AIR_ADB_HOST_STRING.name(), "127.0.0.1");
        int basePort = parsePort(config.get(ConfigurationKeyEnum.BLUESTACKS_AIR_ADB_PORT_INT.name()), 5555);
        String target = emulatorNumber == null ? "" : emulatorNumber.trim();
        if (target.isBlank()) {
            return host + ":" + basePort;
        }
        if (target.contains(":")) {
            return target;
        }

        int numericValue = parsePort(target, -1);
        if (numericValue >= 1000) {
            return host + ":" + numericValue;
        }
        if (numericValue >= 0) {
            int port = basePort + numericValue;
            return host + ":" + port;
        }

        LOG.warn("Unsupported BlueStacks emulator identifier '{}'. Falling back to {}:{}",
                emulatorNumber, host, basePort);
        return host + ":" + basePort;
    }

    /**
     * Visible for unit tests — same serial mapping as {@link #getDeviceSerial(String)}.
     */
    public static String mapDeviceSerial(String emulatorNumber, String host, int basePort) {
        String target = emulatorNumber == null ? "" : emulatorNumber.trim();
        if (target.isBlank()) {
            return host + ":" + basePort;
        }
        if (target.contains(":")) {
            return target;
        }
        int numericValue = parsePort(target, -1);
        if (numericValue >= 1000) {
            return host + ":" + numericValue;
        }
        if (numericValue >= 0) {
            return host + ":" + (basePort + numericValue);
        }
        return host + ":" + basePort;
    }

    @Override
    public void launchEmulator(String emulatorNumber) {
        if (tryOpenBlueStacksApp()) {
            LOG.info("Requested BlueStacks launch via macOS open command");
        } else {
            LOG.info("Start BlueStacks manually, then enable ADB (Settings → Advanced → Android Debug Bridge)");
        }
        waitForAdbDevice(emulatorNumber, 120);
    }

    @Override
    public void closeEmulator(String emulatorNumber) {
        LOG.info(
                "BlueStacks instance {} left running (no CLI shutdown). Use BlueStacks UI or force-stop the game via ADB if needed.",
                emulatorNumber);
    }

    @Override
    public boolean isRunning(String emulatorNumber) {
        return isAdbDeviceOnline(emulatorNumber);
    }

    private String resolveBundlePath(String configuredPath) {
        if (configuredPath != null && !configuredPath.isBlank()) {
            File bundle = new File(configuredPath);
            if (bundle.isDirectory()) {
                return bundle.getAbsolutePath();
            }
        }
        String detected = PlatformPaths.detectBlueStacksMacBundle();
        if (detected != null) {
            return detected;
        }
        return configuredPath != null ? configuredPath : "";
    }

    private boolean tryOpenBlueStacksApp() {
        if (appBundlePath != null && !appBundlePath.isBlank()) {
            File bundle = new File(appBundlePath);
            if (bundle.isDirectory()) {
                return runOpenCommand(bundle.getAbsolutePath());
            }
        }
        for (String appName : LAUNCH_BUNDLE_NAMES) {
            File bundle = new File("/Applications", appName);
            if (bundle.isDirectory() && runOpenCommand(bundle.getAbsolutePath())) {
                return true;
            }
        }
        return runOpenCommand("-a", "BlueStacks") || runOpenCommand("-a", "BlueStacks Air");
    }

    private boolean runOpenCommand(String... args) {
        try {
            String[] command = new String[args.length + 1];
            command[0] = "open";
            System.arraycopy(args, 0, command, 1, args.length);
            Process process = new ProcessBuilder(command).start();
            return process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            LOG.debug("open command failed: {}", e.getMessage());
            return false;
        }
    }

    private void waitForAdbDevice(String emulatorNumber, int timeoutSeconds) {
        String serial = getDeviceSerial(emulatorNumber);
        LOG.info("Waiting for BlueStacks ADB device {} using {}", serial, getAdbPath());
        for (int i = 0; i < timeoutSeconds; i++) {
            if (isAdbDeviceOnline(emulatorNumber)) {
                LOG.info("BlueStacks ADB device is online: {}", serial);
                return;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        LOG.warn("BlueStacks ADB device not online after {}s for index {}", timeoutSeconds, emulatorNumber);
    }

    private boolean isAdbDeviceOnline(String emulatorNumber) {
        String serial = getDeviceSerial(emulatorNumber);
        try {
            String adb = getAdbPath();
            ProcessBuilder connectPb = new ProcessBuilder(adb, "connect", serial);
            Process connectProcess = connectPb.start();
            connectProcess.waitFor(10, TimeUnit.SECONDS);
            String connectOutput;
            try (BufferedReader connectReader = new BufferedReader(new InputStreamReader(connectProcess.getInputStream()))) {
                connectOutput = connectReader.readLine();
            }

            ProcessBuilder statePb = new ProcessBuilder(adb, "-s", serial, "get-state");
            Process stateProcess = statePb.start();
            boolean finished = stateProcess.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                stateProcess.destroyForcibly();
                return false;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stateProcess.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && line.trim().equalsIgnoreCase("device")) {
                    return true;
                }
            }

            ProcessBuilder devicesPb = new ProcessBuilder(adb, "devices");
            Process devicesProcess = devicesPb.start();
            boolean devicesFinished = devicesProcess.waitFor(5, TimeUnit.SECONDS);
            if (!devicesFinished) {
                devicesProcess.destroyForcibly();
                return false;
            }
            try (BufferedReader devicesReader = new BufferedReader(new InputStreamReader(devicesProcess.getInputStream()))) {
                String line;
                while ((line = devicesReader.readLine()) != null) {
                    if (line.startsWith(serial) && line.contains("device")) {
                        return true;
                    }
                }
            }
            LOG.debug("ADB state check failed for {}. connectOutput={}", serial, connectOutput);
            return false;
        } catch (Exception e) {
            LOG.debug("ADB state check failed for {}: {}", serial, e.getMessage());
            return false;
        }
    }

    private static int parsePort(String value, int defaultPort) {
        if (value == null || value.isBlank()) {
            return defaultPort;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultPort;
        }
    }
}
