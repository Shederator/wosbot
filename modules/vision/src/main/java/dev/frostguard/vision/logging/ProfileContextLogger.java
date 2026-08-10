package dev.frostguard.vision.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.frostguard.api.domain.AccountDescriptor;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;

/**
 * Orchestrates profile-specific logging by multiplexing SLF4J output into
 * dedicated rolling file appenders.  Each profile gets its own .log file
 * which is automatically compressed and rotated upon reaching 10MB.
 */
public final class ProfileContextLogger {

    private static final Logger rootLog = LoggerFactory.getLogger(ProfileContextLogger.class);
    private static final Map<Long, PrintWriter> writerRegistry = new ConcurrentHashMap<>();
    
    private static final SimpleDateFormat logTimestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private static final SimpleDateFormat fileTimestamp = new SimpleDateFormat("yyyy-MM-dd");
    
    private static final long MAX_BYTES = 10_485_760L; // 10MB
    private static final int ROLLOVER_COUNT = 5;

    private final Logger targetLog;
    private final AccountDescriptor profile;
    private final String sourceName;

    /**
     * Constructs a new logger bound to a specific profile context.
     * 
     * @param origin  The class emitting the logs
     * @param profile The profile context, or null for general logging
     */
    public ProfileContextLogger(Class<?> origin, AccountDescriptor profile) {
        this.targetLog = LoggerFactory.getLogger(origin);
        this.profile = profile;
        this.sourceName = origin.getSimpleName();
        
        ensureLogDirectory();

        if (profile != null && !writerRegistry.containsKey(profile.getId())) {
            try {
                initWriter(profile);
            } catch (IOException e) {
                rootLog.error("Stream allocation failed for profile {}: {}", profile.getName(), e.getMessage());
            }
        }
    }

    /** Helper for general class-level logging without profile context. */
    public ProfileContextLogger(Class<?> origin) {
        this(origin, null);
    }

    private void ensureLogDirectory() {
        try {
            Files.createDirectories(Paths.get("logs"));
        } catch (IOException e) {
            rootLog.error("Base directory creation failed: {}", e.getMessage());
        }
    }

    private synchronized void initWriter(AccountDescriptor acc) throws IOException {
        if (writerRegistry.containsKey(acc.getId())) return;

        String path = String.format("logs/account_%s_%d.log", cleanPath(acc.getName()), acc.getId());
        File handle = new File(path);

        if (handle.exists() && handle.length() > MAX_BYTES) {
            rollover(handle);
        } else if (!handle.exists()) {
            handle.createNewFile();
        }

        PrintWriter pw = new PrintWriter(new FileWriter(handle, true), true);
        writerRegistry.put(acc.getId(), pw);

        pw.println("----------------------------------------------------------");
        pw.println("Session Started: " + logTimestamp.format(new Date()));
        pw.println("Target Profile: " + acc.getName() + " [#" + acc.getId() + "]");
        pw.println("Device Slot: " + acc.getEmulatorNumber());
        pw.println("----------------------------------------------------------");
    }

    private void rollover(File current) throws IOException {
        String base = current.getName().substring(0, current.getName().lastIndexOf('.'));
        String stamp = fileTimestamp.format(new Date());
        
        int slot = 0;
        boolean vacant = false;

        while (!vacant && slot < ROLLOVER_COUNT) {
            File arch = new File(current.getParent(), String.format("%s.%s.%d.gz", base, stamp, slot));
            if (!arch.exists()) {
                vacant = true;
            } else {
                slot++;
            }
        }

        if (!vacant) {
            File[] existing = current.getParentFile().listFiles((dir, name) -> 
                name.startsWith(base) && name.endsWith(".gz"));
            
            if (existing != null && existing.length > 0) {
                File oldest = existing[0];
                for (File f : existing) {
                    if (f.getName().compareTo(oldest.getName()) < 0) oldest = f;
                }
                oldest.delete();
            }
            slot = 0;
        }

        File target = new File(current.getParent(), String.format("%s.%s.%d.gz", base, stamp, slot));
        
        try (InputStream in = new BufferedInputStream(new FileInputStream(current));
             OutputStream out = new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(target)))) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
        
        new FileWriter(current, false).close(); // Purge original
    }

    private static String cleanPath(String raw) {
        return (raw == null) ? "null" : raw.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String decorate(String level, String msg) {
        return String.format("%s [%s] %s: %s", 
            logTimestamp.format(new Date()), level, sourceName, msg);
    }

    public void info(String msg) {
        targetLog.info((profile != null) ? (profile.getName() + " | " + msg) : msg);
        dispatch("INFO", msg);
    }

    public void debug(String msg) {
        targetLog.debug(msg);
        dispatch("DEBUG", msg);
    }

    public void warn(String msg) {
        targetLog.warn(msg);
        dispatch("WARN", msg);
    }

    public void error(String msg) {
        targetLog.error(msg);
        dispatch("ERROR", msg);
    }

    public void error(String msg, Throwable cause) {
        targetLog.error(msg, cause);
        if (profile != null) {
            PrintWriter pw = writerRegistry.get(profile.getId());
            if (pw != null) {
                enforceSizeLimit();
                pw.println(decorate("ERROR", msg));
                cause.printStackTrace(pw);
            }
        }
    }

    private void dispatch(String level, String msg) {
        if (profile != null) {
            PrintWriter pw = writerRegistry.get(profile.getId());
            if (pw != null) {
                enforceSizeLimit();
                pw.println(decorate(level, msg));
            }
        }
    }

    private void enforceSizeLimit() {
        if (profile == null) return;
        
        String path = String.format("logs/account_%s_%d.log", cleanPath(profile.getName()), profile.getId());
        File handle = new File(path);
        
        if (handle.exists() && handle.length() > MAX_BYTES) {
            try {
                PrintWriter pw = writerRegistry.remove(profile.getId());
                if (pw != null) pw.close();
                
                rollover(handle);
                initWriter(profile);
            } catch (IOException e) {
                rootLog.error("Rollover failed for {}: {}", profile.getName(), e.getMessage());
            }
        }
    }

    /** Flushes and closes all active profile log streams. */
    public static void shutdown() {
        writerRegistry.values().forEach(PrintWriter::close);
        writerRegistry.clear();
    }
}
