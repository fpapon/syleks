package fr.openobject.ai.syleks.app;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.json.JsonMapper;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationUpdateTest {
    @TempDir Path home;

    @Test
    void loadsExistingValuesAndAtomicallyUpdatesWithoutDroppingOtherSettings() throws Exception {
        try (var container = ConfiguringContainer.of().start();
                var mapper = container.lookup(JsonMapper.class)) {
            Path directory = home.resolve(".config/syleks");
            Files.createDirectories(directory.resolve("agents"));
            String original =
                    """
                    {"model":"team/model-one","provider":{
                      "team":{"name":"Team","options":{"baseURL":"https://example.test/v1","apiKey":"private-token"},
                        "models":{"model-one":{"name":"First","limit":{"context":32768,"output":4096}},"other":{"name":"Keep me"}}},
                      "another":{"name":"Other provider","options":{"baseURL":"https://other.test/v1"},"models":{"other":{}}}},
                     "permission":{"bash":"deny","task":{"*":"deny","reviewer":"ask"}},
                     "max_rounds":9,"agent":{"plan":{"permission":{"read":"deny"}},"reviewer":{"prompt":"Custom review prompt"}}}
                    """;
            Files.writeString(directory.resolve("agent-config.json"), original);
            Files.writeString(directory.resolve("agents/build.md"), "Custom build prompt.");
            var wizard = new ConfigInitWizard(mapper.instance());
            var form = wizard.load(home);
            assertEquals("team", form.value(0));
            assertEquals("private-token", form.value(3));
            assertEquals("9", form.value(8));
            form.value(2, "https://new.test/v1");
            form.value(8, "25");
            new ConfigInitializer().save(home, form);
            var updated =
                    mapper.instance()
                            .fromString(
                                    Map.class,
                                    Files.readString(directory.resolve("agent-config.json")));
            assertEquals(
                    mapper.instance().fromString(Map.class, original).get("permission"),
                    updated.get("permission"));
            assertTrue(updated.toString().contains("another"));
            assertTrue(updated.toString().contains("Keep me"));
            assertTrue(updated.toString().contains("Custom review prompt"));
            assertEquals(
                    "Custom build prompt.", Files.readString(directory.resolve("agents/build.md")));
            var config = new AppConfig(mapper.instance(), home, Map.of(), home.resolve("project"));
            assertEquals(
                    "https://new.test/v1/chat/completions",
                    config.resolveModel("team/model-one").endpoint().toString());
            assertEquals(
                    PermissionPolicy.Decision.DENY,
                    config.permissions("plan").evaluate("read", "file"));
            if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                assertEquals(
                        "rw-------",
                        java.nio.file.attribute.PosixFilePermissions.toString(
                                Files.getPosixFilePermissions(
                                        directory.resolve("agent-config.json"))));
            }
            try (var files = Files.list(directory)) {
                assertFalse(files.anyMatch(p -> p.toString().endsWith(".tmp")));
            }
        }
    }

    @Test
    void rejectsConcurrentChangesAndKeepsTheNewerFile() throws Exception {
        try (var container = ConfiguringContainer.of().start();
                var mapper = container.lookup(JsonMapper.class)) {
            var wizard = new ConfigInitWizard(mapper.instance());
            var one = wizard.load(home);
            var two = wizard.load(home);
            var initializer = new ConfigInitializer();
            initializer.save(home, one);
            Path file = home.resolve(".config/syleks/agent-config.json");
            String content = Files.readString(file);
            two.value(0, "another");
            assertTrue(
                    assertThrows(java.io.IOException.class, () -> initializer.save(home, two))
                            .getMessage()
                            .contains("changed on disk"));
            assertEquals(content, Files.readString(file));
        }
    }

    @Test
    void canRepairInvalidJsonOnlyAfterExplicitSaveAndCanClearAnExistingApiKey() throws Exception {
        try (var container = ConfiguringContainer.of().start();
                var mapper = container.lookup(JsonMapper.class)) {
            Path file = home.resolve(".config/syleks/agent-config.json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "invalid original");
            var wizard = new ConfigInitWizard(mapper.instance());
            var form = wizard.load(home);
            assertTrue(form.warning().contains("Invalid existing JSON"));
            assertEquals("invalid original", Files.readString(file));
            form.value(3, "secret");
            new ConfigInitializer().save(home, form);
            var update = wizard.load(home);
            assertEquals("secret", update.value(3));
            update.value(3, "");
            new ConfigInitializer().save(home, update);
            assertEquals(
                    "",
                    new AppConfig(mapper.instance(), home, Map.of(), home.resolve("project"))
                            .resolveModel("local/qwen3.8-27b")
                            .apiKey());
        }
    }
}
