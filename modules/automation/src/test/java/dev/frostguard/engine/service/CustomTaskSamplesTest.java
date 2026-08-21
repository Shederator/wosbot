package dev.frostguard.engine.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.frostguard.api.configs.FlowStepKind;
import dev.frostguard.api.domain.AutomationBlueprint;
import dev.frostguard.api.domain.AutomationStep;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class CustomTaskSamplesTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void everyBuilderSampleLoadsCleanlyAndGeneratesJava() throws Exception {
        Path repository = findRepositoryRoot();
        Path examples = repository.resolve("examples/custom-tasks");
        List<Path> samples;
        try (var files = Files.list(examples)) {
            samples = files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
        }
        assertFalse(samples.isEmpty(), "At least one editable Task Builder sample is required");

        for (Path sample : samples) {
            AutomationBlueprint blueprint = mapper.readValue(sample.toFile(), AutomationBlueprint.class);
            assertNotNull(blueprint.getName(), sample + " must have a title");
            assertFalse(blueprint.getNodes().isEmpty(), sample + " must contain steps");

            for (AutomationStep step : blueprint.getNodes()) {
                assertFalse(step.isExecuted(), sample + " contains executed authoring state");
                assertTrue(step.getParams().keySet().stream().noneMatch(key -> key.startsWith("__")),
                        sample + " contains transient execution attributes");
                assertReferencedTemplateExists(repository, sample, step);
            }

            String className = sample.getFileName().toString().replace(".json", "");
            String source = new TaskCodeGenerator().generate(blueprint, className, blueprint.getName());
            assertTrue(source.contains("public class " + className),
                    sample + " did not generate the expected Java class");
        }
    }

    private void assertReferencedTemplateExists(
            Path repository, Path sample, AutomationStep step) {
        if (step.getType() != FlowStepKind.TEMPLATE_SEARCH) {
            return;
        }
        String reference = step.getParam("templatePath");
        if (!TemplatePathResolver.isFileReference(reference)
                || reference.startsWith(TemplatePathResolver.FILE_PREFIX)) {
            return;
        }
        Path relative = Path.of(reference.replace('\\', '/'));
        boolean besideSample = Files.isRegularFile(sample.getParent().resolve(relative).normalize());
        boolean visionAsset = Files.isRegularFile(repository
                .resolve("modules/vision/src/main/resources")
                .resolve(relative).normalize());
        assertTrue(besideSample || visionAsset,
                sample + " references a missing template: " + reference);
    }

    private Path findRepositoryRoot() throws IOException {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("examples/custom-tasks"))
                    && Files.isRegularFile(candidate.resolve("pom.xml"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IOException("Could not locate repository root from " + System.getProperty("user.dir"));
    }
}
