package fr.openobject.ai.syleks.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigInitializerTest {
    @TempDir Path home;

    @Test
    void generatesSchemaValidConfigurationAndLoadableDefaultAgents() throws Exception {
        var results = new ConfigInitializer().initialize(home);
        assertEquals(6, results.size());
        assertTrue(results.stream().allMatch(ConfigInitializer.Result::created));
        var directory = home.resolve(".config/syleks");
        var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(directory.resolve("agent-config.schema.json").toUri());
        assertTrue(schema.validate(new ObjectMapper().readTree(directory.resolve("agent-config.json").toFile())).isEmpty());
        try (var container = ConfiguringContainer.of().start(); var mapper = container.lookup(JsonMapper.class)) {
            var config = new AppConfig(mapper.instance(), home, Map.of(), home.resolve("project"));
            assertEquals("local/qwen3.8-27b", config.defaultModel());
            assertEquals("build", config.defaultAgent());
            assertEquals(List.of("build", "plan", "architect"), config.primaryAgents());
            assertTrue(config.agent("reviewer").mode().subagent());
            for (var name : List.of("build", "plan", "architect", "reviewer")) {
                assertFalse(config.agent(name).prompt().isBlank());
            }
        }
    }

    @Test
    void preservesCustomizationsAndFillsInMissingFilesOnRerun() throws Exception {
        var initializer = new ConfigInitializer();
        initializer.initialize(home);
        var directory = home.resolve(".config/syleks");
        Files.writeString(directory.resolve("agent-config.json"), "custom config, intentionally not parsed");
        Files.writeString(directory.resolve("agents/build.md"), "My custom instructions");
        Files.delete(directory.resolve("agents/plan.md"));
        var results = initializer.initialize(home);
        assertEquals(1, results.stream().filter(ConfigInitializer.Result::created).count());
        assertEquals("plan.md", results.stream().filter(ConfigInitializer.Result::created).findFirst().orElseThrow().path().getFileName().toString());
        assertEquals("custom config, intentionally not parsed", Files.readString(directory.resolve("agent-config.json")));
        assertEquals("My custom instructions", Files.readString(directory.resolve("agents/build.md")));
        assertTrue(initializer.initialize(home).stream().noneMatch(ConfigInitializer.Result::created));
    }

    @Test
    void createsPrivateFilesAndDirectoriesOnPosixFilesystems() throws Exception {
        var results = new ConfigInitializer().initialize(home);
        if (home.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(home.resolve(".config/syleks"))));
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(home.resolve(".config/syleks/agents"))));
            for (var result : results) assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(result.path())));
        }
    }

    @Test
    void reportsConflictingDestinationInsteadOfTreatingItAsAnExistingConfig() throws Exception {
        Files.createDirectories(home.resolve(".config/syleks/agent-config.json"));
        var error = assertThrows(java.io.IOException.class, () -> new ConfigInitializer().initialize(home));
        assertTrue(error.getMessage().contains("not a regular file"));
        assertTrue(Files.isDirectory(home.resolve(".config/syleks/agent-config.json")));
    }
}
