/*
 * Copyright (c) 2026 - present - Francois Papon - francois.papon@openobject.fr
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package fr.openobject.ai.syleks.session;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectServiceTest {
    @TempDir Path root;

    @Test
    void identifiesGitRootSymlinksAndSeparateWorktreesWithoutUsingRemoteAsIdentity()
            throws Exception {
        try (var f = new SessionFixture(root)) {
            Path repo = root.resolve("repository");
            Files.createDirectories(repo);
            git("init", repo.toString());
            git(
                    "-C",
                    repo.toString(),
                    "-c",
                    "user.name=Session test",
                    "-c",
                    "user.email=session@example.test",
                    "commit",
                    "--allow-empty",
                    "-m",
                    "Initial commit");
            git(
                    "-C",
                    repo.toString(),
                    "remote",
                    "add",
                    "origin",
                    "https://user:secret@example.test/repo.git?token=hidden");
            Path nested = repo.resolve("nested/directory");
            Files.createDirectories(nested);
            var project = ProjectService.identify(nested, f.repositories);
            assertEquals(repo.toRealPath().toString(), project.rootPath());
            assertEquals("https://example.test/repo.git", project.gitRemote());
            Path link = root.resolve("link");
            Files.createSymbolicLink(link, nested);
            assertEquals(project.id(), ProjectService.identify(link, f.repositories).id());
            Path worktree = root.resolve("worktree");
            git(
                    "-C",
                    repo.toString(),
                    "worktree",
                    "add",
                    "-b",
                    "session-test",
                    worktree.toString());
            var other = ProjectService.identify(worktree, f.repositories);
            assertNotEquals(project.id(), other.id());
            assertEquals(worktree.toRealPath().toString(), other.rootPath());
            assertEquals(project.gitRemote(), other.gitRemote());
        }
    }

    private void git(String... args) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add("git");
        command.addAll(java.util.List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        assertEquals(
                0,
                process.exitValue(),
                new String(
                        process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8));
    }
}
