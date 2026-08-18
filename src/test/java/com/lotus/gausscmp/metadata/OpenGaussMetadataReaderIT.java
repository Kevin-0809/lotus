package com.lotus.gausscmp.metadata;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class OpenGaussMetadataReaderIT {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("testdb").withUsername("test").withPassword("test");

    @Test
    void readsTablesColumnsConstraintsIndexes() throws Exception {
        try (Connection conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
            try (var st = conn.createStatement()) {
                st.execute("CREATE SCHEMA app");
                st.execute("CREATE TABLE app.t1 (id integer PRIMARY KEY, name varchar(50) NOT NULL, created_at timestamp default now())");
                st.execute("CREATE INDEX idx_t1_name ON app.t1 (name)");
                st.execute("COMMENT ON TABLE app.t1 IS 'demo table'");
                st.execute("CREATE TABLE app.no_pk (a int, b int)");
            }
            OpenGaussMetadataReader reader = new OpenGaussMetadataReader();
            SchemaSnapshot snap = reader.read(conn, "app", List.of(".*"), List.of());
            assertThat(snap.tables()).hasSize(2);
            TableMeta t1 = snap.tables().stream().filter(t -> t.name().equals("t1")).findFirst().orElseThrow();
            assertThat(t1.comment()).isEqualTo("demo table");
            assertThat(t1.columns()).hasSize(3);
            assertThat(t1.columns().get(0).dataType()).isEqualTo("integer");
            assertThat(t1.hasPrimaryKeyOrUnique()).isTrue();
            assertThat(t1.indexes()).hasSize(1);
            assertThat(t1.indexes().get(0).name()).isEqualTo("idx_t1_name");
            TableMeta noPk = snap.tables().stream().filter(t -> t.name().equals("no_pk")).findFirst().orElseThrow();
            assertThat(noPk.hasPrimaryKeyOrUnique()).isFalse();
        }
    }

    @Test
    void appliesIncludeExcludeFilters() throws Exception {
        try (Connection conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
            try (var st = conn.createStatement()) {
                st.execute("CREATE SCHEMA app2");
                st.execute("CREATE TABLE app2.real_t (id int PRIMARY KEY)");
                st.execute("CREATE TABLE app2.tmp_t (id int)");
                st.execute("CREATE TABLE app2.bak_t (id int)");
            }
            OpenGaussMetadataReader reader = new OpenGaussMetadataReader();
            SchemaSnapshot snap = reader.read(conn, "app2", List.of(".*"), List.of("^tmp_.*", "^bak_.*"));
            assertThat(snap.tables()).hasSize(1);
            assertThat(snap.tables().get(0).name()).isEqualTo("real_t");
        }
    }
}
