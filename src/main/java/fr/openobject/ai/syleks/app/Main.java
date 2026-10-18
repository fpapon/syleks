package fr.openobject.ai.syleks.app;

import fr.openobject.ai.syleks.session.SessionService;
import fr.openobject.ai.syleks.tui.TerminalApp;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import java.io.PrintStream;
import java.util.Arrays;

/** Argument routing around the generated Fusion container. Legacy `chat` remains supported. */
public final class Main {
    private Main() { }
    public static void main(String[] args) {
        int code = run(args, System.out, System.err);
        if (code != 0) System.exit(code);
    }
    public record Options(String action, String id) { }
    public static Options parse(String[] arguments) {
        var args = new java.util.ArrayList<>(Arrays.asList(arguments));
        if (!args.isEmpty() && args.getFirst().equals("chat")) args.removeFirst();
        if (args.isEmpty()) return new Options("default", "");
        return switch (args.getFirst()) {
            case "-c", "--continue" -> { require(args.size() == 1); yield new Options("resume", ""); }
            case "--new" -> { require(args.size() == 1); yield new Options("new", ""); }
            case "-s", "--session" -> { require(args.size() == 2); yield new Options("open", args.get(1)); }
            case "--fork" -> { require(args.size() == 2); yield new Options("fork", args.get(1)); }
            default -> throw new IllegalArgumentException("Unknown arguments. Use --help for usage.");
        };
    }
    private static void require(boolean condition) { if (!condition) throw new IllegalArgumentException("Invalid arguments. Use --help for usage."); }
    public static int run(String[] args, PrintStream out, PrintStream error) {
        try {
            if (Arrays.asList(args).contains("--help") || Arrays.asList(args).contains("-h")) {
                out.println("""
                        Syleks — persistent local AI sessions
                        Usage: syleks [chat] [-c|--continue | -s|--session <id> | --new | --fork <id>]
                               syleks session list
                               syleks session delete <id>
                               syleks session export <id>
                        No option: open the TUI and offer this project's sessions.
                        -c: resume the latest session; create one if this project has none.
                        Exports are JSON on stdout and may contain confidential content. Redirect explicitly.
                        Session IDs are restricted to the current project. Use --help for this message.
                        """);
                return 0;
            }
            if (args.length > 0 && args[0].equals("session")) {
                require(args.length >= 2);
                String operation = args[1];
                require(operation.equals("list") ? args.length == 2 : (operation.equals("delete") || operation.equals("export")) && args.length == 3);
                try (var container = ConfiguringContainer.of().start(); var instance = container.lookup(SessionService.class)) {
                    var service = instance.instance();
                    switch (operation) {
                        case "list" -> {
                            out.println("ID\tTITLE\tUPDATED_AT\tMODEL\tMESSAGES\tSTATUS");
                            for (var s : service.list()) out.println(s.id() + "\t" + safe(s.title()) + "\t" + s.updatedAt() + "\t" + safe(s.model()) + "\t" + s.messageCount() + "\t" + s.status());
                        }
                        case "delete" -> { service.delete(args[2]); out.println("Deleted session " + args[2]); }
                        case "export" -> out.println(service.export(args[2]));
                        default -> throw new IllegalArgumentException("Unknown session command");
                    }
                }
            } else {
                var options = parse(args);
                try (var container = ConfiguringContainer.of().start(); var instance = container.lookup(TerminalApp.class)) {
                    var app = instance.instance(); app.initialize(options); app.run();
                }
            }
            return 0;
        } catch (IllegalArgumentException e) { error.println("Syleks: " + safe(e.getMessage())); return 2; }
        catch (Exception e) { error.println("Syleks: " + safe(e.getMessage())); return 1; }
    }
    private static String safe(String value) { return value == null ? "Operation failed" : value.replaceAll("[\\p{Cc}]", " "); }
}
