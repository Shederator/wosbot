# Frostguard Architecture

This document gives a high-level map of the codebase for developers who need to find the right layer before changing behavior. Frostguard is a Java 21 multi-module Maven application that drives Android emulators for Whiteout Survival automation.

## System Blocks

```mermaid
flowchart TD
    User[Developer or Operator] --> App[modules/desktop<br/>JavaFX or headless launcher]
    App --> Engine[modules/automation<br/>services, scheduler, helpers]
    App --> Tasks[modules/tasks<br/>built-in routines]
    App --> Watcher[modules/watcher<br/>Telegram watcher jar]
    App -. future update module .-> Update[platform-neutral update contracts and policy]

    Engine --> Data[modules/persistence<br/>SQLite and repositories]
    Engine --> Vision[modules/vision<br/>OCR and template matching]
    Engine --> Api[modules/api<br/>shared contracts]
    Tasks --> Engine
    Tasks --> Api
    Vision --> Api
    Data --> Api

    Engine --> Android[Android emulator<br/>ADB, screenshots, input]
    Vision --> Templates[Template PNG resources]
```

The main design rule is: UI code configures and observes automation; engine code schedules and coordinates it; task code contains game-specific business behavior; vision/data modules provide lower-level capabilities.

## Dependency Direction

Dependencies should stay mostly downward:

```mermaid
flowchart LR
    fg_app[modules/desktop] --> fg_engine[modules/automation]
    fg_app --> fg_tasks[modules/tasks]
    fg_app --> fg_vision[modules/vision]
    fg_app --> fg_api[modules/api]

    fg_tasks --> fg_engine
    fg_tasks --> fg_api

    fg_engine --> fg_api
    fg_engine --> fg_data[modules/persistence]
    fg_engine --> fg_vision

    fg_data --> fg_api
    fg_vision --> fg_api
    fg_watcher[modules/watcher] -. standalone .-> watcher_runtime[Telegram runtime]
```

Do not put game task business logic in `modules/desktop`; do not put UI concepts in `modules/automation` or `modules/tasks`.

| Module | Maven artifact | Responsibility |
|---|---|---|
| `modules/api` | `frostguard-api` | Cross-module contracts and domain values |
| `modules/persistence` | `frostguard-persistence` | SQLite, Hibernate, entities, and repositories |
| `modules/vision` | `frostguard-vision` | OCR, OpenCV, templates, and vision assets |
| `modules/automation` | `frostguard-automation` | Emulator control, scheduling, and reusable interactions |
| `modules/tasks` | `frostguard-tasks` | Game-specific automation routines |
| `modules/desktop` | `frostguard-desktop` | JavaFX UI and desktop entry points |
| `modules/watcher` | `frostguard-watcher` | Companion watcher process |
| `packaging/desktop` | `frostguard-desktop-package` | Platform packaging inputs and output verification |

The platform-neutral update module is introduced with its implementation rather
than as an empty project-layout placeholder.

## Logical Decomposition

### API Layer

`modules/api` contains stable cross-module contracts:

- `TpDailyTaskEnum`, `ConfigurationKeyEnum`, and related enums define task and configuration identifiers.
- `AccountDescriptor`, `TaskStateData`, `DailyTaskStatusData`, `PointData`, `AreaData`, and `ImageSearchResultData` carry state between modules.
- `TemplatesEnum` maps logical template names to classpath resources declared in `modules/api/src/main/resources/config/templates.properties`.

Use this module for shared data shapes only. It should not depend on services, JavaFX, repositories, or emulator code.

### Data Layer

`modules/persistence` owns persistence:

- `DataStore` and `DataSeeder` initialize the SQLite/Hibernate runtime.
- `Profile`, `Config`, `DailyTask`, and related entities model persisted state.
- `ProfileRepository`, `ConfigRepository`, and `DailyTaskRepository` expose repository operations.

Engine services are the normal callers. UI controllers should prefer engine services over direct repository access.

### Vision Layer

`modules/vision` owns low-level image and OCR primitives:

