package cl.camodev.wosbot.emulator.impl;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

import cl.camodev.wosbot.emulator.Emulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LDPlayerEmulator extends Emulator {

    private static final Logger logger = LoggerFactory.getLogger(LDPlayerEmulator.class);
    private static final int PROCESS_TIMEOUT_SECONDS = 30;

    public LDPlayerEmulator(String consolePath) {
        super(consolePath);
    }

    @Override
    protected String getDeviceSerial(String emulatorNumber) {
        // LDPlayer exposes ADB on port 5555 + (index * 2)
        // Using 127.0.0.1:PORT format (consistent with MuMu/MEmu) so both
        // ddmlib auto-discovery AND adb connect recovery work correctly.
        // Note: The old emulator-XXXX format used the console port (5554),
        // which worked for auto-discovery but failed on adb connect recovery
        // because adb connect needs the ADB port (5555), not the console port.
        int port = 5555 + (Integer.parseInt(emulatorNumber) * 2);
        return "127.0.0.1:" + port;
    }

    @Override
    public void launchEmulator(String emulatorNumber) {
        String[] command = { consolePath + File.separator + "ldconsole.exe", "launch", "--index", emulatorNumber };
        executeCommand(command);
        logger.info("LDPlayer launched at index {}", emulatorNumber);
    }

    @Override
    public void closeEmulator(String emulatorNumber) {
        String[] command = { consolePath + File.separator + "ldconsole.exe", "quit", "--index", emulatorNumber };
        executeCommand(command);
        logger.info("LDPlayer closed at index {}", emulatorNumber);
    }

    @Override
    public boolean isRunning(String emulatorNumber) {
        try {
            String[] command = { consolePath + File.separator + "ldconsole.exe", "isrunning", "--index", emulatorNumber };
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(consolePath));

            Process process = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();

            boolean finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                logger.warn("LDPlayer isRunning check timed out for index {}", emulatorNumber);
                return false;
            }

            return line != null && line.trim().equalsIgnoreCase("running");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("LDPlayer isRunning check interrupted for index {}", emulatorNumber);
        } catch (IOException e) {
            logger.error("Error checking if LDPlayer emulator is running at index {}", emulatorNumber, e);
        }
        return false;
    }

    private void executeCommand(String[] command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(consolePath));
            Process process = pb.start();
            boolean finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                logger.warn("LDPlayer command timed out and was killed: {}", String.join(" ", command));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("LDPlayer command interrupted: {}", String.join(" ", command));
        } catch (IOException e) {
            logger.error("Error executing LDPlayer command: {}", String.join(" ", command), e);
        }
    }
}
