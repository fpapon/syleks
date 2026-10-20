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
package fr.openobject.ai.syleks.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BrowserLauncherTest {
    @TempDir Path root;

    @Test
    void selectsPlatformOpenersWithTheEntireAuthorizationUriAsOneArgument() {
        var uri =
                URI.create(
                        "https://example.invalid/authorize?state=a&redirect_uri=http%3A%2F%2Flocalhost");
        assertEquals(
                List.of("xdg-open", uri.toASCIIString()), BrowserLauncher.command(uri, "Linux"));
        assertEquals(
                List.of("/usr/bin/open", uri.toASCIIString()),
                BrowserLauncher.command(uri, "Mac OS X"));
        assertEquals(
                List.of("rundll32", "url.dll,FileProtocolHandler", uri.toASCIIString()),
                BrowserLauncher.command(uri, "Windows 11"));
    }

    @Test
    void passesTheUrlLiterallyWithoutExecutingShellMetacharacters() throws Exception {
        var output = root.resolve("received");
        var script = root.resolve("opener");
        Files.writeString(script, "#!/bin/sh\nprintf '%s' \"$1\" > \"$2\"\n");
        String uri = "https://example.invalid/?state=a&code=$(false);value=two words";
        assertTrue(
                BrowserLauncher.launch(
                        List.of("/bin/sh", script.toString(), uri, output.toString())));
        assertEquals(uri, Files.readString(output));
    }

    @Test
    void unavailableOrFailingOpenersAllowManualAuthorization() {
        assertFalse(BrowserLauncher.launch(List.of(root.resolve("missing-opener").toString())));
        assertFalse(BrowserLauncher.launch(List.of("/bin/sh", "-c", "exit 1")));
    }

    @Test
    void interruptedLaunchPreservesCancellation() {
        Thread.currentThread().interrupt();
        try {
            assertFalse(BrowserLauncher.launch(List.of("/bin/sh", "-c", "read value")));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
