package fr.openobject.ai.syleks.session;

import fr.openobject.ai.syleks.session.model.MessageStatus;
import java.nio.file.Path;

/** Separate JVM probe: no process-local service or JDBC state can survive between invocations. */
public final class SessionProcess {
    public static void main(String[] args) throws Exception {
        try (var f = new SessionFixture(Path.of(args[0]))) {
            if (args[1].equals("write")) {
                var session = f.sessions.create("qwen3:8b", "Ollama");
                for (int i = 0; i < 20; i++)
                    f.conversation.add(
                            session.id(), "user", "writer message " + i, MessageStatus.COMPLETE);
                System.out.println(session.id());
            } else if (args[1].equals("create")) {
                f.agent.create("qwen3:8b");
                try (var generation =
                        f.agent.begin("qwen3:8b", "build", "remember across processes", false)) {
                    generation.append("persisted answer");
                    generation.finish(MessageStatus.COMPLETE);
                }
                System.out.println(f.agent.current().id());
            } else if (args[1].equals("crash")) {
                f.agent.open(args[2]);
                var generation = f.agent.begin("qwen3:8b", "build", "interrupted question", false);
                generation.append("survives abrupt termination");
                generation.flush();
                // Deliberately bypass all shutdown hooks and service cleanup.
                Runtime.getRuntime().halt(0);
            } else if (args[1].equals("hold")) {
                try (var lease = f.sessions.acquire(args[2])) {
                    System.out.println("LOCKED");
                    System.out.flush();
                    System.in.read();
                }
            } else {
                f.agent.resume("qwen3:8b");
                try (var generation =
                        f.agent.begin("qwen3:8b", "build", "follow up in another process", false)) {
                    if (!generation.context().messages().toString().contains("persisted answer"))
                        throw new AssertionError("Context missing prior answer");
                    generation.append("second answer");
                    generation.finish(MessageStatus.COMPLETE);
                }
                System.out.println(f.agent.current().id());
            }
        }
    }
}
