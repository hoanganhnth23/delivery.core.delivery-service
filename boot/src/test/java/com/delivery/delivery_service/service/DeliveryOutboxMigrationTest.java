package com.delivery.delivery_service.service;

import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryOutboxMigrationTest {

    @Test
    void createEventIdentityMigrationHasRecoverableV8V9Boundary() throws Exception {
        String v8 = new ClassPathResource(
                "db/migration/V8__add_delivery_create_event_identity.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        String v9 = new ClassPathResource(
                "db/migration/V9__enforce_delivery_create_event_identity.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(v8).contains("create_event_id UUID NULL").doesNotContain("SET NOT NULL");
        assertThat(v9).contains("create_event_id IS NULL", "SET NOT NULL",
                "uk_deliveries_create_event_id");
    }

    @Test
    void outboxMigrationCreatesUsableSchemaWithoutHibernate() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:delivery_outbox_migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V1__create_deliveries.sql"),
                new ClassPathResource("db/migration/V3__add_shipping_fee_to_deliveries.sql"),
                new ClassPathResource("db/migration/V4__add_shipper_offer_to_deliveries.sql"),
                new ClassPathResource("db/migration/V6__create_delivery_outbox.sql"))
                .execute(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        LocalDateTime now = LocalDateTime.now();

        jdbc.update("""
                INSERT INTO deliveries(order_id, status, creator_id, shipping_fee, offered_shipper_id)
                VALUES (?, ?, ?, ?, ?)
                """, 20L, "FINDING_SHIPPER", 8L, 15000, 10L);

        jdbc.update("""
                INSERT INTO outbox_events(event_id, aggregate_type, aggregate_id, event_type,
                    topic, event_key, payload, status, attempts, next_attempt_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), "DELIVERY", "1", "DELIVERY_CREATED_RESULT",
                "delivery.created.result", "20", "{}", "PENDING", 0, now, now);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM deliveries", Long.class)).isEqualTo(1L);
    }

    @Test
    void inboundCommandReceiptMigrationAddsAUniqueDurableIdentityFence() throws Exception {
        var dataSource = new DriverManagerDataSource(
                databaseUrl("inbound_command_receipts"), "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V14__delivery_inbound_command_receipts.sql")).execute(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID eventId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();

        jdbc.update("""
                INSERT INTO delivery_inbound_receipts(event_id, command_type, order_id,
                    delivery_id, payload_fingerprint, received_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, eventId, "CACHE_SHIPPER_OFFER", 20L, 30L, "a".repeat(64), now);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO delivery_inbound_receipts(event_id, command_type, order_id,
                    delivery_id, payload_fingerprint, received_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, eventId, "CACHE_SHIPPER_OFFER", 20L, 30L, "a".repeat(64), now))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void versionSixThroughElevenRepairsEmptyLegacyOutboxTable() throws Exception {
        var dataSource = new DriverManagerDataSource(
                databaseUrl("empty_legacy_outbox"), "sa", "");
        createVersionFiveDeliverySchemaWithLegacyOutbox(dataSource, false);

        applyOutboxRepairMigrations(dataSource);

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO outbox_events(event_id, aggregate_type, aggregate_id, event_type,
                    topic, event_key, payload, status, attempts, next_attempt_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), "DELIVERY", "1", "DELIVERY_CREATED_RESULT",
                "delivery.created.result", "20", "{}", "PENDING", 0, now, now);

        assertThat(columnExists(dataSource, "outbox_events", "next_attempt_at")).isTrue();
        assertThat(columnExists(dataSource, "outbox_events", "event_id")).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class)).isEqualTo(1L);
    }

    @Test
    void versionElevenStopsWhenLegacyOutboxRowsHaveNoDurableEventIdentity() throws Exception {
        var dataSource = new DriverManagerDataSource(
                databaseUrl("unreconciled_legacy_outbox"), "sa", "");
        createVersionFiveDeliverySchemaWithLegacyOutbox(dataSource, true);

        assertThatThrownBy(() -> applyOutboxRepairMigrations(dataSource))
                .isInstanceOf(FlywayException.class)
                .rootCause()
                .hasMessageContaining("legacy row")
                .hasMessageContaining("manual reconciliation");
    }

    private void applyOutboxRepairMigrations(DriverManagerDataSource dataSource) {
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V6__create_delivery_outbox.sql"))
                .execute(dataSource);
        Flyway.configure()
                .dataSource(dataSource)
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("10"))
                .load()
                .migrate();
    }

    private void createVersionFiveDeliverySchemaWithLegacyOutbox(
            DriverManagerDataSource dataSource,
            boolean insertLegacyOutboxRow) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE deliveries (
                        id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                        order_id BIGINT NOT NULL,
                        shipper_id BIGINT,
                        status VARCHAR(255),
                        total_price DECIMAL(12,2),
                        shipping_fee DECIMAL(12,2),
                        created_at TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE outbox_events (
                        id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                        created_at TIMESTAMP NOT NULL,
                        error_message VARCHAR(255),
                        event_key VARCHAR(255),
                        payload TEXT NOT NULL,
                        retry_count INTEGER NOT NULL,
                        sent_at TIMESTAMP,
                        status VARCHAR(255) NOT NULL,
                        topic VARCHAR(255) NOT NULL
                    )
                    """);
            if (insertLegacyOutboxRow) {
                statement.execute("""
                        INSERT INTO outbox_events(created_at, event_key, payload, retry_count, status, topic)
                        VALUES (CURRENT_TIMESTAMP, '20', '{}', 0, 'PENDING', 'delivery.created.result')
                        """);
            }
        }
    }

    private boolean columnExists(DriverManagerDataSource dataSource, String table, String column) throws Exception {
        try (Connection connection = dataSource.getConnection();
             ResultSet columns = connection.getMetaData().getColumns(null, null, table, column)) {
            return columns.next();
        }
    }

    private String databaseUrl(String label) {
        return "jdbc:h2:mem:delivery_outbox_migration_" + label + "_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
    }
}
