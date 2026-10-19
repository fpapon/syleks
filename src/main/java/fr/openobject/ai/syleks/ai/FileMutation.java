package fr.openobject.ai.syleks.ai;

import java.nio.file.Path;

/** Called only after resource authorization and immediately before a native file effect. */
@FunctionalInterface
public interface FileMutation {
    @FunctionalInterface
    interface Effect {
        void run() throws Exception;
    }

    void commit(Path path, byte[] before, byte[] after, Effect effect) throws Exception;

    FileMutation DIRECT = (path, before, after, effect) -> effect.run();
}
