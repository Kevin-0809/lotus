package com.lotus.gausscmp.integration;

import com.lotus.gausscmp.cli.Main;
import com.lotus.gausscmp.config.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class EndToEndIT {

    @Container
    static PostgreSQLContainer<?> src = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("srcdb").withUsername("test").withPassword("test");
    @Container
    static PostgreSQLContainer<?> tgt = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("tgtdb").withUsername("test").withPassword("test");

    @Test
    void consistentDatabasesExitZero(@TempDir Path tmp) throws Exception {
        setupSchema(src, "CREATE TABLE app.t (id int PRIMARY KEY, name text)");
        setupSchema(tgt, "CREATE TABLE app.t (id int PRIMARY KEY, name text)");
        exec(src, "INSERT INTO app.t VALUES (1,'a'),(2,'b')");
        exec(tgt, "INSERT INTO app.t VALUES (1,'a'),(2,'b')");
        Path cfg = writeConfig(tmp, src, tgt);
        int exitCode = new Main().execute("compare", "-c", cfg.toString());
        assertThat(exitCode).isEqualTo(0);
    }

    @Test
    void differentDatabasesExitOneAndGenerateScripts(@TempDir Path tmp) throws Exception {
        setupSchema(src, "CREATE TABLE app.t (id int PRIMARY KEY, name text)");
        setupSchema(tgt, "CREATE TABLE app.t (id int PRIMARY KEY, name text)");
        exec(src, "INSERT INTO app.t VALUES (1,'a'),(2,'b')");
        exec(tgt, "INSERT INTO app.t VALUES (1,'a'),(3,'c')");
        Path cfg = writeConfig(tmp, src, tgt);
        int exitCode = new Main().execute("compare", "-c", cfg.toString());
        assertThat(exitCode).isEqualTo(1);
        Path reportDir = findReportDir(Path.of(tmp.toString(), "report"));
        assertThat(reportDir.resolve("result.json")).exists();
        assertThat(reportDir.resolve("report.html")).exists();
        assertThat(reportDir.resolve("dml_sync.sql")).exists();
        String dml = Files.readString(reportDir.resolve("dml_sync.sql"));
        assertThat(dml).contains("INSERT").contains("DELETE");
    }

    @Test
    void ddlScriptFixesStructureDifference(@TempDir Path tmp) throws Exception {
        setupSchema(src, "CREATE TABLE app.t (id int PRIMARY KEY, name text)");
        setupSchema(tgt, "CREATE TABLE app.t (id int PRIMARY KEY)");
        exec(src, "INSERT INTO app.t VALUES (1,'a')");
        exec(tgt, "INSERT INTO app.t VALUES (1)");
        Path cfg = writeConfig(tmp, src, tgt);
        int exitCode = new Main().execute("compare", "-c", cfg.toString());
        assertThat(exitCode).isEqualTo(1);
        Path reportDir = findReportDir(Path.of(tmp.toString(), "report"));
        String ddl = Files.readString(reportDir.resolve("ddl_sync.sql"));
        assertThat(ddl).contains("ADD COLUMN");
    }

    @Test
    void noPrimaryKeyTableSkipped(@TempDir Path tmp) throws Exception {
        setupSchema(src, "CREATE TABLE app.no_pk (a int, b int)");
        setupSchema(tgt, "CREATE TABLE app.no_pk (a int, b int)");
        exec(src, "INSERT INTO app.no_pk VALUES (1,2)");
        exec(tgt, "INSERT INTO app.no_pk VALUES (1,2)");
        Path cfg = writeConfig(tmp, src, tgt);
        int exitCode = new Main().execute("compare", "-c", cfg.toString());
        assertThat(exitCode).isEqualTo(0);
        Path reportDir = findReportDir(Path.of(tmp.toString(), "report"));
        String json = Files.readString(reportDir.resolve("result.json"));
        assertThat(json).contains("SKIPPED");
    }

    private Path writeConfig(Path tmp, PostgreSQLContainer<?> s, PostgreSQLContainer<?> t) throws Exception {
        Path cfg = tmp.resolve("compare.yaml");
        String content = """
            source:
              host: %s
              port: %d
              database: %s
              username: %s
              password: %s
              schema: app
              readOnly: false
            target:
              host: %s
              port: %d
              database: %s
              username: %s
              password: %s
              schema: app
              readOnly: false
            options:
              parallelism: 1
              chunkSize: 5000
              drillDown: true
              checksumFunction: md5
              tableFilter: {include: [".*"], exclude: []}
              output: {dir: "%s/report", html: true, ddlScript: true, dmlScript: true}
              syncDirection: source-to-target
              maxDisplayRows: 1000
              tableTimeout: 0
            """.formatted(s.getHost(), s.getMappedPort(5432), s.getDatabaseName(), s.getUsername(), s.getPassword(),
                          t.getHost(), t.getMappedPort(5432), t.getDatabaseName(), t.getUsername(), t.getPassword(),
                          tmp.toString().replace("\\", "/"));
        Files.writeString(cfg, content);
        return cfg;
    }

    private static void setupSchema(PostgreSQLContainer<?> c, String ddl) throws Exception {
        try (var conn = java.sql.DriverManager.getConnection(c.getJdbcUrl(), c.getUsername(), c.getPassword());
             var st = conn.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS app");
            st.execute(ddl);
        }
    }

    private static void exec(PostgreSQLContainer<?> c, String sql) throws Exception {
        try (var conn = java.sql.DriverManager.getConnection(c.getJdbcUrl(), c.getUsername(), c.getPassword());
             var st = conn.createStatement()) { st.execute(sql); }
    }

    private static Path findReportDir(Path reportRoot) throws Exception {
        try (var stream = Files.list(reportRoot)) {
            return stream.findFirst().orElseThrow();
        }
    }
}
