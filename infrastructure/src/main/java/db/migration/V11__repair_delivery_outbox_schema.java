package db.migration;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Repairs delivery outbox tables that predate the durable event identity contract. */
public class V11__repair_delivery_outbox_schema extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String outbox = requireTable(connection, "outbox_events");

        addColumnIfMissing(connection, outbox, "event_id", "event_id UUID");
        addColumnIfMissing(connection, outbox, "aggregate_type", "aggregate_type VARCHAR(64)");
        addColumnIfMissing(connection, outbox, "aggregate_id", "aggregate_id VARCHAR(255)");
        addColumnIfMissing(connection, outbox, "event_type", "event_type VARCHAR(128)");
        addColumnIfMissing(connection, outbox, "event_key", "event_key VARCHAR(255)");
        addColumnIfMissing(connection, outbox, "attempts", "attempts INTEGER");
        addColumnIfMissing(connection, outbox, "next_attempt_at", "next_attempt_at TIMESTAMP");
        addColumnIfMissing(connection, outbox, "last_error", "last_error VARCHAR(2000)");

        if (hasColumn(connection, outbox, "retry_count")) {
            execute(connection, "UPDATE " + outbox + " SET attempts = retry_count WHERE attempts IS NULL");
        }
        execute(connection, "UPDATE " + outbox + " SET attempts = 0 WHERE attempts IS NULL");
        execute(connection, "UPDATE " + outbox + " SET next_attempt_at = created_at "
                + "WHERE next_attempt_at IS NULL AND created_at IS NOT NULL");
        execute(connection, "UPDATE " + outbox + " SET next_attempt_at = CURRENT_TIMESTAMP "
                + "WHERE next_attempt_at IS NULL");
        if (hasColumn(connection, outbox, "error_message")) {
            execute(connection, "UPDATE " + outbox + " SET last_error = error_message "
                    + "WHERE last_error IS NULL");
        }

        long unreconciled = queryCount(connection, """
                SELECT count(*) FROM outbox_events
                WHERE event_id IS NULL
                   OR aggregate_type IS NULL
                   OR aggregate_id IS NULL
                   OR event_type IS NULL
                   OR topic IS NULL
                   OR event_key IS NULL
                   OR payload IS NULL
                   OR status IS NULL
                   OR attempts IS NULL
                   OR next_attempt_at IS NULL
                   OR created_at IS NULL
                """);
        if (unreconciled > 0) {
            throw new FlywayException("Delivery outbox contains " + unreconciled
                    + " legacy row(s) without durable event identity; manual reconciliation is required");
        }

        dropColumnIfExists(connection, outbox, "retry_count");
        dropColumnIfExists(connection, outbox, "error_message");

        requireNotNull(connection, outbox, "event_id");
        requireNotNull(connection, outbox, "aggregate_type");
        requireNotNull(connection, outbox, "aggregate_id");
        requireNotNull(connection, outbox, "event_type");
        requireNotNull(connection, outbox, "topic");
        requireNotNull(connection, outbox, "event_key");
        requireNotNull(connection, outbox, "payload");
        requireNotNull(connection, outbox, "status");
        requireNotNull(connection, outbox, "attempts");
        requireNotNull(connection, outbox, "next_attempt_at");
        requireNotNull(connection, outbox, "created_at");

        if (!hasUniqueSingleColumnIndex(connection, outbox, "event_id")) {
            execute(connection, "ALTER TABLE " + outbox
                    + " ADD CONSTRAINT uk_delivery_outbox_event_id UNIQUE (event_id)");
        }

        execute(connection, "ALTER TABLE " + outbox + " DROP CONSTRAINT IF EXISTS outbox_events_status_check");
        execute(connection, "ALTER TABLE " + outbox + " DROP CONSTRAINT IF EXISTS ck_delivery_outbox_status");
        execute(connection, "ALTER TABLE " + outbox
                + " ADD CONSTRAINT ck_delivery_outbox_status CHECK (status IN ('PENDING', 'SENT', 'DEAD'))");

        execute(connection, """
                CREATE INDEX IF NOT EXISTS idx_delivery_outbox_pending
                ON outbox_events (status, next_attempt_at, created_at)
                """);
        execute(connection, """
                CREATE INDEX IF NOT EXISTS idx_delivery_outbox_aggregate
                ON outbox_events (aggregate_type, aggregate_id, created_at)
                """);
    }

    private void addColumnIfMissing(Connection connection, String tableName, String columnName,
                                    String definition) throws SQLException {
        if (!hasColumn(connection, tableName, columnName)) {
            execute(connection, "ALTER TABLE " + tableName + " ADD COLUMN " + definition);
        }
    }

    private void dropColumnIfExists(Connection connection, String tableName, String columnName) throws SQLException {
        if (hasColumn(connection, tableName, columnName)) {
            execute(connection, "ALTER TABLE " + tableName + " DROP COLUMN " + columnName);
        }
    }

    private boolean hasColumn(Connection connection, String tableName, String columnName) throws SQLException {
        try (ResultSet resultSet = connection.getMetaData()
                .getColumns(null, connection.getSchema(), tableName, "%")) {
            while (resultSet.next()) {
                if (columnName.equalsIgnoreCase(resultSet.getString("COLUMN_NAME"))) return true;
            }
        }
        return false;
    }

    private void requireNotNull(Connection connection, String tableName, String columnName) throws SQLException {
        execute(connection, "ALTER TABLE " + tableName + " ALTER COLUMN " + columnName + " SET NOT NULL");
    }

    private long queryCount(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private String requireTable(Connection connection, String expectedName) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet tables = metadata.getTables(
                null, connection.getSchema(), "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String name = tables.getString("TABLE_NAME");
                if (expectedName.equalsIgnoreCase(name)) return name;
            }
        }
        throw new FlywayException("Existing Delivery outbox schema is incomplete: missing table " + expectedName);
    }

    private boolean hasUniqueSingleColumnIndex(Connection connection, String tableName, String columnName)
            throws SQLException {
        Map<String, Set<String>> columnsByIndex = new HashMap<>();
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(
                null, connection.getSchema(), tableName, true, false)) {
            while (indexes.next()) {
                String index = indexes.getString("INDEX_NAME");
                String column = indexes.getString("COLUMN_NAME");
                if (index != null && column != null) {
                    columnsByIndex.computeIfAbsent(index.toLowerCase(Locale.ROOT), ignored -> new HashSet<>())
                            .add(column.toLowerCase(Locale.ROOT));
                }
            }
        }
        return columnsByIndex.values().stream()
                .anyMatch(columns -> columns.equals(Set.of(columnName.toLowerCase(Locale.ROOT))));
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
