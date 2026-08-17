package dev.frostguard.engine.service;

import dev.frostguard.api.runtime.WorkspacePaths;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.schedule.CustomTaskConfigurable;
import dev.frostguard.engine.schedule.DelayedTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import javax.tools.*;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service that manages runtime compilation, loading and execution of custom
 * {@code .java} task files exported by the Task Builder.
 *
 * <p>Custom tasks are compiled to a {@code custom_tasks/} directory and loaded
 * via a dedicated class loader so they can be instantiated as {@link DelayedTask}
 * instances and scheduled in the normal {@code TaskQueue} infrastructure.</p>
 *
 * <p>This is a singleton — use {@link #getInstance()} to obtain the instance.</p>
 */
public class CustomTaskService {

    private static final Logger logger = LoggerFactory.getLogger(CustomTaskService.class);
    /** Fully-qualified package that generated task classes live in. */
    private static final String TASK_PACKAGE = "dev.frostguard.engine.listener.task.impl";
    private static final String TASK_PACKAGE_PATH = TASK_PACKAGE.replace('.', '/');

    private static final CustomTaskService INSTANCE = new CustomTaskService();

    /** Directory where compiled .class files are stored. */
    private final Path compiledDir;

    /** JSON file that persists task state across restarts. */
    private final Path jsonFile;

    /** Maps className → loaded Class<?> */
    private final Map<String, Class<?>> loadedClasses = new ConcurrentHashMap<>();

    /** Maps className → source .java File */
    private final Map<String, File> sourceFiles = new ConcurrentHashMap<>();

    /** Maps className → enabled task settings */
    private final Map<String, CustomTaskSettings> enabledTasks = new ConcurrentHashMap<>();

    /** Maps className → persisted task settings regardless of enabled state. */
    private final Map<String, CustomTaskSettings> savedTaskSettings = new ConcurrentHashMap<>();

    private URLClassLoader taskClassLoader;

    // ========================================================================
    // Settings DTO (immutable)
    // ========================================================================

    /**
     * Immutable configs for an enabled custom task.
     */
    public static class CustomTaskSettings {

        private final String className;
        private final String customName;
        private final int offsetMinutes;
        private final int priority;
        private final String firstExecutionUtc;
        private final Integer followUpDelayHours;

        public CustomTaskSettings(String className, String customName, int offsetMinutes, int priority) {
            this(className, customName, offsetMinutes, priority, null, 8);
        }

        public CustomTaskSettings(String className, String customName, int offsetMinutes, int priority,
                                  String firstExecutionUtc, Integer followUpDelayHours) {
            this.className = className;
            this.customName = customName;
            this.offsetMinutes = offsetMinutes;
            this.priority = priority;
            this.firstExecutionUtc = firstExecutionUtc;
            this.followUpDelayHours = followUpDelayHours != null ? followUpDelayHours : 8;
        }

        public String getClassName()   { return className; }
        public String getCustomName()  { return customName; }
        public int getOffsetMinutes()  { return offsetMinutes; }
        public int getPriority()       { return priority; }
        public String getFirstExecutionUtc() { return firstExecutionUtc; }
        public Integer getFollowUpDelayHours() { return followUpDelayHours; }
    }

    // ========================================================================
    // Persistence DTO
    // ========================================================================

    /**
     * JSON-serializable entry for persisting custom task state.
     */
    public static class SavedTaskEntry {

        @JsonProperty("className")    private String className;
        @JsonProperty("sourcePath")   private String sourcePath;
        @JsonProperty("enabled")      private boolean enabled;
        @JsonProperty("offsetMinutes") private int offsetMinutes;
        @JsonProperty("priority")     private int priority;
        @JsonProperty("firstExecutionUtc") private String firstExecutionUtc;
        @JsonProperty("followUpDelayHours") private Integer followUpDelayHours;

        /** Default constructor for Jackson. */
        public SavedTaskEntry() {}

        public SavedTaskEntry(String className, String sourcePath, boolean enabled, int offsetMinutes, int priority) {
            this(className, sourcePath, enabled, offsetMinutes, priority, null, 8);
        }

        public SavedTaskEntry(String className, String sourcePath, boolean enabled, int offsetMinutes, int priority,
                              String firstExecutionUtc, Integer followUpDelayHours) {
            this.className = className;
            this.sourcePath = sourcePath;
            this.enabled = enabled;
            this.offsetMinutes = offsetMinutes;
            this.priority = priority;
            this.firstExecutionUtc = firstExecutionUtc;
            this.followUpDelayHours = followUpDelayHours != null ? followUpDelayHours : 8;
        }

        public String  getClassName()     { return className; }
        public void    setClassName(String v)  { this.className = v; }
        public String  getSourcePath()    { return sourcePath; }
        public void    setSourcePath(String v) { this.sourcePath = v; }
        public boolean isEnabled()        { return enabled; }
        public void    setEnabled(boolean v)   { this.enabled = v; }
        public int     getOffsetMinutes() { return offsetMinutes; }
        public void    setOffsetMinutes(int v) { this.offsetMinutes = v; }
        public int     getPriority()      { return priority; }
        public void    setPriority(int v)      { this.priority = v; }
        public String  getFirstExecutionUtc() { return firstExecutionUtc; }
        public void    setFirstExecutionUtc(String v) { this.firstExecutionUtc = v; }
        public Integer getFollowUpDelayHours() { return followUpDelayHours; }
        public void    setFollowUpDelayHours(Integer v) { this.followUpDelayHours = v; }
    }

    // ========================================================================
    // Construction & Singleton
    // ========================================================================

    private CustomTaskService() {
        compiledDir = WorkspacePaths.current().customTasks();
        jsonFile = compiledDir.resolve("custom_tasks.json");
        try {
            Files.createDirectories(compiledDir);
        } catch (IOException e) {
            logger.error("Failed to create custom_tasks directory", e);
        }
        loadSavedTasks();
    }

    public static CustomTaskService getInstance() {
        return INSTANCE;
    }

    public synchronized void shutdown() {
        if (taskClassLoader != null) {
            try {
                taskClassLoader.close();
            } catch (IOException exception) {
                logger.warn("Could not close custom task class loader: {}", exception.getMessage());
            }
            taskClassLoader = null;
        }
        loadedClasses.clear();
        sourceFiles.clear();
        logger.info("Custom task service stopped");
    }

    // ========================================================================
    // Compile & Load
    // ========================================================================

    /**
     * Compiles a {@code .java} source file and loads the resulting class.
     *
     * @param javaFile the .java source file
     * @return the loaded class name, or {@code null} on failure
     */
    public String compileAndLoad(File javaFile) {
        if (javaFile == null || !javaFile.exists() || !javaFile.getName().endsWith(".java")) {
            logger.warn("Invalid java file: {}", javaFile);
            return null;
        }

        String className = javaFile.getName().replace(".java", "");

        if (!compileSource(javaFile, className)) {
            return null;
        }

        return loadCompiledClass(javaFile, className);
    }

    // ── Compilation ────────────────────────────────────────────────────────

    private boolean compileSource(File javaFile, String className) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            logger.error("No Java compiler available. Make sure you are running on a JDK, not a JRE.");
            return false;
        }

        String classpath = buildClasspath();

        // Ensure package directory structure exists
        Path packageDir = compiledDir.resolve(TASK_PACKAGE_PATH);
        try {
            Files.createDirectories(packageDir);
        } catch (IOException e) {
            logger.error("Failed to create package directory", e);
            return false;
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null);

        try {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT,
                    Collections.singletonList(compiledDir.toFile()));
        } catch (IOException e) {
            logger.error("Failed to set class output location", e);
            return false;
        }

        Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjects(javaFile);
        List<String> options = Arrays.asList(
                "-classpath", classpath,
                "-d", compiledDir.toAbsolutePath().toString()
        );

        JavaCompiler.CompilationTask task = compiler.getTask(
                null, fileManager, diagnostics, options, null, compilationUnits);
        boolean success = task.call();

        try { fileManager.close(); } catch (IOException ignored) {}

        if (!success) {
            StringBuilder errors = new StringBuilder("Compilation failed for " + className + ":\n");
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                errors.append("  Line ").append(d.getLineNumber())
                      .append(": ").append(d.getMessage(null)).append("\n");
            }
            logger.error(errors.toString());
            return false;
        }

        logger.info("Successfully compiled: {}", className);
        return true;
    }

    // ── Class Loading ──────────────────────────────────────────────────────

    private String loadCompiledClass(File javaFile, String className) {
        try {
            reloadClassLoader();
            String fqcn = TASK_PACKAGE + "." + className;
            Class<?> clazz = taskClassLoader.loadClass(fqcn);
            loadedClasses.put(className, clazz);
            sourceFiles.put(className, javaFile);
            logger.info("Successfully loaded class: {}", fqcn);
            return className;
        } catch (Exception e) {
            logger.error("Failed to load class: {}", className, e);
            return null;
        }
    }

    // ── Classpath Construction ──────────────────────────────────────────────

    private String buildClasspath() {
        StringBuilder cp = new StringBuilder(System.getProperty("java.class.path"));
        addJarsFromDir(cp, Paths.get(System.getProperty("user.dir"), "lib"));
        addJarsFromDir(cp, Paths.get(System.getProperty("user.dir")));
        cp.append(File.pathSeparator).append(compiledDir.toAbsolutePath());
        return cp.toString();
    }

    private void addJarsFromDir(StringBuilder cp, Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jar")) {
            for (Path jar : stream) {
                cp.append(File.pathSeparator).append(jar.toAbsolutePath());
            }
        } catch (IOException ignored) {}
    }

    private void reloadClassLoader() throws Exception {
        if (taskClassLoader != null) {
            try { taskClassLoader.close(); } catch (IOException ignored) {}
        }
        taskClassLoader = new URLClassLoader(
                new URL[]{ compiledDir.toUri().toURL() },
                getClass().getClassLoader()
        );
    }

    // ========================================================================
    // Task Instantiation
    // ========================================================================

    /**
     * Creates a new {@link DelayedTask} instance from a previously compiled custom class.
     */
    public DelayedTask createTask(String className, AccountDescriptor profile) {
        Class<?> clazz = loadedClasses.get(className);
        if (clazz == null) {
            logger.warn("Class not loaded: {}", className);
            return null;
        }

        try {
            Constructor<?> ctor = clazz.getConstructor(AccountDescriptor.class, TpDailyTaskEnum.class);
            return (DelayedTask) ctor.newInstance(profile, TpDailyTaskEnum.CUSTOM_TASK);
        } catch (Exception e) {
            logger.error("Failed to instantiate custom task: {}", className, e);
            return null;
        }
    }

    /**
     * Creates a DelayedTask with the stored custom name, priority, and repeat interval applied.
     */
    public DelayedTask createTaskWithSettings(CustomTaskSettings configs, AccountDescriptor profile) {
        DelayedTask task = createTask(configs.getClassName(), profile);
        if (task != null) {
            task.setTaskName(configs.getCustomName());
            task.setCustomPriority(configs.getPriority());
            task.setRepeatIntervalMinutes(configs.getOffsetMinutes());
            task.setCustomTaskIdentifier(configs.getClassName());
            if (task instanceof CustomTaskConfigurable configurableTask) {
                configurableTask.applyCustomTaskSettings(configs);
            }
        }
        return task;
    }

    // ========================================================================
    // Task Registry Management
    // ========================================================================

    /**
     * Removes a custom task class from the registry and deletes its compiled output.
     */
    public void removeTask(String className) {
        loadedClasses.remove(className);
        sourceFiles.remove(className);
        enabledTasks.remove(className);
        savedTaskSettings.remove(className);

        Path classFile = compiledDir.resolve(TASK_PACKAGE_PATH + "/" + className + ".class");
        try {
            Files.deleteIfExists(classFile);
        } catch (IOException e) {
            logger.warn("Could not delete class file: {}", classFile, e);
        }
        saveTasks();
    }

    /**
     * Marks a custom task as enabled with the given configs.
     */
    public void enableTask(String className, String customName, int offsetMinutes, int priority) {
        enableTask(className, customName, offsetMinutes, priority, null, 8);
    }

    /**
     * Marks a custom task as enabled with the given settings.
     */
    public void enableTask(String className, String customName, int offsetMinutes, int priority,
                           String firstExecutionUtc, Integer followUpDelayHours) {
        CustomTaskSettings settings = new CustomTaskSettings(className, customName, offsetMinutes, priority,
                firstExecutionUtc, followUpDelayHours);
        savedTaskSettings.put(className, settings);
        enabledTasks.put(className, settings);
        saveTasks();
    }

    /**
     * Marks a custom task as disabled.
     */
    public void disableTask(String className) {
        enabledTasks.remove(className);
        saveTasks();
    }

    /**
     * Updates persisted settings for a loaded custom task without changing its enabled state.
     */
    public void saveTaskSettings(String className, String customName, int offsetMinutes, int priority,
                                 String firstExecutionUtc, Integer followUpDelayHours) {
        CustomTaskSettings settings = new CustomTaskSettings(className, customName, offsetMinutes, priority,
                firstExecutionUtc, followUpDelayHours);
        savedTaskSettings.put(className, settings);
        if (enabledTasks.containsKey(className)) {
            enabledTasks.put(className, settings);
        }
        saveTasks();
    }

    // ========================================================================
    // Queries
    // ========================================================================

    public boolean isLoaded(String className) {
        return loadedClasses.containsKey(className);
    }

    public Set<String> getLoadedClassNames() {
        return Collections.unmodifiableSet(loadedClasses.keySet());
    }

    public boolean supportsCustomTaskConfiguration(String className) {
        Class<?> clazz = loadedClasses.get(className);
        return clazz != null && CustomTaskConfigurable.class.isAssignableFrom(clazz);
    }

    public File getSourceFile(String className) {
        return sourceFiles.get(className);
    }

    /**
     * Returns all currently enabled custom task configs.
     */
    public Collection<CustomTaskSettings> getEnabledTasks() {
        return Collections.unmodifiableCollection(enabledTasks.values());
    }

    // ========================================================================
    // Persistence
    // ========================================================================

    /**
     * Loads saved task entries from {@code custom_tasks.json}, recompiles them,
     * and re-registers any that were marked as enabled.
     */
    private void loadSavedTasks() {
        if (!Files.exists(jsonFile)) {
            return;
        }

        List<SavedTaskEntry> entries;
        try {
            ObjectMapper mapper = new ObjectMapper();
            entries = mapper.readValue(jsonFile.toFile(), new TypeReference<List<SavedTaskEntry>>() {});
        } catch (Exception e) {
            logger.error("Failed to read custom_tasks.json", e);
            return;
        }

        if (entries == null || entries.isEmpty()) {
            return;
        }

        for (SavedTaskEntry entry : entries) {
            logger.info("Loading saved task: className={}, enabled={}, offset={}, priority={}, source={}",
                    entry.getClassName(), entry.isEnabled(), entry.getOffsetMinutes(),
                    entry.getPriority(), entry.getSourcePath());

            File sourceFile = new File(entry.getSourcePath());
            if (!sourceFile.exists()) {
                logger.warn("Source file not found for saved task {}: {}",
                        entry.getClassName(), entry.getSourcePath());
                continue;
            }

            String loaded = compileAndLoad(sourceFile);
            if (loaded == null) {
                logger.warn("Failed to reload saved task: {}", entry.getClassName());
                continue;
            }

            if (entry.isEnabled()) {
                enableTask(entry.getClassName(), entry.getClassName(),
                        entry.getOffsetMinutes(), entry.getPriority(),
                        entry.getFirstExecutionUtc(), entry.getFollowUpDelayHours());
                logger.info("Restored enabled custom task: {} (offset={}m, priority={})",
                        entry.getClassName(), entry.getOffsetMinutes(), entry.getPriority());
            } else {
                saveTaskSettings(entry.getClassName(), entry.getClassName(),
                        entry.getOffsetMinutes(), entry.getPriority(),
                        entry.getFirstExecutionUtc(), entry.getFollowUpDelayHours());
                logger.info("Task {} is saved but not enabled, skipping", entry.getClassName());
            }
        }

        logger.info("loadSavedTasks completed. Enabled tasks count: {}", enabledTasks.size());
    }

    /**
     * Persists the current task registry (all loaded tasks + their enabled state)
     * to {@code custom_tasks.json}.
     */
    public void saveTasks() {
        List<SavedTaskEntry> entries = new ArrayList<>();
        for (Map.Entry<String, File> e : sourceFiles.entrySet()) {
            String className = e.getKey();
            CustomTaskSettings configs = enabledTasks.get(className);
            CustomTaskSettings persistedSettings = savedTaskSettings.get(className);
            boolean enabled  = configs != null;
            int offset       = enabled ? configs.getOffsetMinutes()
                    : persistedSettings != null ? persistedSettings.getOffsetMinutes() : 60;
            int priority     = enabled ? configs.getPriority()
                    : persistedSettings != null ? persistedSettings.getPriority() : 0;
            String firstExecutionUtc = enabled ? configs.getFirstExecutionUtc()
                    : persistedSettings != null ? persistedSettings.getFirstExecutionUtc() : null;
            Integer followUpDelayHours = enabled ? configs.getFollowUpDelayHours()
                    : persistedSettings != null ? persistedSettings.getFollowUpDelayHours() : 8;
            entries.add(new SavedTaskEntry(className, e.getValue().getAbsolutePath(),
                    enabled, offset, priority, firstExecutionUtc, followUpDelayHours));
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            mapper.writeValue(jsonFile.toFile(), entries);
        } catch (IOException ex) {
            logger.error("Failed to write custom_tasks.json", ex);
        }
    }

    /**
     * Returns all saved task entries (for UI restoration).
     */
    public List<SavedTaskEntry> getAllSavedEntries() {
        List<SavedTaskEntry> entries = new ArrayList<>();
        for (Map.Entry<String, File> e : sourceFiles.entrySet()) {
            String className = e.getKey();
            CustomTaskSettings configs = enabledTasks.get(className);
            CustomTaskSettings persistedSettings = savedTaskSettings.get(className);
            boolean enabled  = configs != null;
            int offset       = enabled ? configs.getOffsetMinutes()
                    : persistedSettings != null ? persistedSettings.getOffsetMinutes() : 60;
            int priority     = enabled ? configs.getPriority()
                    : persistedSettings != null ? persistedSettings.getPriority() : 0;
            String firstExecutionUtc = enabled ? configs.getFirstExecutionUtc()
                    : persistedSettings != null ? persistedSettings.getFirstExecutionUtc() : null;
            Integer followUpDelayHours = enabled ? configs.getFollowUpDelayHours()
                    : persistedSettings != null ? persistedSettings.getFollowUpDelayHours() : 8;
            entries.add(new SavedTaskEntry(className, e.getValue().getAbsolutePath(),
                    enabled, offset, priority, firstExecutionUtc, followUpDelayHours));
        }
        return entries;
    }
}
