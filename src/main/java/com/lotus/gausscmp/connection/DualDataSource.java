package com.lotus.gausscmp.connection;

import com.lotus.gausscmp.config.SourceConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public final class DualDataSource implements AutoCloseable {
    private final HikariDataSource source;
    private final HikariDataSource target;

    public DualDataSource(SourceConfig src, SourceConfig tgt, int parallelism) {
        this.source = build(src, Math.max(2, parallelism + 1));
        this.target = build(tgt, Math.max(2, parallelism + 1));
    }

    private static HikariDataSource build(SourceConfig c, int poolSize) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(String.format("jdbc:postgresql://%s:%d/%s", c.host(), c.port(), c.database()));
        hc.setUsername(c.username());
        hc.setPassword(c.password());
        hc.setMaximumPoolSize(poolSize);
        hc.setReadOnly(c.readOnly());
        hc.setConnectionTimeout(30000);
        hc.setIdleTimeout(600000);
        hc.setInitializationFailTimeout(0);
        hc.setPoolName("gausscmp-" + c.host());
        return new HikariDataSource(hc);
    }

    public HikariDataSource source() { return source; }
    public HikariDataSource target() { return target; }

    @Override
    public void close() {
        if (source != null) source.close();
        if (target != null) target.close();
    }
}
