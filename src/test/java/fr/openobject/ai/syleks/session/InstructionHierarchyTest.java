package fr.openobject.ai.syleks.session;

import static org.junit.jupiter.api.Assertions.*;

import fr.openobject.ai.syleks.app.ConfigurationFiles;
import fr.openobject.ai.syleks.session.model.MessageStatus;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstructionHierarchyTest {
    @TempDir Path root;

    @Test
    void sendsGlobalRootAndNestedInstructionsInOrderAndPreservesProjectSnapshotAfterRestart()
            throws Exception {
        Path project = Files.createDirectories(root.resolve("project"));
        git(project);
        Path nested = Files.createDirectories(project.resolve("module/src"));
        Files.writeString(root.resolve("AGENTS.md"), "OUTSIDE_PARENT");
        write(project.resolve("AGENTS.md"), "ROOT_GUIDANCE");
        write(project.resolve("module/AGENTS.md"), "MODULE_GUIDANCE");
        write(nested.resolve("AGENTS.md"), "NESTED_GUIDANCE");
        write(project.resolve("sibling/AGENTS.md"), "SIBLING_GUIDANCE");
        var global = write(root.resolve("home/.config/syleks/AGENTS.md"), "GLOBAL_GUIDANCE");
        String id;
        try (var f = new SessionFixture(root, nested)) {
            try (var generation = f.agent.begin("qwen3:8b", "build", "inspect", false)) {
                String sent = generation.context().messages().getFirst().get("content").toString();
                assertOrdered(
                        sent,
                        "GLOBAL_GUIDANCE",
                        "ROOT_GUIDANCE",
                        "MODULE_GUIDANCE",
                        "NESTED_GUIDANCE");
                assertFalse(sent.contains("OUTSIDE_PARENT"));
                assertFalse(sent.contains("SIBLING_GUIDANCE"));
                generation.finish(MessageStatus.COMPLETE);
            }
            id = f.agent.current().id();
        }
        Files.writeString(global, "NEW_GLOBAL_GUIDANCE");
        Files.writeString(project.resolve("AGENTS.md"), "NEW_ROOT_GUIDANCE");
        Files.writeString(nested.resolve("AGENTS.md"), "NEW_NESTED_GUIDANCE");
        try (var f = new SessionFixture(root, nested)) {
            f.agent.open(id);
            try (var generation = f.agent.begin("qwen3:8b", "plan", "continue", false)) {
                String sent = generation.context().messages().getFirst().get("content").toString();
                assertOrdered(
                        sent,
                        "NEW_GLOBAL_GUIDANCE",
                        "ROOT_GUIDANCE",
                        "MODULE_GUIDANCE",
                        "NESTED_GUIDANCE");
                assertFalse(sent.contains("NEW_ROOT_GUIDANCE"));
                assertFalse(sent.contains("NEW_NESTED_GUIDANCE"));
                generation.finish(MessageStatus.COMPLETE);
            }
            f.agent.create("qwen3:8b");
            try (var generation = f.agent.begin("qwen3:8b", "build", "new session", false)) {
                assertTrue(
                        generation.context().messages().toString().contains("NEW_NESTED_GUIDANCE"));
                generation.finish(MessageStatus.COMPLETE);
            }
        }
        Path other = Files.createDirectories(root.resolve("other-project"));
        git(other);
        Files.writeString(other.resolve("AGENTS.md"), "OTHER_PROJECT_GUIDANCE");
        try (var f = new SessionFixture(root, other)) {
            String instructions = f.context.instructions("build");
            assertTrue(instructions.contains("OTHER_PROJECT_GUIDANCE"));
            assertTrue(instructions.contains("NEW_GLOBAL_GUIDANCE"));
            assertFalse(instructions.contains("ROOT_GUIDANCE"));
            assertFalse(instructions.contains("NESTED_GUIDANCE"));
            assertThrows(IllegalArgumentException.class, () -> f.agent.open(id));
        }
    }

    @Test
    void rejectsEscapingBrokenAndInvalidInstructionFilesWithoutReadingOtherProjects()
            throws Exception {
        Path project = Files.createDirectories(root.resolve("project"));
        Path outside =
                Files.writeString(root.resolve("outside.md"), "FICTIONAL_OTHER_PROJECT_DATA");
        Path file = project.resolve("AGENTS.md");
        try (var f = new SessionFixture(root)) {
            Files.createSymbolicLink(file, outside);
            assertThrows(IllegalStateException.class, () -> f.context.instructions("build"));
            Files.delete(file);
            Files.createSymbolicLink(file, project.resolve("missing.md"));
            assertThrows(IllegalStateException.class, () -> f.context.instructions("build"));
            Files.delete(file);
            Files.write(file, new byte[] {(byte) 0xc3, (byte) 0x28});
            assertThrows(IllegalStateException.class, () -> f.context.instructions("build"));
            Files.delete(file);
            Files.createDirectory(file);
            assertThrows(IllegalStateException.class, () -> f.context.instructions("build"));
        }
    }

    @Test
    void boundsIndividualAndCombinedInstructionSizesAndReleasesTheSessionOnFailure()
            throws Exception {
        Path project = Files.createDirectories(root.resolve("project"));
        git(project);
        Path nested = Files.createDirectories(project.resolve("module"));
        try (var f = new SessionFixture(root, nested)) {
            Files.writeString(
                    project.resolve("AGENTS.md"), "x".repeat(ConfigurationFiles.TEXT_LIMIT + 1));
            assertThrows(IllegalStateException.class, () -> f.context.instructions("build"));
            Files.writeString(
                    project.resolve("AGENTS.md"), "é".repeat(ConfigurationFiles.TEXT_LIMIT / 2));
            Files.writeString(
                    nested.resolve("AGENTS.md"), "x".repeat(ConfigurationFiles.TEXT_LIMIT));
            assertThrows(IllegalStateException.class, () -> f.context.instructions("build"));
            assertThrows(
                    IllegalStateException.class,
                    () -> f.agent.begin("qwen3:8b", "build", "too much", false));
            try (var lease = f.sessions.acquire(f.agent.current().id())) {
                assertNotNull(lease);
            }
        }
    }

    private void assertOrdered(String text, String... markers) {
        int previous = -1;
        for (String marker : markers) {
            int index = text.indexOf(marker);
            assertTrue(index > previous, text);
            previous = index;
        }
    }

    private Path write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text);
    }

    private void git(Path project) throws Exception {
        var process = new ProcessBuilder("git", "init", "--quiet", project.toString()).start();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
    }
}
