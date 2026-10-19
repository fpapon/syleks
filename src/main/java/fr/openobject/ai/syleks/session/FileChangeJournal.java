package fr.openobject.ai.syleks.session;

import fr.openobject.ai.syleks.ai.*;
import fr.openobject.ai.syleks.session.storage.SqliteDatabase;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Write-ahead snapshots. Recovery observes files; it never repeats a filesystem effect. */
@io.yupiik.fusion.framework.api.scope.DefaultScoped
public class FileChangeJournal {
    public record Change(
            String id,
            String group,
            String session,
            String call,
            String path,
            byte[] before,
            byte[] after,
            String state,
            String created) {}

    private static final int LIMIT = 1_000_000;
    private static final Map<Path, ReentrantLock> MUTEXES = new ConcurrentHashMap<>();
    private final SqliteDatabase database;
    private final Path workspace;

    public FileChangeJournal(
            SqliteDatabase database, fr.openobject.ai.syleks.app.AppConfig config) {
        this(database, config.workspace());
    }

    public static FileChangeJournal at(SqliteDatabase database, Path workspace) {
        return new FileChangeJournal(database, workspace);
    }

    private FileChangeJournal(SqliteDatabase database, Path workspace) {
        this.database = database;
        this.workspace = workspace.toAbsolutePath().normalize();
    }

    public List<Change> list(String session) {
        return list(session, false, false);
    }

    public List<Change> listTree(String session) {
        return list(session, true, false);
    }

    private List<Change> list(String session, boolean children, boolean pending) {
        return database.read(
                c -> {
                    try (var s =
                            c.prepareStatement(
                                    children
                                            ? "WITH RECURSIVE tree(id) AS (SELECT ? UNION SELECT t.session_id FROM agent_tasks t JOIN tree ON t.parent_session_id=tree.id) SELECT * FROM file_changes WHERE session_id IN (SELECT id FROM tree) ORDER BY rowid"
                                            : "SELECT * FROM file_changes WHERE session_id=?"
                                                    + (pending ? " AND state LIKE 'PENDING%'" : "")
                                                    + " ORDER BY rowid")) {
                        s.setString(1, session);
                        try (var r = s.executeQuery()) {
                            var result = new ArrayList<Change>();
                            while (r.next())
                                result.add(
                                        new Change(
                                                r.getString("id"),
                                                r.getString("group_id"),
                                                r.getString("session_id"),
                                                r.getString("tool_call_id"),
                                                r.getString("path"),
                                                r.getBytes("before_content"),
                                                r.getBytes("after_content"),
                                                r.getString("state"),
                                                r.getString("created_at")));
                            return List.copyOf(result);
                        }
                    }
                });
    }

    public FileMutation recording(String session, String call) {
        String group = UUID.randomUUID().toString();
        return (path, before, after, effect) ->
                locked(
                        () -> {
                            var checkedPath = checked(path.toString());
                            if (!Arrays.equals(before, bytes(checkedPath)))
                                throw new IOException(
                                        "File changed before journaled mutation; retry");
                            if (before != null && before.length > LIMIT
                                    || after != null && after.length > LIMIT)
                                throw new IOException("File snapshot exceeds 1 MB");
                            var change =
                                    new Change(
                                            UUID.randomUUID().toString(),
                                            group,
                                            session,
                                            call,
                                            checkedPath.toString(),
                                            before,
                                            after,
                                            "PENDING",
                                            Instant.now().toString());
                            database.transaction(
                                    () -> {
                                        // A new edit branches away from any redo history.
                                        database.write(
                                                c -> {
                                                    try (var statement =
                                                            c.prepareStatement(
                                                                    "UPDATE file_changes SET state='ABANDONED' WHERE session_id=? AND state='UNDONE'")) {
                                                        statement.setString(1, session);
                                                        statement.executeUpdate();
                                                    }
                                                    return null;
                                                });
                                        insert(change);
                                        return null;
                                    });
                            effect.run();
                            state(change.id(), "APPLIED");
                            return null;
                        });
    }

    public void insert(Change change) {
        database.write(
                c -> {
                    try (var s =
                            c.prepareStatement(
                                    "INSERT INTO file_changes VALUES(?,?,?,?,?,?,?,?,?)")) {
                        Object[] args = {
                            change.id(),
                            change.group(),
                            change.session(),
                            change.call(),
                            change.path(),
                            change.before(),
                            change.after(),
                            change.state(),
                            change.created()
                        };
                        for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
                        s.executeUpdate();
                    }
                    return null;
                });
    }

    private void state(String id, String state) {
        database.write(
                c -> {
                    try (var s = c.prepareStatement("UPDATE file_changes SET state=? WHERE id=?")) {
                        s.setString(1, state);
                        s.setString(2, id);
                        s.executeUpdate();
                    }
                    return null;
                });
    }

    public void recover(String session) {
        if (list(session, false, true).isEmpty()) return;
        try {
            locked(
                    () -> {
                        for (var change : list(session, false, true)) {
                            String value;
                            try {
                                byte[] actual = bytes(checked(change.path()));
                                if (Arrays.equals(actual, change.after())) value = "APPLIED";
                                else if (Arrays.equals(actual, change.before()))
                                    value =
                                            change.state().equals("PENDING")
                                                    ? "NO_EFFECT"
                                                    : "UNDONE";
                                else value = "CONFLICT";
                            } catch (IOException e) {
                                value = "CONFLICT";
                            }
                            state(change.id(), value);
                        }
                        return null;
                    });
        } catch (Exception e) {
            throw new IllegalStateException("Unable to inspect pending file changes", e);
        }
    }

