package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.Statement;

/**
 * Switches the legacy single-delivery guard to a batch-aware guard.
 * PostgreSQL owns the production uniqueness policy; the H2 validation harness
 * receives non-unique projection indexes because H2 has no partial indexes.
 */
public class V21__allow_active_delivery_batch_per_shipper extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP INDEX IF EXISTS ux_deliveries_one_active_per_shipper");
            if (isPostgres(connection)) {
                statement.execute("""
                        CREATE UNIQUE INDEX IF NOT EXISTS ux_deliveries_one_active_legacy_per_shipper
                            ON deliveries (shipper_id)
                            WHERE shipper_id IS NOT NULL
                              AND batch_id IS NULL
                              AND status IN ('ASSIGNED', 'PICKED_UP', 'DELIVERING')
                        """);
                statement.execute("""
                        CREATE UNIQUE INDEX IF NOT EXISTS ux_delivery_batches_one_active_per_shipper
                            ON delivery_batches (shipper_id)
                            WHERE shipper_id IS NOT NULL
                              AND status IN ('OFFERED', 'ACCEPTED', 'PICKED_UP', 'DELIVERING')
                        """);
            } else {
                // H2 does not implement PostgreSQL partial indexes. The
                // migration test only needs the schema projection; production
                // deployments always run the PostgreSQL branch above.
                statement.execute("CREATE INDEX IF NOT EXISTS ix_deliveries_batch_shipper_guard ON deliveries (shipper_id, batch_id, status)");
                statement.execute("CREATE INDEX IF NOT EXISTS ix_delivery_batches_shipper_guard ON delivery_batches (shipper_id, status)");
            }
        }
    }

    private boolean isPostgres(Connection connection) throws Exception {
        return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres");
    }
}
