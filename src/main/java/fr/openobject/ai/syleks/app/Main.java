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
package fr.openobject.ai.syleks.app;

import fr.openobject.ai.syleks.i18n.InterfaceLanguage;
import fr.openobject.ai.syleks.i18n.Messages;
import fr.openobject.ai.syleks.session.SessionService;
import fr.openobject.ai.syleks.tui.TerminalApp;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.logging.Logger;

/** Argument routing around the generated Fusion container. Legacy `chat` remains supported. */
public final class Main {
    static {
        // Select the manager before Fusion or any application logger initializes JUL.
        String manager = "io.yupiik.logging.jul.YupiikLogManager";
        if (System.getProperty("java.util.logging.manager") == null)
            System.setProperty("java.util.logging.manager", manager);
        if (manager.equals(System.getProperty("java.util.logging.manager"))
                && System.getProperty("java.util.logging.config.file") == null
                && System.getenv("JAVA_UTIL_LOGGING_CONFIG_FILE") == null)
            System.setProperty("java.util.logging.config.file", "syleks-logging.properties");
    }

    private static final Logger LOGGER = Logger.getLogger(Main.class.getName());

    private Main() {}

    public static void main(String[] args) {
        int code = run(args, System.out, System.err);
        if (code != 0) System.exit(code);
    }

    public record Options(String action, String id) {}

    public static Options parse(String[] arguments) {
        var args = new java.util.ArrayList<>(Arrays.asList(arguments));
        if (!args.isEmpty() && args.getFirst().equals("chat")) args.removeFirst();
        if (args.isEmpty()) return new Options("default", "");
        return switch (args.getFirst()) {
            case "-c", "--continue" -> {
                require(args.size() == 1);
                yield new Options("resume", "");
            }
            case "--new" -> {
                require(args.size() == 1);
                yield new Options("new", "");
            }
            case "-s", "--session" -> {
                require(args.size() == 2);
                yield new Options("open", args.get(1));
            }
            case "--fork" -> {
                require(args.size() == 2);
                yield new Options("fork", args.get(1));
            }
            default ->
                    throw new IllegalArgumentException("Unknown arguments. Use --help for usage.");
        };
    }

    private static void require(boolean condition) {
        if (!condition)
            throw new IllegalArgumentException("Invalid arguments. Use --help for usage.");
    }

