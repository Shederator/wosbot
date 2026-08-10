package dev.frostguard.engine.emulator.instance;

import dev.frostguard.engine.emulator.EmulatorInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

// Drives MEmu Player via memuc.exe.  ADB port = 21503 + (index * 10).
public class MEmuEmulatorInstance extends EmulatorInstance {

    private static final Logger LOG = LoggerFactory.getLogger(MEmuEmulatorInstance.class);

    public MEmuEmulatorInstance(String path) { super(path); }

    @Override protected String getDeviceSerial(String id) {
        return "127.0.0.1:" + (21503 + Integer.parseInt(id) * 10);
    }

    @Override public void launchEmulator(String id) { memuc("start", id, 60); LOG.info("MEmu {} starting", id); }
    @Override public void closeEmulator(String id)  { memuc("stop", id, 60);  LOG.info("MEmu {} stopping", id); }

    @Override public boolean isRunning(String id) {
        try {
            Process p = new ProcessBuilder(cli(), "isvmrunning", "-i", id)
                    .directory(new File(consolePath).getParentFile()).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String t = line.trim();
                    if (!t.isEmpty() && !"Not Running".equalsIgnoreCase(t)) return true;
                }
            }
            p.waitFor(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); LOG.error("MEmu status interrupted", e); }
          catch (IOException e)          { LOG.error("MEmu status I/O error for {}", id, e); }
        return false;
    }

    private void memuc(String action, String id, int timeoutSec) {
        try {
            Process p = new ProcessBuilder(cli(), action, "-i", id)
                    .directory(new File(consolePath).getParentFile()).start();
            p.waitFor(timeoutSec, TimeUnit.SECONDS);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
          catch (IOException e)          { LOG.error("memuc {} failed", action, e); }
    }

    private String cli() { return Paths.get(consolePath, "memuc.exe").toString(); }
}
