package dev.frostguard.engine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.frostguard.api.configs.FlowStepKind;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AutomationBlueprint;
import dev.frostguard.api.domain.AutomationStep;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.engine.nav.ShopTab;
import dev.frostguard.engine.nav.SidebarDestination;
import dev.frostguard.engine.nav.SidebarSection;
import dev.frostguard.engine.helper.NavigationHelper.AllianceMenu;
import dev.frostguard.engine.helper.NavigationHelper.EventMenu;

class TaskBuilderServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void executesShopNavigationWithTheSelectedProfileAndTab() {
        String originalWorkspace = System.getProperty(WorkspacePaths.WORKSPACE_PROPERTY);
        System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, tempDir.toString());
        try {
            AtomicReference<String> emulator = new AtomicReference<>();
            AtomicReference<AccountDescriptor> selectedProfile = new AtomicReference<>();
            AtomicReference<ShopTab> selectedTab = new AtomicReference<>();
            TaskBuilderService service = new TaskBuilderService(new ObjectMapper(),
                    (device, profile, target) -> {
                        emulator.set(device);
                        selectedProfile.set(profile);
                        selectedTab.set(target);
                        return true;
                    });
            AccountDescriptor profile = new AccountDescriptor(
                    42L, "Test Profile", "3", true, 1L, 30L);
            service.startSession("Shop probe", profile);
            AutomationStep step = new AutomationStep(1, FlowStepKind.SHOP_NAVIGATION);
            step.setParam(AutomationStep.PARAM_SHOP_TAB, ShopTab.CANYON_SHOP.name());

            assertTrue(service.executeNode(step));

            assertEquals("3", emulator.get());
            assertEquals(profile, selectedProfile.get());
            assertEquals(ShopTab.CANYON_SHOP, selectedTab.get());
            assertTrue(step.isExecuted());
        } finally {
            restoreWorkspace(originalWorkspace);
        }
    }

    @Test
    void refusesShopNavigationWithoutProfileContext() {
        String originalWorkspace = System.getProperty(WorkspacePaths.WORKSPACE_PROPERTY);
        System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, tempDir.toString());
        try {
            AtomicBoolean called = new AtomicBoolean();
            TaskBuilderService service = new TaskBuilderService(new ObjectMapper(),
                    (device, profile, target) -> {
                        called.set(true);
                        return true;
                    });
            service.startSession("Shop probe", "0");
            AutomationStep step = new AutomationStep(1, FlowStepKind.SHOP_NAVIGATION);
            step.setParam(AutomationStep.PARAM_SHOP_TAB, ShopTab.VIP_SHOP.name());

            assertFalse(service.executeNode(step));

            assertFalse(called.get());
            assertFalse(step.isExecuted());
        } finally {
            restoreWorkspace(originalWorkspace);
        }
    }

    @Test
    void executesBothSidebarModesThroughTheSelectedProfile() {
        String originalWorkspace = System.getProperty(WorkspacePaths.WORKSPACE_PROPERTY);
        System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, tempDir.toString());
        try {
            AtomicReference<String> observed = new AtomicReference<>();
            TaskBuilderService.SidebarNavigationAction sidebar = new TaskBuilderService.SidebarNavigationAction() {
                @Override
                public boolean openSection(String device, AccountDescriptor profile, SidebarSection section) {
                    observed.set(device + ":" + profile.getName() + ":" + section);
                    return true;
                }

                @Override
                public boolean navigateTo(String device, AccountDescriptor profile,
                                          SidebarDestination destination) {
                    observed.set(device + ":" + profile.getName() + ":" + destination);
                    return true;
                }
            };
            TaskBuilderService service = new TaskBuilderService(new ObjectMapper(),
                    (device, profile, tab) -> true, sidebar);
            AccountDescriptor profile = new AccountDescriptor(
                    42L, "Test Profile", "3", true, 1L, 30L);
            service.startSession("Sidebar probe", profile);

            AutomationStep section = new AutomationStep(1, FlowStepKind.SIDEBAR_NAVIGATION);
            section.setParam(AutomationStep.PARAM_SIDEBAR_MODE, "SECTION");
            section.setParam(AutomationStep.PARAM_SIDEBAR_TARGET, "WILDERNESS");
            assertTrue(service.executeNode(section));
            assertEquals("3:Test Profile:WILDERNESS", observed.get());

            AutomationStep destination = new AutomationStep(2, FlowStepKind.SIDEBAR_NAVIGATION);
            destination.setParam(AutomationStep.PARAM_SIDEBAR_MODE, "DESTINATION");
            destination.setParam(AutomationStep.PARAM_SIDEBAR_TARGET, "LIGHTHOUSE_INTEL");
            assertTrue(service.executeNode(destination));
            assertEquals("3:Test Profile:LIGHTHOUSE_INTEL", observed.get());
        } finally {
            restoreWorkspace(originalWorkspace);
        }
    }

    @Test
    void refusesInvalidSidebarTargetsAndMissingProfileWithoutCallingNavigator() {
        String originalWorkspace = System.getProperty(WorkspacePaths.WORKSPACE_PROPERTY);
        System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, tempDir.toString());
        try {
            AtomicBoolean called = new AtomicBoolean();
            TaskBuilderService.SidebarNavigationAction sidebar = new TaskBuilderService.SidebarNavigationAction() {
                @Override
                public boolean openSection(String device, AccountDescriptor profile, SidebarSection section) {
                    called.set(true);
                    return true;
                }

                @Override
                public boolean navigateTo(String device, AccountDescriptor profile,
                                          SidebarDestination destination) {
                    called.set(true);
                    return true;
                }
            };
            TaskBuilderService service = new TaskBuilderService(new ObjectMapper(),
                    (device, profile, tab) -> true, sidebar);
            service.startSession("Sidebar probe", "3");
            AutomationStep missingProfile = new AutomationStep(1, FlowStepKind.SIDEBAR_NAVIGATION);
            missingProfile.setParam(AutomationStep.PARAM_SIDEBAR_MODE, "SECTION");
            missingProfile.setParam(AutomationStep.PARAM_SIDEBAR_TARGET, "CITY");
            assertFalse(service.executeNode(missingProfile));

            AutomationStep invalid = new AutomationStep(2, FlowStepKind.SIDEBAR_NAVIGATION);
            invalid.setParam(AutomationStep.PARAM_SIDEBAR_MODE, "DESTINATION");
            invalid.setParam(AutomationStep.PARAM_SIDEBAR_TARGET, "UNKNOWN_ROW");
            assertFalse(service.executeNode(invalid));
            assertFalse(called.get());
            assertFalse(missingProfile.isExecuted());
            assertFalse(invalid.isExecuted());
        } finally {
            restoreWorkspace(originalWorkspace);
        }
    }

    @Test
    void stopsSidebarNodeWhenSharedNavigatorReportsFailure() {
        String originalWorkspace = System.getProperty(WorkspacePaths.WORKSPACE_PROPERTY);
        System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, tempDir.toString());
        try {
            TaskBuilderService.SidebarNavigationAction sidebar = new TaskBuilderService.SidebarNavigationAction() {
                @Override
                public boolean openSection(String device, AccountDescriptor profile, SidebarSection section) {
                    return false;
                }

                @Override
                public boolean navigateTo(String device, AccountDescriptor profile,
                                          SidebarDestination destination) {
                    return false;
                }
            };
            TaskBuilderService service = new TaskBuilderService(new ObjectMapper(),
                    (device, profile, tab) -> true, sidebar);
            service.startSession("Sidebar probe", new AccountDescriptor(
                    42L, "Test Profile", "3", true, 1L, 30L));
            AutomationStep step = new AutomationStep(1, FlowStepKind.SIDEBAR_NAVIGATION);
            step.setParam(AutomationStep.PARAM_SIDEBAR_MODE, "SECTION");
            step.setParam(AutomationStep.PARAM_SIDEBAR_TARGET, "DAILY");

            assertFalse(service.executeNode(step));
            assertFalse(step.isExecuted());
        } finally {
            restoreWorkspace(originalWorkspace);
        }
    }

    @Test
    void executesAllianceAndEventNavigationWithTheSelectedProfile() {
        String originalWorkspace = System.getProperty(WorkspacePaths.WORKSPACE_PROPERTY);
        System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, tempDir.toString());
        try {
            AtomicReference<String> observed = new AtomicReference<>();
            TaskBuilderService service = new TaskBuilderService(new ObjectMapper(),
                    (device, profile, tab) -> true, unusedSidebarNavigation(),
                    (device, profile, target) -> {
                        observed.set(device + ":" + profile.getName() + ":" + target);
                        return true;
                    },
                    (device, profile, target) -> {
                        observed.set(device + ":" + profile.getName() + ":" + target);
                        return true;
                    });
            service.startSession("Menu probe", new AccountDescriptor(
                    42L, "Test Profile", "3", true, 1L, 30L));

            AutomationStep alliance = new AutomationStep(1, FlowStepKind.ALLIANCE_NAVIGATION);
            alliance.setParam(AutomationStep.PARAM_ALLIANCE_MENU, AllianceMenu.TERRITORY.name());
            assertTrue(service.executeNode(alliance));
            assertEquals("3:Test Profile:TERRITORY", observed.get());

            AutomationStep event = new AutomationStep(2, FlowStepKind.EVENT_NAVIGATION);
            event.setParam(AutomationStep.PARAM_EVENT_MENU, EventMenu.ALLIANCE_CHAMPIONSHIP.name());
            assertTrue(service.executeNode(event));
            assertEquals("3:Test Profile:ALLIANCE_CHAMPIONSHIP", observed.get());
        } finally {
            restoreWorkspace(originalWorkspace);
        }
    }

    @Test
    void refusesInvalidMenuTargetsAndMissingProfileWithoutNavigating() {
        String originalWorkspace = System.getProperty(WorkspacePaths.WORKSPACE_PROPERTY);
        System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, tempDir.toString());
        try {
            AtomicBoolean called = new AtomicBoolean();
            TaskBuilderService service = new TaskBuilderService(new ObjectMapper(),
                    (device, profile, tab) -> true, unusedSidebarNavigation(),
                    (device, profile, target) -> {
                        called.set(true);
                        return true;
                    },
                    (device, profile, target) -> {
                        called.set(true);
                        return true;
                    });
            service.startSession("Menu probe", "3");

            AutomationStep alliance = new AutomationStep(1, FlowStepKind.ALLIANCE_NAVIGATION);
            alliance.setParam(AutomationStep.PARAM_ALLIANCE_MENU, "UNKNOWN");
            assertFalse(service.executeNode(alliance));

            AutomationStep event = new AutomationStep(2, FlowStepKind.EVENT_NAVIGATION);
            event.setParam(AutomationStep.PARAM_EVENT_MENU, EventMenu.MERCENARY.name());
            assertFalse(service.executeNode(event));

            assertFalse(called.get());
            assertFalse(alliance.isExecuted());
            assertFalse(event.isExecuted());
        } finally {
            restoreWorkspace(originalWorkspace);
        }
    }

    @Test
    void stopsMenuNodesWhenSharedNavigationReportsFailure() {
        String originalWorkspace = System.getProperty(WorkspacePaths.WORKSPACE_PROPERTY);
        System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, tempDir.toString());
        try {
            TaskBuilderService service = new TaskBuilderService(new ObjectMapper(),
                    (device, profile, tab) -> true, unusedSidebarNavigation(),
                    (device, profile, target) -> false,
                    (device, profile, target) -> false);
            service.startSession("Menu probe", new AccountDescriptor(
                    42L, "Test Profile", "3", true, 1L, 30L));
            AutomationStep alliance = new AutomationStep(1, FlowStepKind.ALLIANCE_NAVIGATION);
            alliance.setParam(AutomationStep.PARAM_ALLIANCE_MENU, AllianceMenu.WAR.name());
            AutomationStep event = new AutomationStep(2, FlowStepKind.EVENT_NAVIGATION);
            event.setParam(AutomationStep.PARAM_EVENT_MENU, EventMenu.HERO_MISSION.name());

            assertFalse(service.executeNode(alliance));
            assertFalse(service.executeNode(event));
            assertFalse(alliance.isExecuted());
            assertFalse(event.isExecuted());
        } finally {
            restoreWorkspace(originalWorkspace);
        }
    }

    @Test
    void savesAndImportsAllianceAndEventSelections() throws Exception {
        String originalWorkspace = System.getProperty(WorkspacePaths.WORKSPACE_PROPERTY);
        System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, tempDir.toString());
        try {
            TaskBuilderService service = new TaskBuilderService();
            service.startSession("Menu probe", "3");
            AutomationStep alliance = new AutomationStep(1, FlowStepKind.ALLIANCE_NAVIGATION);
            alliance.setParam(AutomationStep.PARAM_ALLIANCE_MENU, AllianceMenu.TERRITORY.name());
            service.addNode(alliance);
            AutomationStep event = new AutomationStep(2, FlowStepKind.EVENT_NAVIGATION);
            event.setParam(AutomationStep.PARAM_EVENT_MENU, EventMenu.ALLIANCE_CHAMPIONSHIP.name());
            service.addNode(event);

            TaskBuilderService.CustomTaskSaveResult saved =
                    service.saveCurrentTaskToCustomTasks("Menu probe", tempDir.resolve("menu.json"));
            AutomationBlueprint imported = service.loadDefinition(saved.builderFile().toFile(), "3");

            assertEquals(FlowStepKind.ALLIANCE_NAVIGATION, imported.getNodes().get(0).getType());
            assertEquals(AllianceMenu.TERRITORY.name(),
                    imported.getNodes().get(0).getParam(AutomationStep.PARAM_ALLIANCE_MENU));
            assertEquals(FlowStepKind.EVENT_NAVIGATION, imported.getNodes().get(1).getType());
            assertEquals(EventMenu.ALLIANCE_CHAMPIONSHIP.name(),
                    imported.getNodes().get(1).getParam(AutomationStep.PARAM_EVENT_MENU));
            String javaSource = Files.readString(saved.javaFile());
            assertTrue(javaSource.contains("navigateToAllianceMenu(AllianceMenu.TERRITORY)"));
            assertTrue(javaSource.contains("navigateToEventMenu(EventMenu.ALLIANCE_CHAMPIONSHIP)"));
        } finally {
            restoreWorkspace(originalWorkspace);
        }
    }

    @Test
    void savesBuilderJsonAndGeneratedJavaBesideIt() throws Exception {
        String originalWorkspace = System.getProperty(WorkspacePaths.WORKSPACE_PROPERTY);
        System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, tempDir.toString());
        try {
            TaskBuilderService service = new TaskBuilderService();
            service.startSession("Expert Idle Exploration", "0");

            AutomationStep step = new AutomationStep(1, FlowStepKind.WAIT);
            step.setNodeName("Pause before bag");
            step.setParam("durationMs", "200");
            service.addNode(step);

            Path builderFile = tempDir.resolve("custom-tasks").resolve("expert_idle_exploration.json");
            TaskBuilderService.CustomTaskSaveResult saved =
                    service.saveCurrentTaskToCustomTasks("Expert Idle Exploration", builderFile);

            assertEquals("expert_idle_exploration", saved.className());
            assertTrue(Files.exists(saved.builderFile()));
            assertTrue(Files.exists(saved.javaFile()));

            String javaSource = Files.readString(saved.javaFile());
            assertTrue(javaSource.contains("// Pause before bag"));

            AutomationBlueprint loaded = service.loadDefinition(saved.builderFile().toFile(), "1");
            assertEquals("Expert Idle Exploration", loaded.getName());
            assertEquals("Pause before bag", loaded.getNodes().get(0).getNodeName());
            assertEquals("1", service.getActiveEmulatorNumber());
        } finally {
            restoreWorkspace(originalWorkspace);
        }
    }

    /**
     * Saving a flow that contained a template-search node used to abort
     * part-way through writing and leave an unparseable file on disk, so the
     * flow could never be reopened. The whole save/reload cycle is exercised
     * here through the service, exactly as the editor drives it.
     */
    @Test
    void savesAndReloadsAFlowContainingATemplateSearchNode() throws Exception {
        String originalWorkspace = System.getProperty(WorkspacePaths.WORKSPACE_PROPERTY);
        System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, tempDir.toString());
        try {
            TaskBuilderService service = new TaskBuilderService();
            service.startSession("Dead Shot", "0");

            AutomationStep find = new AutomationStep(1, FlowStepKind.TEMPLATE_SEARCH);
            find.setNodeName("search deal");
            find.setParam("templatePath", "HOME_DEALS_BUTTON");
            find.setParam("threshold", "90");
            service.addNode(find);

            AutomationStep wait = new AutomationStep(2, FlowStepKind.WAIT);
            wait.setNodeName("wait for panel");
            wait.setParam("durationMs", "1500");
            service.addNode(wait);

            Path builderFile = tempDir.resolve("custom-tasks").resolve("dead_shot.json");
            TaskBuilderService.CustomTaskSaveResult saved =
                    service.saveCurrentTaskToCustomTasks("Dead Shot", builderFile);

            String builderJson = Files.readString(saved.builderFile());
            JsonNode parsed = new ObjectMapper().readTree(builderJson);
            assertEquals("Dead Shot", parsed.path("title").asText());
            assertEquals(2, parsed.path("steps").size());
            assertEquals("search deal",
                    parsed.path("steps").path(0).path("attributes").path("nodeName").asText());

            // Legacy duplicate spellings must not reappear in the saved file.
            for (String legacyKey : new String[] {"\"id\"", "\"type\"", "\"params\"",
                    "\"canvasX\"", "\"nextNodeId\"", "\"summary\"", "\"nodes\""}) {
                assertFalse(builderJson.contains(legacyKey),
                        "legacy key leaked into the saved file: " + legacyKey);
            }

            AutomationBlueprint reloaded = service.loadDefinition(saved.builderFile().toFile(), "0");
            assertEquals(2, reloaded.getNodes().size());
            assertEquals(FlowStepKind.TEMPLATE_SEARCH, reloaded.getNodes().get(0).getKind());
            assertEquals("search deal", reloaded.getNodes().get(0).getNodeName());
            assertEquals("HOME_DEALS_BUTTON", reloaded.getNodes().get(0).getParam("templatePath"));
            assertEquals("wait for panel", reloaded.getNodes().get(1).getNodeName());
        } finally {
            restoreWorkspace(originalWorkspace);
        }
    }

    @Test
    void stagesPickedTemplatesWithPortableCollisionSafeNames() throws Exception {
        String originalWorkspace = System.getProperty(WorkspacePaths.WORKSPACE_PROPERTY);
        System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, tempDir.toString());
        try {
            TaskBuilderService service = new TaskBuilderService();
            Path firstSource = tempDir.resolve("first").resolve("event tab.png");
            Path secondSource = tempDir.resolve("second").resolve("event tab.png");
            Files.createDirectories(firstSource.getParent());
            Files.createDirectories(secondSource.getParent());
            Files.writeString(firstSource, "first");
            Files.writeString(secondSource, "second");

            String first = service.stageCustomTemplate(firstSource);
            String same = service.stageCustomTemplate(firstSource);
            String second = service.stageCustomTemplate(secondSource);

            assertEquals("templates/event_tab.png", first);
            assertEquals(first, same);
            assertEquals("templates/event_tab-2.png", second);
            assertEquals("first", Files.readString(tempDir.resolve("custom-tasks").resolve(first)));
            assertEquals("second", Files.readString(tempDir.resolve("custom-tasks").resolve(second)));
        } finally {
            restoreWorkspace(originalWorkspace);
        }
    }

    @Test
    void saveAsCopiesRelativeTemplatesBesideTheBuilderDefinition() throws Exception {
        String originalWorkspace = System.getProperty(WorkspacePaths.WORKSPACE_PROPERTY);
        System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, tempDir.resolve("workspace").toString());
        try {
            Path importedDirectory = tempDir.resolve("imported");
            Path sourceTemplate = importedDirectory.resolve("templates").resolve("event_tab.png");
            Files.createDirectories(sourceTemplate.getParent());
            Files.writeString(sourceTemplate, "png");
            Path sourceJson = importedDirectory.resolve("flow.json");
            Files.writeString(sourceJson, """
                    {
                      "title": "Portable flow",
                      "initialScreen": "ANY",
                      "steps": [{
                        "stepId": 1,
                        "kind": "TEMPLATE_SEARCH",
                        "attributes": {"templatePath": "templates/event_tab.png"},
                        "successorId": -1,
                        "alternateId": -1
                      }]
                    }
                    """);

            TaskBuilderService service = new TaskBuilderService();
            service.loadDefinition(sourceJson.toFile(), "0");
            Path savedJson = tempDir.resolve("shared-copy").resolve("flow.json");
            service.saveCurrentTaskToCustomTasks("Portable flow", savedJson);

            assertEquals("png", Files.readString(
                    savedJson.getParent().resolve("templates").resolve("event_tab.png")));
        } finally {
            restoreWorkspace(originalWorkspace);
        }
    }

    @Test
    void failedSerializationCannotReplaceAnExistingBuilderFile() throws Exception {
        String originalWorkspace = System.getProperty(WorkspacePaths.WORKSPACE_PROPERTY);
        System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, tempDir.toString());
        try {
            ObjectMapper failingMapper = new ObjectMapper() {
                @Override
                public void writeValue(File resultFile, Object value) throws IOException {
                    Files.writeString(resultFile.toPath(), "partial");
                    throw new IOException("simulated serialization failure");
                }
            };
            TaskBuilderService service = new TaskBuilderService(failingMapper);
            service.startSession("Safe save", "0");
            service.addNode(new AutomationStep(1, FlowStepKind.WAIT));

            Path builderFile = tempDir.resolve("custom-tasks").resolve("safe.json");
            Path javaFile = builderFile.resolveSibling("safe.java");
            Files.createDirectories(builderFile.getParent());
            Files.writeString(builderFile, "known-good-json");
            Files.writeString(javaFile, "known-good-java");

            assertThrows(IOException.class,
                    () -> service.saveCurrentTaskToCustomTasks("Safe save", builderFile));

            assertEquals("known-good-json", Files.readString(builderFile));
            assertEquals("known-good-java", Files.readString(javaFile));
            try (var files = Files.list(builderFile.getParent())) {
                assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
            }
        } finally {
            restoreWorkspace(originalWorkspace);
        }
    }

    private void restoreWorkspace(String originalWorkspace) {
        if (originalWorkspace == null) {
            System.clearProperty(WorkspacePaths.WORKSPACE_PROPERTY);
        } else {
            System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, originalWorkspace);
        }
    }

    private static TaskBuilderService.SidebarNavigationAction unusedSidebarNavigation() {
        return new TaskBuilderService.SidebarNavigationAction() {
            @Override
            public boolean openSection(String device, AccountDescriptor profile, SidebarSection section) {
                return true;
            }

            @Override
            public boolean navigateTo(String device, AccountDescriptor profile, SidebarDestination destination) {
                return true;
            }
        };
    }
}
