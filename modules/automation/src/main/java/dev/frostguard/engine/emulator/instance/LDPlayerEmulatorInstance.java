package dev.frostguard.engine.emulator.instance;

import dev.frostguard.engine.emulator.EmulatorInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

// Drives LDPlayer via its ldconsole.exe CLI.  ADB port = 5555 + (index * 2).
public class LDPlayerEmulatorInstance extends EmulatorInstance {

    private static final Logger LOG = LoggerFactory.getLogger(LDPlayerEmulatorInstance.class);
    private static final int CMD_TIMEOUT = 30;

    public LDPlayerEmulatorInstance(String path) { super(path); }

    @Override protected String getDeviceSerial(String id) {
        return "127.0.0.1:" + (5555 + Integer.parseInt(id) * 2);
    }

    @Override public void launchEmulator(String id) {
        exec("launch", "--index", id);
        LOG.info("LDPlayer {} boot requested", id);
    }

    @Override public void closeEmulator(String id) {
        exec("quit", "--index", id);
        LOG.info("LDPlayer {} shutdown requested", id);
    }

    @Override public boolean isRunning(String id) {
        try {
            Process p = pb("isrunning", "--index", id).start();
            String line;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                line = r.readLine();
            }
            if (!p.waitFor(CMD_TIMEOUT, TimeUnit.SECONDS)) { p.destroyForcibly(); return false; }
            return line != null && "running".equalsIgnoreCase(line.trim());
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
          catch (IOException e)          { LOG.error("LDPlayer status check failed for {}", id, e); }
        return false;
    }

    private void exec(String... args) {
        try {
            String[] cmd = new String[args.length + 1];
            cmd[0] = cli();
            System.arraycopy(args, 0, cmd, 1, args.length);
            Process p = new ProcessBuilder(cmd).directory(new File(consolePath)).start();
            if (!p.waitFor(CMD_TIMEOUT, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
          catch (IOException e)          { LOG.error("LDPlayer CLI error: {}", String.join(" ", args), e); }
    }

    private ProcessBuilder pb(String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = cli();
        System.arraycopy(args, 0, cmd, 1, args.length);
        return new ProcessBuilder(cmd).directory(new File(consolePath));
    }

    private String cli() { return Paths.get(consolePath, "ldconsole.exe").toString(); }
}
