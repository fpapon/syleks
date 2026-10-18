package fr.openobject.ai.syleks.app;

import fr.openobject.ai.syleks.tui.TerminalApp;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

/**
 * Entry point invoked as `java -jar target/syleks-*.jar chat`.
 */
@DefaultScoped
@Command(name = "chat", description = "Open the local AI terminal conversation")
public class ChatCommand implements Runnable {
    private final TerminalApp app;

    public ChatCommand(Configuration configuration, TerminalApp app) {
        this.app = app;
    }

    @RootConfiguration("-")
    public record Configuration() {}

    @Override
    public void run() {
        try {
            app.run();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to open terminal UI", e);
        }
    }
}
