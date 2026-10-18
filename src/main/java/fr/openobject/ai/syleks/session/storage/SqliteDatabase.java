package fr.openobject.ai.syleks.session.storage;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Short-lived connections; reentrant unit of work shares a connection within one transaction. */
@DefaultScoped
public class SqliteDatabase {
    private static final ConcurrentHashMap<String, ReentrantLock> WRITERS =
            new ConcurrentHashMap<>();
    private static final ThreadLocal<java.util.Map<String, Connection>> TRANSACTION =
            ThreadLocal.withInitial(java.util.HashMap::new);
    private final String url;
    private final ReentrantLock writer;
    private final DataPaths paths;

    public SqliteDatabase(DataPaths paths) {
        this.paths = paths;
        url = "jdbc:sqlite:" + paths.database();
        writer = WRITERS.computeIfAbsent(url, key -> new ReentrantLock());
        try {
            if (Files.isSymbolicLink(paths.database()))
                throw new IllegalStateException("Unsafe SQLite database symlink");
            if (!Files.exists(paths.database())) {
                try {
                    Files.createFile(paths.database());
                } catch (java.nio.file.FileAlreadyExistsException ignored) {
                }
            }
            DataPaths.protect(paths.database(), false);
            try (var connection = connect();
                    var statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
            }
            transaction(
                    () -> {
                        try (var statement = current().createStatement();
                                var version = statement.executeQuery("PRAGMA user_version")) {
                            int currentVersion = version.next() ? version.getInt(1) : 0;
                            if (currentVersion > 2)
                                throw new IllegalStateException(
                                        "Database schema "
                                                + currentVersion
                                                + " is newer than supported schema 2; upgrade Syleks");
                            if (currentVersion == 0) {
                                try (var resource =
                                        SqliteDatabase.class.getResourceAsStream(
                                                "/db/migrations/V001__sessions.sql")) {
                                    if (resource == null)
                                        throw new IllegalStateException(
                                                "Missing migration V001__sessions.sql");
                                    String sql =
                                            new String(
                                                    resource.readAllBytes(),
                                                    StandardCharsets.UTF_8);
                                    for (String command : sql.split(";"))
                                        if (!command.isBlank()) statement.execute(command);
                                    statement.execute("PRAGMA user_version=1");
                                }
                            }
                            if (currentVersion < 2) {
                                try (var resource =
                                        SqliteDatabase.class.getResourceAsStream(
                                                "/db/migrations/V002__provider_accounts.sql")) {
                                    if (resource == null)
                                        throw new IllegalStateException(
                                                "Missing account migration");
                                    for (String command :
                                            new String(
                                                            resource.readAllBytes(),
                                                            StandardCharsets.UTF_8)
                                                    .split(";"))
                                        if (!command.isBlank()) statement.execute(command);
                                    statement.execute("PRAGMA user_version=2");
                                }
                            }
                        } catch (java.io.IOException | SQLException e) {
                            throw failure(e);
                        }
                        return null;
                    });
        } catch (java.io.IOException | SQLException e) {
            throw failure(e);
        }
    }

    private Connection connect() throws SQLException {
        var connection = DriverManager.getConnection(url);
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    public <T> T read(SqlFunction<T> action) {
        if (current() != null) {
            try {
                return action.apply(current());
            } catch (SQLException e) {
                throw failure(e);
            }
        }
        try (var connection = connect()) {
            return action.apply(connection);
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    public <T> T write(SqlFunction<T> action) {
        return transaction(() -> read(action));
    }

    public <T> T transaction(Supplier<T> action) {
        if (current() != null) return action.get();
        writer.lock();
        try (var connection = connect();
                var statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            TRANSACTION.get().put(url, connection);
            try {
                T result = action.get();
                statement.execute("COMMIT");
                return result;
            } catch (Throwable error) {
                try {
                    statement.execute("ROLLBACK");
                } catch (SQLException rollback) {
                    error.addSuppressed(rollback);
                }
                throw error;
            } finally {
                TRANSACTION.get().remove(url);
            }
        } catch (SQLException e) {
            throw failure(e);
        } finally {
            writer.unlock();
        }
    }

    private Connection current() {
        return TRANSACTION.get().get(url);
    }

    public DataPaths paths() {
        return paths;
    }

    public static IllegalStateException failure(Exception error) {
        return new IllegalStateException(
                "Syleks SQLite operation failed: " + error.getMessage(), error);
    }

    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
    }
}
