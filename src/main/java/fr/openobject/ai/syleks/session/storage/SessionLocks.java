package fr.openobject.ai.syleks.session.storage;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** OS locks are per session, shared across processes, and released automatically after a crash. */
@DefaultScoped
public class SessionLocks {
    private final DataPaths paths;
    public SessionLocks(DataPaths paths) { this.paths = paths; }

    public Lease acquire(String sessionId) {
        var lease = tryAcquire(sessionId);
        if (lease == null) throw new IllegalStateException("Session " + sessionId + " is in use by another Syleks generation; retry after it finishes");
        return lease;
    }

    public Lease tryAcquire(String sessionId) {
        UUID.fromString(sessionId); // No user-controlled filesystem path.
        var file = paths.directory().resolve("locks").resolve(sessionId + ".lock");
        FileChannel channel = null;
        try {
            if (java.nio.file.Files.isSymbolicLink(file)) throw new IOException("Unsafe session lock symlink");
            channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            DataPaths.protect(file, false);
            FileLock lock;
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException busy) { channel.close(); return null; }
            if (lock == null) { channel.close(); return null; }
            return new Lease(channel, lock);
        } catch (IOException e) {
            if (channel != null) try { channel.close(); } catch (IOException suppressed) { e.addSuppressed(suppressed); }
            throw new IllegalStateException("Unable to lock session " + sessionId, e);
        }
    }

    public static final class Lease implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        private Lease(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }
        @Override public void close() {
            try { lock.release(); channel.close(); }
            catch (IOException e) { throw new IllegalStateException("Unable to release session lock", e); }
        }
    }
}
