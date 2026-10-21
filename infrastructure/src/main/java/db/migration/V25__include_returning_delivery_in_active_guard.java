package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.Statement;

/** RETURNING retains the shipper reservation until the restaurant confirms it. */
public class V25__include_returning_delivery_in_active_guard extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP INDEX IF EXISTS ux_deliveries_one_active_legacy_per_shipper");
            if (isPostgres(connection)) {
                statement.execute("""
                        CREATE UNIQUE INDEX IF NOT EXISTS ux_deliveries_one_active_legacy_per_shipper
                            ON deliveries (shipper_id)
                            WHERE shipper_id IS NOT NULL
                              AND batch_id IS NULL
                              AND status IN ('ASSIGNED', 'PICKED_UP', 'DELIVERING', 'RETURNING')
                        """);
            } else {
                statement.execute("CREATE INDEX IF NOT EXISTS ix_deliveries_returning_shipper_guard "
                        + "ON deliveries (shipper_id, batch_id, status)");
            }
        }
    }

    private boolean isPostgres(Connection connection) throws Exception {
        return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres");
    }
}
