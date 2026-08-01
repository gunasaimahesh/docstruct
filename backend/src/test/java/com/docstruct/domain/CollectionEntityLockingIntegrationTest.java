package com.docstruct.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import java.util.stream.Stream;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.docstruct.domain.schema.DocumentSchema;
import com.docstruct.domain.schema.SchemaColumn;

/**
 * Exercises the {@code @Version} optimistic lock on {@link CollectionEntity} against a real
 * PostgreSQL instance. Mocks can show that a schema-merge conflict is retried, but only the
 * database can say whether the conflict is raised at all.
 */
@Testcontainers(disabledWithoutDocker = true)
class CollectionEntityLockingIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static JdbcTemplate jdbcTemplate;
    private static SessionFactory sessionFactory;

    @BeforeAll
    static void setUp() {
        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @AfterAll
    static void tearDown() {
        if (sessionFactory != null) {
            sessionFactory.close();
        }
    }

    private static SessionFactory buildSessionFactory(String ddlAuto) {
        return new Configuration()
                .addAnnotatedClass(CollectionEntity.class)
                .setProperty(AvailableSettings.JAKARTA_JDBC_URL, POSTGRES.getJdbcUrl())
                .setProperty(AvailableSettings.JAKARTA_JDBC_USER, POSTGRES.getUsername())
                .setProperty(AvailableSettings.JAKARTA_JDBC_PASSWORD, POSTGRES.getPassword())
                .setProperty(AvailableSettings.HBM2DDL_AUTO, ddlAuto)
                .buildSessionFactory();
    }

    private static DocumentSchema invoiceSchema() {
        return new DocumentSchema(
                List.of(new SchemaColumn("Vendor", ColumnType.TEXT, null, true)),
                "invoice", ConfidenceLevel.HIGH);
    }

    /** The merge ingestion performs: the existing columns plus one newly detected one. */
    private static DocumentSchema plus(DocumentSchema schema, String newColumn) {
        return schema.withColumns(Stream.concat(
                        schema.columns().stream(),
                        Stream.of(new SchemaColumn(newColumn, ColumnType.CURRENCY, null, false)))
                .toList());
    }

    @Test
    void concurrentSchemaMergeIsRejectedRatherThanLosingAColumn() {
        // Stand up the current table shape, plant a collection, then rewind the table to
        // its pre-@Version shape — the state an already-running deployment is in.
        String collectionId;
        try (SessionFactory beforeUpgrade = buildSessionFactory("create");
             Session session = beforeUpgrade.openSession()) {
            session.beginTransaction();
            CollectionEntity collection = CollectionEntity.create("Invoices", null, invoiceSchema());
            session.persist(collection);
            session.getTransaction().commit();
            collectionId = collection.getId();
        }
        jdbcTemplate.execute("ALTER TABLE collections DROP COLUMN version");

        // The fixed schema is managed by ddl-auto (decision #3), so the upgrade has to add a
        // NOT NULL column to a table that already has rows. Only the default makes that legal.
        sessionFactory = buildSessionFactory("update");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM collections WHERE id = ?", Long.class, collectionId))
                .isZero();

        Throwable conflict;
        try (Session first = sessionFactory.openSession();
             Session second = sessionFactory.openSession()) {

            first.beginTransaction();
            second.beginTransaction();

            // Both uploads read the same schema, as they would either side of an LLM call.
            CollectionEntity readByFirst = first.find(CollectionEntity.class, collectionId);
            CollectionEntity readBySecond = second.find(CollectionEntity.class, collectionId);
            assertThat(readByFirst.getVersion()).isEqualTo(readBySecond.getVersion());

            readByFirst.updateSchema(plus(readByFirst.getSchema(), "Tax"));
            first.getTransaction().commit();

            readBySecond.updateSchema(plus(readBySecond.getSchema(), "Currency"));
            conflict = catchThrowable(() -> second.getTransaction().commit());

            if (second.getTransaction().isActive()) {
                second.getTransaction().rollback();
            }
        }

        // IngestionService retries on Spring's translation of the failure, so the bridge from
        // Hibernate matters as much as the failure itself.
        assertThat(conflict).isNotNull();
        assertThat(new HibernateJpaDialect().translateExceptionIfPossible((RuntimeException) conflict))
                .isInstanceOf(OptimisticLockingFailureException.class);

        // The winner's column survived and the loser's write was rejected, not merged over it.
        try (Session session = sessionFactory.openSession()) {
            CollectionEntity stored = session.find(CollectionEntity.class, collectionId);
            assertThat(stored.getSchema().columns())
                    .extracting(SchemaColumn::name)
                    .containsExactly("Vendor", "Tax");
            assertThat(stored.getVersion()).isEqualTo(1L);
        }
    }
}