- `OpenCvPatternLocator` loads OpenCV and performs template matching.
- `TesseractOcrProvider` integrates Tess4J/Tesseract.
- `ResilientOcrExecutor` adds retry and validation behavior around OCR extraction.
- PNG templates live under `modules/vision/src/main/resources/templates`.

Task-facing code should usually call `TemplateSearchHelper` or `BotOcrEngine` from `modules/automation`, not raw vision utilities directly.

### Engine Layer

`modules/automation` is the application core:

- `ScheduleService` starts/stops automation, loads profiles/configuration, restores schedules, and publishes bot/queue state.
- `TaskDispatcher` owns per-profile `TaskQueue` instances and starts queue threads.
- `TaskQueue` selects ready tasks, executes them, records task state, handles rescheduling, and manages preemption.
- `DelayedTask` is the base class for all Java automation tasks.
- Helper classes such as `NavigationHelper`, `MarchHelper`, `StaminaHelper`, and `TemplateSearchHelper` provide reusable game operations.
- Services such as `ProfileService`, `ConfigService`, `TaskManagementService`, `LoggingService`, and `StatisticsService` coordinate shared state.
- `EmulatorController` is the gateway for ADB/device actions, screenshots, taps, swipes, process checks, and emulator lifecycle operations.

### Task Layer

`modules/tasks` contains game-specific routines grouped by domain:

- `alliance`, `city`, `combat`, `dailies`, `economy`, `events`, `exploration`, `heroes`, `lifecycle`, `pets`.
- Each routine extends `DelayedTask` and implements `execute()`.
- `TaskRegistrations.initialize()` registers the task factory with `DelayedTaskRegistry`.

To add a built-in task, add the routine in `modules/tasks`, add or reuse a `TpDailyTaskEnum`, then register it in `TaskRegistrations`.

### UI Layer

`modules/desktop` contains the JavaFX screens and application entry points:

- `Main` initializes logging, analytics, task registrations, and launches JavaFX or headless mode.
- `LauncherLayoutController` wires major panels together.
- Panel controllers under `dev.frostguard.app.panel.*` edit configuration and call engine services.
- Scheduler UI controllers call `ScheduleService`, `TaskManagementService`, and `TaskQueue` APIs.
- Task Builder UI creates `AutomationStep` graphs and delegates generation/import/save to `TaskBuilderService`.

FXML and CSS resources live in `modules/desktop/src/main/resources/layout` and `modules/desktop/src/main/resources/styles`.

## Runtime Decomposition

At runtime, one process contains the JavaFX app or headless bootstrap, shared singleton services, and one task queue per enabled profile.

```mermaid
sequenceDiagram
    participant Main
    participant Tasks as TaskRegistrations
    participant UI as FXApp or HeadlessApp
    participant Scheduler as ScheduleService
    participant Dispatcher as TaskDispatcher
    participant Queue as TaskQueue per profile
    participant Emulator as EmulatorController
    participant DB as modules/persistence repositories

    Main->>Tasks: initialize registry
    Main->>UI: start selected frontend
    UI->>Scheduler: launchEngine()
    Scheduler->>Emulator: initialize()
    Scheduler->>DB: load global config and enabled profiles
    loop enabled profiles
        Scheduler->>Dispatcher: registerAccount(profile)
        Scheduler->>Queue: enqueue Initialize and configured tasks
        Scheduler->>Queue: enqueue enabled custom tasks
    end
    Scheduler->>Dispatcher: startAll()
    loop queue tick
        Queue->>Queue: choose runnable task
        Queue->>Emulator: screenshots, taps, swipes
        Queue->>DB: persist next execution
    end
```

Each `TaskQueue` chooses runnable tasks by priority and schedule, executes
`DelayedTask.run()`, records state, persists the next execution through
`ScheduleService`, and re-enqueues recurring tasks. Task-facing helpers wrap
emulator control, template matching, and OCR so routines do not depend directly
on low-level providers.

## Task Contract

Built-in and runtime-loaded tasks extend `DelayedTask`. Important hooks:

