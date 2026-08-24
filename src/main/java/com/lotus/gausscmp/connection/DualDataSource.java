package com.lotus.gausscmp.connection;

import com.lotus.gausscmp.config.SourceConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DualDataSource implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(DualDataSource.class);
    private final HikariDataSource source;
    private final HikariDataSource target;

    public DualDataSource(SourceConfig src, SourceConfig tgt, int parallelism) {
        int workers = Math.max(1, parallelism);
        int poolSize = workers;
        LOG.debug("创建比对连接池: parallelism={}, poolSize={}", workers, poolSize);
        this.source = build(src, poolSize);
        this.target = build(tgt, poolSize);
    }

    private static HikariDataSource build(SourceConfig c, int poolSize) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(c.url());
        hc.setUsername(c.username());
        hc.setPassword(c.password());
        hc.setMaximumPoolSize(poolSize);
        hc.setReadOnly(c.readOnly());
        hc.setConnectionTimeout(30000);
        hc.setIdleTimeout(600000);
        hc.setInitializationFailTimeout(0);
        hc.setPoolName("gausscmp-" + c.username() + "-" + Integer.toHexString(c.url().hashCode()));
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