    public static int run(String[] args, PrintStream out, PrintStream error) {
        try {
            Messages.install(
                    Messages.forCatalog(
                            InterfaceLanguage.load(ConfigInitializer.home(), System.getenv())
                                    .catalog()));
            if (Arrays.asList(args).contains("--help") || Arrays.asList(args).contains("-h")) {
                out.println(Messages.text("cli.help"));
                return 0;
            }
            if (args.length > 0 && args[0].equals("secrets")) {
                require(
                        args.length == 2 && java.util.Set.of("migrate", "encrypt").contains(args[1])
                                || args.length == 3
                                        && java.util.Set.of("rotate", "revoke").contains(args[1]));
                var secrets = fr.openobject.ai.syleks.auth.ConfigSecrets.open();
                var migration = new fr.openobject.ai.syleks.auth.SecretConfiguration(secrets);
                var file = ConfigInitializer.home().resolve(".config/syleks/agent-config.json");
                if (args[1].equals("revoke")) {
                    secrets.revoke(args[2]);
                    out.println(
                            "Secret reference revoked. Restart chats; revoke the remote credential separately.");
                } else if (args[1].equals("rotate")) {
                    String value;
                    if (System.console() != null) {
                        char[] password = System.console().readPassword("Replacement secret: ");
                        if (password == null)
                            throw new java.io.IOException("Secret input cancelled");
                        try {
                            value = new String(password);
                        } finally {
                            java.util.Arrays.fill(password, '\0');
                        }
                    } else {
                        var bytes = new java.io.ByteArrayOutputStream();
                        for (int b; (b = System.in.read()) != -1 && b != '\n'; ) {
                            if (bytes.size() >= 8192)
                                throw new java.io.IOException("Secret input exceeds limit");
                            bytes.write(b);
                        }
                        value = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
                    }
                    migration.rotate(file, args[2], value);
                    out.println("Secret rotated. Restart active chats.");
                } else if (args[1].equals("encrypt"))
                    out.println(
                            "Encrypted "
                                    + migration.encrypt(file)
                                    + " values. Restart active chats.");
                else
                    out.println(
                            "Migrated "
                                    + migration.migrate(file)
                                    + " values. Restart active chats.");
            } else if (args.length > 0 && args[0].equals("trust")) {
                require(
                        args.length == 2 && java.util.Set.of("inspect", "revoke").contains(args[1])
                                || args.length == 3 && args[1].equals("grant"));
                if (args[1].equals("revoke")) {
                    ProjectTrust.revoke(
                            ConfigInitializer.home(),
                            java.nio.file.Path.of(System.getProperty("user.dir")));
                    out.println("Project trust revoked. Restart active chats.");
                    return 0;
                }
                var trust =
                        new ProjectTrust(
                                ConfigInitializer.home(),
                                java.nio.file.Path.of(System.getProperty("user.dir")));
                switch (args[1]) {
                    case "grant" -> {
                        trust.grant(args[2]);
                        out.println("Project trust recorded. Restart active chats.");
                    }
                    default -> {
                        out.println(safe(trust.state()));
                        trust.files()
                                .forEach(
                                        (path, text) ->
                                                out.println(
                                                        "\n--- "
                                                                + safe(path.toString())
                                                                + " ---\n"
                                                                + safeOutput(text)));
                        out.println(
                                "Review every definition above, then: syleks trust grant "
                                        + trust.digest());
                    }
                }
            } else if (args.length > 0 && args[0].equals("mcp")) {
                require(
                        args.length == 2 && java.util.Set.of("list", "tools").contains(args[1])
                                || args.length == 3
                                        && java.util.Set.of("auth", "logout").contains(args[1]));
                try (var container = ConfiguringContainer.of().start();
                        var instance =
                                container.lookup(fr.openobject.ai.syleks.ai.ToolRegistry.class)) {
                    transportWarnings(container, error);
                    var registry = instance.instance();
                    if (args[1].equals("auth")) {
                        registry.mcp()
                                .login(
                                        args[2],
                                        uri -> out.println(Messages.text("cli.mcp.open", uri)));
                        out.println(Messages.text("cli.mcp.authorized"));
                        return 0;
                    }
                    if (args[1].equals("logout")) {
                        registry.mcp().logout(args[2]);
                        out.println(Messages.text("cli.mcp.removed"));
                        return 0;
                    }
                    var tools = registry.forAgent(registryConfigAgent(container));
                    if (args[1].equals("list"))
                        registry.mcp()
                                .status()
                                .forEach((id, status) -> out.println(id + "\t" + status));
                    else
                        for (var tool : tools) {
                            var function = (java.util.Map<?, ?>) tool.get("function");
                            if (function.get("name").toString().startsWith("mcp_"))
                                out.println(function.get("name"));
                        }
                }
            } else if (ProviderCommands.handles(args)) {
                try (var container = ConfiguringContainer.of().start();
                        var instance = container.lookup(ProviderCommands.class)) {
                    transportWarnings(container, error);
                    instance.instance().run(args, out);
                }
            } else if (args.length > 0 && args[0].equals("config")) {
                if (args.length == 2 && args[1].equals("update")) {
                    try (var container = ConfiguringContainer.of().start();
                            var mapper = container.lookup(io.yupiik.fusion.json.JsonMapper.class)) {
                        for (var result :
                                new ConfigUpdater(mapper.instance())
                                        .update(ConfigInitializer.home())) {
                            out.println(
                                    (result.updated() ? "Updated " : "Already current ")
                                            + safe(result.path().toString()));
                            if (result.backup() != null)
                                out.println("Backup: " + safe(result.backup().toString()));
                        }
                        out.println("Restart active chats to apply updated configuration.");
                    }
                    return 0;
                }
                require(
                        (args.length == 2 || args.length == 3 && args[2].equals("--defaults"))
                                && args[1].equals("init"));
                if (args.length == 2 && System.console() != null) {
                    try (var terminal = TerminalApp.openTerminal()) {
                        TerminalApp.ConfigurationResult result;
                        try (var container = ConfiguringContainer.of().start();
                                var initializer = container.lookup(ConfigInitializer.class);
                                var wizard = container.lookup(ConfigInitWizard.class)) {
                            result =
                                    TerminalApp.configure(
                                            initializer.instance(), wizard.instance(), terminal);
                        }
                        if (result.restart()) chat(new Options("default", ""), terminal);
                    }
                } else {
                    try (var container = ConfiguringContainer.of().start();
                            var instance = container.lookup(ConfigInitializer.class)) {
                        var home = ConfigInitializer.home();
                        String configuration = null;
                        if (args.length == 2
                                && !java.nio.file.Files.exists(
                                        home.resolve(".config/syleks/agent-config.json"),
                                        java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                            try (var wizard = container.lookup(ConfigInitWizard.class)) {
                                configuration = wizard.instance().configure(out);
                            }
                        }
                        for (var result : instance.instance().initialize(home, configuration)) {
                            out.println(
                                    Messages.text(
                                            result.created() ? "cli.created" : "cli.skipped",
                                            safe(result.path().toString())));
                        }
                    }
                }
            } else if (args.length > 0 && args[0].equals("session")) {
                require(args.length >= 2);
                String operation = args[1];
                require(
                        operation.equals("list")
                                ? args.length == 2
                                : operation.equals("fork")
                                        ? args.length == 3 || args.length == 4
                                        : java.util.Set.of(
                                                                "delete",
                                                                "export",
                                                                "import",
                                                                "messages",
                                                                "changes",
                                                                "diff",
                                                                "undo",
                                                                "redo")
                                                        .contains(operation)
                                                && args.length == 3);
                try (var container = ConfiguringContainer.of().start();
                        var instance = container.lookup(SessionService.class)) {
                    var service = instance.instance();
                    switch (operation) {
                        case "list" -> {
                            out.println(Messages.text("cli.session_header"));
                            for (var s : service.list())
                                out.println(
                                        s.id()
                                                + "\t"
                                                + safe(s.title())
                                                + "\t"
                                                + s.updatedAt()
                                                + "\t"
                                                + safe(s.model())
                                                + "\t"
                                                + s.messageCount()
                                                + "\t"
                                                + s.status());
                        }
                        case "delete" -> {
                            service.delete(args[2]);
                            out.println(Messages.text("cli.deleted", args[2]));
                        }
                        case "export" -> out.println(service.export(args[2]));
                        case "import" ->
                                out.println(
                                        service.importArchive(
                                                        ConfigurationFiles.read(
                                                                java.nio.file.Path.of(args[2])
                                                                        .toAbsolutePath(),
                                                                32 * 1024 * 1024))
                                                .id());
                        case "fork" ->
                                out.println(
                                        service.fork(args[2], args.length == 4 ? args[3] : null)
                                                .id());
                        case "messages" -> out.print(service.messageList(args[2]));
                        case "changes" -> out.print(safeOutput(service.changes(args[2])));
                        case "diff" -> out.print(safeOutput(service.diff(args[2])));
                        case "undo", "redo" ->
                                out.println(
                                        service.restoreFiles(
                                                args[2],
                                                operation.equals("redo"),
                                                (op, target, details) -> true));
                        default -> throw new IllegalArgumentException("Unknown session command");
                    }
                }
            } else {
                chat(parse(args));
            }
            return 0;
        } catch (IllegalArgumentException e) {
            LOGGER.fine("Command rejected: " + e.getClass().getName());
            error.println("Syleks: " + safe(e.getMessage()));
            return 2;
        } catch (Exception e) {
            LOGGER.warning("Command failed: " + e.getClass().getName());
            error.println("Syleks: " + safe(e.getMessage()));
            return 1;
        }
    }

    private static void transportWarnings(
            io.yupiik.fusion.framework.api.RuntimeContainer container, PrintStream error) {
        try (var config = container.lookup(AppConfig.class)) {
            config.instance().transport().warnings().forEach(error::println);
        }
    }

    private static String registryConfigAgent(
            io.yupiik.fusion.framework.api.RuntimeContainer container) {
        try (var config = container.lookup(AppConfig.class)) {
            return config.instance().defaultAgent();
        }
    }

    private static void chat(Options options) throws Exception {
        try (var terminal = TerminalApp.openTerminal()) {
            chat(options, terminal);
        }
    }

    private static void chat(Options options, TerminalApp.TerminalHandle terminal)
            throws Exception {
        TerminalApp.RestartRequest restart = null;
        while (true) {
            try (var container = ConfiguringContainer.of().start();
                    var instance = container.lookup(TerminalApp.class)) {
                var app = instance.instance();
                app.initialize(options);
                if (restart != null) app.restoreAfterRestart(restart);
                app.run(terminal);
                restart = app.restartRequest();
            }
            if (restart == null) return;
            options =
                    restart.sessionId() == null
                            ? new Options("new", "")
                            : new Options("open", restart.sessionId());
        }
    }

    private static String safeOutput(String value) {
        return value.replaceAll("[\\p{Cc}&&[^\n\t]]", " ");
    }

    private static String safe(String value) {
        return value == null ? Messages.text("cli.failed") : value.replaceAll("[\\p{Cc}]", " ");
    }
}
