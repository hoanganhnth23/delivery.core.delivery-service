package db.migration;

import java.sql.Connection;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Durable local mapping for principal-to-shipper authorization. */
public class V16__shipper_identity_projection extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        execute(connection, "CREATE TABLE IF NOT EXISTS shipper_identity_projection (principal_id BIGINT PRIMARY KEY,"
                + "legacy_user_id BIGINT NOT NULL UNIQUE,shipper_id BIGINT NOT NULL UNIQUE,mapping_version BIGINT NOT NULL,updated_at TIMESTAMP NOT NULL)");
        execute(connection, "CREATE TABLE IF NOT EXISTS shipper_identity_inbox_receipts (event_id UUID PRIMARY KEY,"
                + "event_type VARCHAR(128) NOT NULL,principal_id BIGINT NOT NULL,payload_fingerprint VARCHAR(64) NOT NULL,processed_at TIMESTAMP NOT NULL)");
    }
    private void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) { statement.execute(sql); }
    }
}
