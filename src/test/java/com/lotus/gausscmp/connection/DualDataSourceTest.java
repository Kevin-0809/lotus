package com.lotus.gausscmp.connection;

import com.lotus.gausscmp.config.SourceConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DualDataSourceTest {

    @Test
    void buildsTwoPoolsWithCorrectParams() {
        SourceConfig src = new SourceConfig("h1", 5432, "d1", "u1", "p1", "s1", true);
        SourceConfig tgt = new SourceConfig("h2", 5432, "d2", "u2", "p2", "s2", true);
        try (var ds = new DualDataSource(src, tgt, 2)) {
            HikariDataSource s = ds.source();
            HikariDataSource t = ds.target();
            assertThat(s.getMaximumPoolSize()).isEqualTo(3);
            assertThat(t.getMaximumPoolSize()).isEqualTo(3);
            assertThat(s.isReadOnly()).isTrue();
            assertThat(t.isReadOnly()).isTrue();
            assertThat(s.getJdbcUrl()).contains("h1", "5432", "d1");
        }
    }

    @Test
    void closesBothPools() {
        SourceConfig src = new SourceConfig("h1", 5432, "d1", "u1", "p1", "s1", true);
        SourceConfig tgt = new SourceConfig("h2", 5432, "d2", "u2", "p2", "s2", true);
        var ds = new DualDataSource(src, tgt, 1);
        ds.close();
        assertThat(ds.source().isClosed()).isTrue();
        assertThat(ds.target().isClosed()).isTrue();
    }
}