- `execute()` contains task-specific business logic.
- `getRequiredStartLocation()` tells `NavigationHelper` where the game should be before execution.
- `consumesStamina()`, `provideDailyMissionProgress()`, and `acceptsInjections()` adjust scheduler/helper behavior.
- `getDistinctKey()` differentiates custom tasks or multiple logical tasks with the same enum.
- `reschedule(...)`, `setRecurring(...)`, and `clearSchedule()` control future execution.

Built-in tasks are created through `DelayedTaskRegistry` and
`TaskRegistrations`. Runtime custom tasks are compiled and loaded by
`CustomTaskService`; optional settings use `CustomTaskConfigurable`. Live and
startup scheduling should go through `ScheduleService.scheduleCustomTask(...)`.

## Cross-Cutting Runtime Features

- Preemption: `GlobalMonitorService` registers `PreemptionRule` instances. `TaskQueue` attaches preemption tokens so long-running tasks can be interrupted safely.
- Injection: idle or sleeping tasks can execute `InjectionRule` work, such as alliance help or furnace upgrade checks.
- Navigation: `DelayedTask.run()` validates the game process and delegates screen correction to `NavigationHelper` before `execute()`.
- OCR and image matching: tasks use `BotOcrEngine`, `ResilientOcrExecutor`, and `TemplateSearchHelper`; those wrap `modules/vision`.
- Logging and metrics: task logs flow through `LoggingService`, profile-aware SLF4J logging, `TaskManagementService`, and `StatisticsService`.

## Build and Packaging

The root `pom.xml` controls Java 21, dependency versions, plugin versions, and module order.

```mermaid
flowchart TD
    RootPom[root pom.xml<br/>versions and reactor order] --> ApiJar[modules/api jar]
    RootPom --> DataJar[modules/persistence jar]
    RootPom --> VisionJar[modules/vision jar]
    RootPom --> EngineJar[modules/automation jar]
    RootPom --> TasksJar[modules/tasks jar]
    RootPom --> WatcherJar[modules/watcher shaded jar]
    RootPom --> AppJar[modules/desktop executable jar]

    ApiJar --> AppBundle[desktop bundle zip]
    DataJar --> AppBundle
    VisionJar --> AppBundle
    EngineJar --> AppBundle
    TasksJar --> AppBundle
    WatcherJar --> AppBundle
    AppJar --> AppBundle

    Tools[tools/adb and tools/tesseract] --> AppBundle
    Templates[modules/vision templates] --> AppBundle
    CustomTasks[examples/custom-tasks examples] --> AppBundle
```

`modules/desktop` builds the Java application artifact. It is run from source
with `./mvnw javafx:run`; its versioned JAR is not a standalone distribution.
`packaging/desktop` consumes that artifact and the watcher artifact:

- executable jar: `modules/desktop/target/frostguard-desktop-<version>.jar`
- transitional desktop zip: `packaging/desktop/target/frostguard-<version>-desktop-bundle.zip`
- packaging inputs staged under `packaging/desktop/target/input`
- ADB/Tesseract files staged from `tools/`
- custom task examples staged from root `examples/custom-tasks/`
- template PNGs staged from `modules/vision/src/main/resources/templates`

`modules/watcher` builds a separate shaded watcher jar.

## Where To Change Things

- Add a new task: `modules/api` enum/config if needed, `modules/tasks` routine, `TaskRegistrations`.
- Change scheduling behavior: `ScheduleService`, `TaskDispatcher`, or `TaskQueue`.
- Change a reusable game interaction: `modules/automation/helper`.
- Change OCR/template matching internals: `modules/vision`.
- Add or rename a template: resource under `modules/vision/src/main/resources/templates`, mapping in `templates.properties`, enum in `TemplatesEnum`.
- Change persisted config/profile/task state: `modules/persistence` entities/repositories and engine services.
- Change UI controls or panels: `modules/desktop/src/main/java/dev/frostguard/app/panel/*` plus matching FXML/CSS.
- Change runtime packaging: `packaging/desktop/pom.xml` or its platform sources.
