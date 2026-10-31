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
package fr.openobject.ai.syleks.mcp;

import fr.openobject.ai.syleks.json.JsonIO;
import java.nio.file.*;
import java.util.*;

/** A real newline JSON-RPC child process; never needs a model or an installed MCP server. */
public final class McpTestServer {
    public static void main(String[] args) throws Exception {
        var json = new JsonIO();
        Files.writeString(Path.of(args[0]), Long.toString(ProcessHandle.current().pid()));
        if (args.length > 1 && args[1].equals("child")) {
            Thread.sleep(60000);
            return;
        }
        boolean initialized = false;
        var input = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
        for (String line; (line = input.readLine()) != null; ) {
            var request = json.parse(line);
            String method = request.path("method").asText();
            if (method.equals("notifications/initialized")) {
                initialized = true;
                continue;
            }
            if (!request.has("id")) continue;
            Object result;
            switch (method) {
                case "initialize" ->
                        result =
                                Map.of(
                                        "protocolVersion",
                                        "2025-06-18",
                                        "capabilities",
                                        Map.of(
                                                "tools",
                                                Map.of(),
                                                "resources",
                                                Map.of(),
                                                "prompts",
                                                Map.of()),
                                        "serverInfo",
                                        Map.of("name", "fixture", "version", "1"));
                case "tools/list" -> {
                    if (!initialized)
                        throw new IllegalStateException("Missing initialized notification");
                    result =
                            Map.of(
                                    "tools",
                                    List.of(
                                            Map.of(
                                                    "name",
                                                    "echo",
                                                    "description",
                                                    "Echo fixture text",
                                                    "inputSchema",
                                                    Map.of(
                                                            "type",
                                                            "object",
                                                            "properties",
                                                            Map.of(
                                                                    "text",
                                                                    Map.of("type", "string")),
                                                            "required",
                                                            List.of("text"),
                                                            "additionalProperties",
                                                            false))));
                }
                case "tools/call" -> {
                    var text = request.path("params").path("arguments").path("text").asText();
                    Files.writeString(
                            Path.of(args[0] + ".calls"),
                            text + "\n",
                            StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND);
                    if (text.equals("wait-child")) {
                        var child =
                                new ProcessBuilder(
                                                Path.of(System.getProperty("java.home"), "bin/java")
                                                        .toString(),
                                                "-cp",
                                                System.getProperty("java.class.path"),
                                                McpTestServer.class.getName(),
                                                args[0] + ".child",
                                                "child")
                                        .start();
                        child.waitFor();
                    }
                    if (text.equals("wait")) Thread.sleep(60000);
                    if (text.equals("malformed")) {
                        System.out.println("not JSON");
                        System.out.flush();
                        continue;
                    }
                    result =
                            Map.of(
                                    "content",
                                    List.of(Map.of("type", "text", "text", text)),
                                    "isError",
                                    text.equals("error"));
                }
                case "resources/read" ->
                        result =
                                Map.of(
                                        "contents",
                                        List.of(
                                                Map.of(
                                                        "uri",
                                                        "fixture://data",
                                                        "text",
                                                        "resource text")));
                case "prompts/get" ->
                        result =
                                Map.of(
                                        "messages",
                                        List.of(
                                                Map.of(
                                                        "role",
                                                        "user",
                                                        "content",
                                                        Map.of(
                                                                "type",
                                                                "text",
                                                                "text",
                                                                "fixture prompt"))));
                default -> result = Map.of();
            }
            if (method.equals("tools/list")
                    && "external-ref".equals(System.getenv("SYLEKS_MCP_FIXTURE_MODE"))) {
                var tree = json.tree(result);
                (tree.path("tools").get(0).path("inputSchema"))
                        .put("$ref", "https://fixture.invalid/schema");
                result = tree;
            }
            System.out.println(
                    json.write(
                            Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result)));
            System.out.flush();
        }
    }
}
