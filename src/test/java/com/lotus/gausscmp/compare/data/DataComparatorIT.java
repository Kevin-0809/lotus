package com.lotus.gausscmp.compare.data;

import com.lotus.gausscmp.compare.diff.*;
import com.lotus.gausscmp.config.SourceConfig;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class DataComparatorIT {

    @Container
    static PostgreSQLContainer<?> srcPg = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("s").withUsername("s").withPassword("s");
    @Container
    static PostgreSQLContainer<?> tgtPg = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("t").withUsername("t").withPassword("t");

    @Test
    void detectsDataDifferences() throws Exception {
        try (Connection sConn = DriverManager.getConnection(srcPg.getJdbcUrl(), srcPg.getUsername(), srcPg.getPassword());
             Connection tConn = DriverManager.getConnection(tgtPg.getJdbcUrl(), tgtPg.getUsername(), tgtPg.getPassword())) {
            setup(sConn, "CREATE TABLE app.t (id int PRIMARY KEY, name text)");
            setup(tConn, "CREATE TABLE app.t (id int PRIMARY KEY, name text)");
            exec(sConn, "INSERT INTO app.t VALUES (1,'a'),(2,'b'),(3,'c')");
            exec(tConn, "INSERT INTO app.t VALUES (1,'a'),(2,'x'),(4,'d')");
            SourceConfig srcCfg = new SourceConfig("jdbc:postgresql://h:5432/s", "s", "s", "app", true);
            SourceConfig tgtCfg = new SourceConfig("jdbc:postgresql://h:5432/t", "t", "t", "app", true);
            var dataCmp = new DataComparator(srcCfg, tgtCfg, 5000, "md5", true, 1000);
            TableDataDiff diff = dataCmp.compareTable(sConn, tConn, "t", List.of("id"), TableDataStatus.CONSISTENT);
            assertThat(diff.status()).isEqualTo(TableDataStatus.DIFFERENT);
            assertThat(diff.sourceRowCount()).isEqualTo(3);
            assertThat(diff.targetRowCount()).isEqualTo(3);
            assertThat(diff.rowDiffs()).hasSize(3);
            assertThat(diff.rowDiffs()).anyMatch(r -> r.type() == RowDiffType.MISMATCH);
            assertThat(diff.rowDiffs()).anyMatch(r -> r.type() == RowDiffType.MISSING_IN_TARGET);
            assertThat(diff.rowDiffs()).anyMatch(r -> r.type() == RowDiffType.EXTRA_IN_TARGET);
        }
    }

    @Test
    void consistentTablesNoDiff() throws Exception {
        try (Connection sConn = DriverManager.getConnection(srcPg.getJdbcUrl(), srcPg.getUsername(), srcPg.getPassword());
             Connection tConn = DriverManager.getConnection(tgtPg.getJdbcUrl(), tgtPg.getUsername(), tgtPg.getPassword())) {
            setup(sConn, "CREATE TABLE app.t2 (id int PRIMARY KEY, name text)");
            setup(tConn, "CREATE TABLE app.t2 (id int PRIMARY KEY, name text)");
            exec(sConn, "INSERT INTO app.t2 VALUES (1,'a'),(2,'b')");
            exec(tConn, "INSERT INTO app.t2 VALUES (1,'a'),(2,'b')");
            SourceConfig srcCfg = new SourceConfig("jdbc:postgresql://h:5432/s", "s", "s", "app", true);
            SourceConfig tgtCfg = new SourceConfig("jdbc:postgresql://h:5432/t", "t", "t", "app", true);
            var dataCmp = new DataComparator(srcCfg, tgtCfg, 5000, "md5", true, 1000);
            TableDataDiff diff = dataCmp.compareTable(sConn, tConn, "t2", List.of("id"), TableDataStatus.CONSISTENT);
            assertThat(diff.status()).isEqualTo(TableDataStatus.CONSISTENT);
        }
    }

    private static void setup(Connection c, String ddl) throws Exception {
        try (var st = c.createStatement()) { st.execute("CREATE SCHEMA app"); st.execute(ddl); }
    }
    private static void exec(Connection c, String sql) throws Exception {
        try (var st = c.createStatement()) { st.execute(sql); }
    }
}
