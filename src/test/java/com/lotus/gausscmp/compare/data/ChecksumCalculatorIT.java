package com.lotus.gausscmp.compare.data;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class ChecksumCalculatorIT {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("t").withUsername("t").withPassword("t");

    @Test
    void computesRowCountAndChecksum() throws Exception {
        try (Connection conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
            try (var st = conn.createStatement()) {
                st.execute("CREATE SCHEMA app");
                st.execute("CREATE TABLE app.t (id int PRIMARY KEY, name text)");
                st.execute("INSERT INTO app.t VALUES (1,'a'),(2,'b'),(3,'c')");
            }
            var calc = new ChecksumCalculator("md5", 5000);
            var result = calc.calculate(conn, "app", "t", List.of("id"));
            assertThat(result.rowCount()).isEqualTo(3);
            assertThat(result.checksum()).isNotBlank();
        }
    }

    @Test
    void checksumChangesWhenDataChanges() throws Exception {
        try (Connection conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
            try (var st = conn.createStatement()) {
                st.execute("CREATE SCHEMA app2");
                st.execute("CREATE TABLE app2.t (id int PRIMARY KEY, name text)");
                st.execute("INSERT INTO app2.t VALUES (1,'a'),(2,'b')");
            }
            var calc = new ChecksumCalculator("md5", 5000);
            var r1 = calc.calculate(conn, "app2", "t", List.of("id"));
            try (var st = conn.createStatement()) {
                st.execute("UPDATE app2.t SET name='x' WHERE id=1");
            }
            var r2 = calc.calculate(conn, "app2", "t", List.of("id"));
            assertThat(r1.checksum()).isNotEqualTo(r2.checksum());
            assertThat(r1.rowCount()).isEqualTo(r2.rowCount());
        }
    }
}