    public String restore(String session, boolean redo, AgentTools.Gate gate) throws Exception {
        return locked(
                () -> {
                    var history = listTree(session);
                    if (history.stream()
                            .anyMatch(
                                    c ->
                                            c.state().startsWith("PENDING")
                                                    || c.state().equals("CONFLICT")))
                        throw new IOException(
                                "Unresolved file change; inspect the workspace before undo/redo");
                    Change selected = null;
                    if (redo) {
                        for (var c : history)
                            if (c.state().equals("UNDONE")) {
                                selected = c;
                                break;
                            }
                    } else {
                        for (var c : history) if (c.state().equals("APPLIED")) selected = c;
                    }
                    if (selected == null)
                        throw new IllegalStateException(
                                redo ? "Nothing to redo" : "Nothing to undo");
                    String group = selected.group();
                    var changes =
                            new ArrayList<>(
                                    history.stream()
                                            .filter(
                                                    c ->
                                                            c.group().equals(group)
                                                                    && c.state()
                                                                            .equals(
                                                                                    redo
                                                                                            ? "UNDONE"
                                                                                            : "APPLIED"))
                                            .toList());
                    if (!redo) Collections.reverse(changes);
                    // Approve the entire batch and verify every expected file before touching any
                    // file.
                    for (var c : changes) {
                        var path = checked(c.path());
                        if (!gate.allow(
                                "write",
                                path.toString(),
                                (redo ? "Redo" : "Undo") + " recorded change to " + path))
                            throw new ToolPermissionDeniedException("File restoration refused");
                    }
                    for (var c : changes) {
                        if (!Arrays.equals(bytes(checked(c.path())), redo ? c.before() : c.after()))
                            throw new IOException("File diverged externally: " + c.path());
                    }
                    for (var c : changes) {
                        if (Thread.currentThread().isInterrupted())
                            throw new InterruptedException("File restoration cancelled");
                        var path = checked(c.path());
                        if (!Arrays.equals(bytes(path), redo ? c.before() : c.after()))
                            throw new IOException("File changed during restoration: " + path);
                        state(c.id(), redo ? "PENDING_REDO" : "PENDING_UNDO");
                        var target = redo ? c.after() : c.before();
                        if (target == null) Files.delete(path);
                        else if (Files.exists(path, LinkOption.NOFOLLOW_LINKS))
                            Files.write(path, target, StandardOpenOption.TRUNCATE_EXISTING);
                        else Files.write(path, target, StandardOpenOption.CREATE_NEW);
                        state(c.id(), redo ? "APPLIED" : "UNDONE");
                    }
                    return (redo ? "Restored" : "Reverted")
                            + " "
                            + changes.size()
                            + " file change(s)";
                });
    }

    public String diff(String session) {
        var output = new StringBuilder();
        for (var c : listTree(session)) {
            output.append("--- ")
                    .append(c.path())
                    .append(" (before)\n+++ ")
                    .append(c.path())
                    .append(" (after, ")
                    .append(c.state())
                    .append(")\n");
            for (var side :
                    List.of(new Object[] {"-", c.before()}, new Object[] {"+", c.after()})) {
                byte[] bytes = (byte[]) side[1];
                if (bytes == null) continue;
                String text;
                try {
                    text =
                            StandardCharsets.UTF_8
                                    .newDecoder()
                                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                                    .decode(java.nio.ByteBuffer.wrap(bytes))
                                    .toString();
                } catch (java.nio.charset.CharacterCodingException e) {
                    text = "[Binary snapshot: " + bytes.length + " bytes]";
                }
                for (var line : text.lines().toList()) {
                    output.append(side[0]).append(line).append('\n');
                    if (output.length() > 32000)
                        return output.substring(0, 32000) + "\n[Diff truncated]";
                }
            }
        }
        return output.isEmpty() ? "No recorded native file changes" : output.toString();
    }

    private Path checked(String value) throws IOException {
        var path = Path.of(value).toAbsolutePath().normalize();
        if (!path.startsWith(workspace) || path.equals(workspace))
            throw new IOException("File change is outside the workspace");
        Path current = workspace;
        if (Files.isSymbolicLink(workspace)) throw new IOException("Unsafe workspace symlink");
        for (var segment : workspace.relativize(path)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) throw new IOException("Unsafe file change symlink");
        }
        return path;
    }

    private byte[] bytes(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Expected a regular file");
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] result = input.readNBytes(LIMIT + 1);
            if (result.length > LIMIT) throw new IOException("File exceeds snapshot limit");
            return result;
        }
    }

    private interface Work<T> {
        T run() throws Exception;
    }

    private <T> T locked(Work<T> work) throws Exception {
        String hash =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(
                                                workspace
                                                        .toString()
                                                        .getBytes(
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8)));
        Path file = database.paths().directory().resolve("locks/workspace-" + hash + ".lock");
        var mutex = MUTEXES.computeIfAbsent(file, ignored -> new ReentrantLock());
        mutex.lockInterruptibly();
        try {
            if (Files.isSymbolicLink(file)) throw new IOException("Unsafe workspace lock");
            try (var channel =
                    FileChannel.open(
                            file,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS)) {
                var lease = channel.tryLock();
                if (lease == null)
                    throw new IOException("Workspace is in use by another file operation");
                try (lease) {
                    return work.run();
                }
            }
        } finally {
            mutex.unlock();
        }
    }
}
