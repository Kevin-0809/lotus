package com.lotus.gausscmp.web.service;

import com.lotus.gausscmp.metadata.*;
import com.lotus.gausscmp.web.entity.*;
import com.lotus.gausscmp.web.repository.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Value;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class MetadataCollectService {
    private static final Logger LOG = LoggerFactory.getLogger(MetadataCollectService.class);
    private static final long COLLECT_LOCK_WAIT_MS = 100;
    private static final int JDBC_BATCH_SIZE = 2000;

    private final ConnectionRepository connectionRepo;
    private final MetaTableRepository tableRepo;
    private final MetaColumnRepository columnRepo;
    private final MetaConstraintRepository constraintRepo;
    private final MetaIndexRepository indexRepo;
    private final MetaSequenceRepository sequenceRepo;
    private final MetaPartitionRepository partitionRepo;
    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final ProgressTracker tracker;
    private final int historyRetention;

    private final ConcurrentHashMap<Long, ReentrantLock> collectLocks = new ConcurrentHashMap<>();

    public MetadataCollectService(ConnectionRepository connectionRepo,
                                  MetaTableRepository tableRepo,
                                  MetaColumnRepository columnRepo,
                                  MetaConstraintRepository constraintRepo,
                                  MetaIndexRepository indexRepo,
                                  MetaSequenceRepository sequenceRepo,
                                  MetaPartitionRepository partitionRepo,
                                  PlatformTransactionManager transactionManager,
                                  JdbcTemplate jdbcTemplate,
                                  ProgressTracker tracker,
                                  @Value("${metadata.collect.parallelism:16}") int parallelism,
                                  @Value("${metadata.collect.history-retention:0}") int historyRetention) {
        this.connectionRepo = connectionRepo;
        this.tableRepo = tableRepo;
        this.columnRepo = columnRepo;
        this.constraintRepo = constraintRepo;
        this.indexRepo = indexRepo;
        this.sequenceRepo = sequenceRepo;
        this.partitionRepo = partitionRepo;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.jdbcTemplate = jdbcTemplate;
        this.tracker = tracker;
        this.historyRetention = Math.max(0, historyRetention);
    }

    public static class CollectResult {
        public final int tableCount;
        public final int sequenceCount;
        public final long durationMs;
        public CollectResult(int t, int s, long d) { tableCount = t; sequenceCount = s; durationMs = d; }
    }

    public CollectResult collect(Long connectionId) throws Exception {
        return collect(connectionId, null);
    }

    public CollectResult collect(Long connectionId, String progressId) throws Exception {
        ReentrantLock lock = collectLocks.computeIfAbsent(connectionId, k -> new ReentrantLock());
        if (!lock.tryLock(COLLECT_LOCK_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            if (progressId != null) tracker.fail(progressId, "该数据源正在采集元数据");
            throw new IllegalStateException("该数据源正在采集元数据，请稍后再试: connectionId=" + connectionId);
        }
        try {
            CollectResult result = transactionTemplate.execute(status -> {
                try {
                    return doCollect(connectionId, progressId);
                } catch (Exception e) {
                    if (progressId != null) tracker.fail(progressId, e.getMessage());
                    if (e instanceof RuntimeException runtime) throw runtime;
                    throw new IllegalStateException("元数据采集失败: " + e.getMessage(), e);
                }
            });
            if (progressId != null) tracker.complete(progressId);
            return result;
        } finally {
            lock.unlock();
            collectLocks.remove(connectionId, lock);
        }
    }

    private CollectResult doCollect(Long connectionId, String progressId) throws Exception {
        DbConnection conn = connectionRepo.findById(connectionId)
            .orElseThrow(() -> new NoSuchElementException("数据源不存在: " + connectionId));

        long start = System.currentTimeMillis();
        LOG.info("开始采集元数据: connectionId={}, name={}, schema={}",
            connectionId, conn.getName(), conn.getSchema());
        HikariDataSource ds = null;
        try {
            HikariConfig hc = new HikariConfig();
            hc.setJdbcUrl(conn.getUrl());
            hc.setUsername(conn.getUsername());
            hc.setPassword(conn.getPassword());
            hc.setMaximumPoolSize(2);
            hc.setMinimumIdle(1);
            hc.setConnectionTimeout(30000);
            hc.setReadOnly(true);
            hc.setPoolName("meta-collect-" + connectionId);
            ds = new HikariDataSource(hc);

            OpenGaussMetadataReader reader = new OpenGaussMetadataReader();
            if (progressId != null) tracker.phase(progressId, "备份历史元数据");
            backupCurrentMetadata(connectionId);
            deleteOldMetadata(connectionId);

            if (progressId != null) tracker.phase(progressId, "批量读取表元数据");
            LOG.info("批量读取表元数据: connectionId={}, schema={}", connectionId, conn.getSchema());
            List<TableMeta> tables;
            List<SequenceMeta> sequences;
            OpenGaussMetadataReader.BatchProgressListener progress = progressId == null ? null
                : new OpenGaussMetadataReader.BatchProgressListener() {
                    @Override public void onTableCount(int count) { tracker.total(progressId, count); }
                    @Override public void onTableDone(String tableName) { tracker.increment(progressId); }
                };
            try (Connection jdbc = ds.getConnection()) {
                tables = reader.readAllTables(jdbc, conn.getSchema(), progress);
                sequences = reader.readSequences(jdbc, conn.getSchema());
            }

            if (progressId != null) tracker.phase(progressId, "写入新元数据");
            LocalDateTime collectedAt = LocalDateTime.now();
            long snapshotId = createSnapshot(connectionId, collectedAt);
            persistMetadata(connectionId, conn, tables, sequences, snapshotId, collectedAt);
            finalizeSnapshot(snapshotId, tables.size(), sequences.size(),
                System.currentTimeMillis() - start);
            if (historyRetention > 0) cleanupExpiredSnapshots(connectionId, historyRetention);

            conn.setCollectedAt(LocalDateTime.now());
            connectionRepo.save(conn);

            int tableCount = tables.size();
            int seqCount = sequences.size();
            long duration = System.currentTimeMillis() - start;
            LOG.info("元数据采集完成: connectionId={}, tables={}, sequences={}, durationMs={}",
                connectionId, tableCount, seqCount, duration);
            return new CollectResult(tableCount, seqCount, duration);
        } finally {
            if (ds != null) ds.close();
        }
    }

    private void persistMetadata(Long connectionId, DbConnection conn,
                                 List<TableMeta> tables, List<SequenceMeta> sequences,
                                 long snapshotId, LocalDateTime collectedAt) {
        Timestamp ts = Timestamp.valueOf(collectedAt);
        long[] tableIds = allocateIds("meta_table", tables.size());
        Map<String, Long> tableIdByName = new HashMap<>(tables.size() * 2);

        List<Object[]> tableRows = new ArrayList<>(tables.size());
        for (int i = 0; i < tables.size(); i++) {
            TableMeta tm = tables.get(i);
            tableIdByName.put(tm.name(), tableIds[i]);
            tableRows.add(new Object[]{
                tableIds[i], snapshotId, connectionId, conn.getName(), conn.getSchema(),
                tm.name(), tm.comment(), tm.partitioned(), tm.partitionStrategy(), tm.partitionKey(), ts});
        }
        batchUpdate("INSERT INTO meta_table(id, snapshot_id, connection_id, connection_name, schema_name, " +
                "table_name, comment, partitioned, partition_strategy, partition_key, collected_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            tableRows);

        int columnTotal = tables.stream().mapToInt(t -> t.columns().size()).sum();
        long[] columnIds = allocateIds("meta_column", columnTotal);
        List<Object[]> columnRows = new ArrayList<>(Math.max(1, columnTotal));
        int ci = 0;
        for (TableMeta tm : tables) {
            Long tableId = tableIdByName.get(tm.name());
            for (ColumnMeta c : tm.columns()) {
                columnRows.add(new Object[]{
                    columnIds[ci++], snapshotId, tableId, connectionId, conn.getName(), conn.getSchema(),
                    tm.name(), c.name(), c.dataType(), c.nullable(), c.defaultValue(), c.comment(), c.ordinal()});
            }
        }
        batchUpdate("INSERT INTO meta_column(id, snapshot_id, table_id, connection_id, connection_name, " +
                "schema_name, table_name, column_name, data_type, nullable, default_value, comment, ordinal) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
            columnRows);

        List<Object[]> constraintRows = new ArrayList<>();
        long[] constraintIds = allocateIds("meta_constraint",
            tables.stream().mapToInt(t -> t.constraints().size()).sum());
        int ri = 0;
        for (TableMeta tm : tables) {
            Long tableId = tableIdByName.get(tm.name());
            for (ConstraintMeta c : tm.constraints()) {
                char code = switch (c.type()) {
                    case PRIMARY -> 'p';
                    case UNIQUE -> 'u';
                    case FOREIGN -> 'f';
                    case CHECK -> 'c';
                };
                constraintRows.add(new Object[]{
                    constraintIds[ri++], snapshotId, tableId, connectionId, conn.getName(), conn.getSchema(),
                    tm.name(), c.name(), String.valueOf(code), c.definition(),
                    c.columns() != null ? String.join(",", c.columns()) : null, c.referencesTable()});
            }
        }
        batchUpdate("INSERT INTO meta_constraint(id, snapshot_id, table_id, connection_id, connection_name, " +
                "schema_name, table_name, constraint_name, type, definition, columns, ref_table) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            constraintRows);

        List<Object[]> indexRows = new ArrayList<>();
        long[] indexIds = allocateIds("meta_index",
            tables.stream().mapToInt(t -> t.indexes().size()).sum());
        int ii = 0;
        for (TableMeta tm : tables) {
            Long tableId = tableIdByName.get(tm.name());
            for (IndexMeta idx : tm.indexes()) {
                indexRows.add(new Object[]{
                    indexIds[ii++], snapshotId, tableId, connectionId, conn.getName(), conn.getSchema(),
                    tm.name(), idx.name(),
                    idx.columns() != null ? String.join(",", idx.columns()) : null,
                    idx.isUnique(), idx.isPartial(), idx.whereClause(), idx.definition()});
            }
        }
        batchUpdate("INSERT INTO meta_index(id, snapshot_id, table_id, connection_id, connection_name, " +
                "schema_name, table_name, index_name, columns, is_unique, is_partial, where_clause, definition) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
            indexRows);

        List<Object[]> partitionRows = new ArrayList<>();
        long[] partitionIds = allocateIds("meta_partition",
            tables.stream().mapToInt(t -> t.partitions().size()).sum());
        int pi = 0;
        for (TableMeta tm : tables) {
            Long tableId = tableIdByName.get(tm.name());
            for (PartitionMeta p : tm.partitions()) {
                partitionRows.add(new Object[]{
                    partitionIds[pi++], snapshotId, tableId, connectionId, conn.getName(), conn.getSchema(),
                    tm.name(), p.name(), p.parentName(), p.ordinal(), p.boundaryExpr(),
                    p.subPartition(), p.tablespace(), p.estimatedRows()});
            }
        }
        batchUpdate("INSERT INTO meta_partition(id, snapshot_id, table_id, connection_id, connection_name, " +
                "schema_name, table_name, partition_name, parent_name, ordinal, boundary_expr, " +
                "is_sub_partition, tablespace, estimated_rows) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            partitionRows);

        if (!sequences.isEmpty()) {
            long[] seqIds = allocateIds("meta_sequence", sequences.size());
            List<Object[]> seqRows = new ArrayList<>(sequences.size());
            for (int i = 0; i < sequences.size(); i++) {
                SequenceMeta sm = sequences.get(i);
                seqRows.add(new Object[]{
                    seqIds[i], snapshotId, connectionId, conn.getName(), conn.getSchema(), sm.name(),
                    sm.dataType(), sm.startValue(), sm.incrementBy(), sm.minValue(),
                    sm.maxValue(), sm.cacheSize(), sm.cycle(), ts});
            }
            batchUpdate("INSERT INTO meta_sequence(id, snapshot_id, connection_id, connection_name, " +
                    "schema_name, sequence_name, data_type, start_value, increment_by, min_value, max_value, " +
                    "cache_size, cycle, collected_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                seqRows);
        }
    }

    /** 将当前元数据整体搬入历史表（服务端 INSERT SELECT，无数据传输），保留原 snapshot_id 与采集时间 */
    private void backupCurrentMetadata(Long connectionId) {
        int tables = copyToHistory("meta_table", connectionId);
        int seqs = copyToHistory("meta_sequence", connectionId);
        int columns = copyToHistory("meta_column", connectionId);
        int constraints = copyToHistory("meta_constraint", connectionId);
        int indexes = copyToHistory("meta_index", connectionId);
        int partitions = copyToHistory("meta_partition", connectionId);
        LOG.info("历史元数据备份完成: connectionId={}, snapshotRows={}", connectionId,
            Map.of("tables", tables, "sequences", seqs, "columns", columns,
                "constraints", constraints, "indexes", indexes, "partitions", partitions));
    }

    /** 历史备份的列清单（与建表列序一致，显式列出避免 ALTER 追加列导致错位） */
    private static final Map<String, String> HISTORY_COLUMNS = Map.of(
        "meta_table", "id, connection_id, connection_name, schema_name, table_name, comment, " +
            "partitioned, partition_strategy, partition_key, partition_count, collected_at",
        "meta_sequence", "id, connection_id, connection_name, schema_name, sequence_name, data_type, " +
            "start_value, increment_by, min_value, max_value, cache_size, cycle, collected_at",
        "meta_column", "id, table_id, connection_id, connection_name, schema_name, table_name, " +
            "column_name, data_type, nullable, default_value, comment, ordinal",
        "meta_constraint", "id, table_id, connection_id, connection_name, schema_name, table_name, " +
            "constraint_name, type, definition, columns, ref_table",
        "meta_index", "id, table_id, connection_id, connection_name, schema_name, table_name, " +
            "index_name, columns, is_unique, is_partial, where_clause, definition",
        "meta_partition", "id, table_id, connection_id, connection_name, schema_name, table_name, " +
            "partition_name, parent_name, ordinal, boundary_expr, is_sub_partition, tablespace, estimated_rows");

    private int copyToHistory(String table, Long connectionId) {
        String cols = HISTORY_COLUMNS.get(table);
        String sql = "INSERT INTO " + table + "_history (snapshot_id, " + cols + ") " +
                     "SELECT snapshot_id, " + cols + " FROM " + table +
                     " WHERE connection_id = ? AND snapshot_id IS NOT NULL";
        return jdbcTemplate.update(sql, connectionId);
    }

    /** 创建新快照；快照的 collected_at 与本次写入的所有明细严格一致 */
    private long createSnapshot(Long connectionId, LocalDateTime collectedAt) {
        Long id = jdbcTemplate.queryForObject(
            "SELECT nextval(pg_get_serial_sequence('meta_snapshot', 'id'))", Long.class);
        jdbcTemplate.update("INSERT INTO meta_snapshot(id, connection_id, collected_at, created_at) " +
                "VALUES (?,?,?,CURRENT_TIMESTAMP)",
            id, connectionId, Timestamp.valueOf(collectedAt));
        LOG.info("创建元数据快照: connectionId={}, snapshotId={}, collectedAt={}",
            connectionId, id, collectedAt);
        return id;
    }

    private void finalizeSnapshot(long snapshotId, int tableCount, int sequenceCount, long durationMs) {
        jdbcTemplate.update(
            "UPDATE meta_snapshot SET table_count=?, sequence_count=?, duration_ms=? WHERE id=?",
            tableCount, sequenceCount, durationMs, snapshotId);
    }

    /** 仅保留每个数据源最近 retention 个快照，更早的快照及其历史明细一并删除（0=永久保留） */
    private void cleanupExpiredSnapshots(Long connectionId, int retention) {
        List<Long> expired = jdbcTemplate.queryForList(
            "SELECT id FROM meta_snapshot WHERE connection_id=? AND id NOT IN " +
            "(SELECT id FROM meta_snapshot WHERE connection_id=? ORDER BY collected_at DESC, id DESC LIMIT ?) " +
            "ORDER BY id", Long.class, connectionId, connectionId, retention);
        if (expired.isEmpty()) return;
        String idList = expired.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
        for (String table : new String[]{"meta_table", "meta_column", "meta_constraint",
                "meta_index", "meta_partition", "meta_sequence"}) {
            jdbcTemplate.update("DELETE FROM " + table + "_history WHERE snapshot_id IN (" + idList + ")");
        }
        jdbcTemplate.update("DELETE FROM meta_snapshot WHERE id IN (" + idList + ")");
        LOG.info("清理过期元数据快照: connectionId={}, removed={}, retention={}",
            connectionId, expired.size(), retention);
    }

    /** 一次性从序列批量分配 count 个主键，保持 BIGSERIAL 序列语义 */
    private long[] allocateIds(String tableName, int count) {
        if (count <= 0) return new long[0];
        List<Long> ids = jdbcTemplate.queryForList(
            "SELECT nextval(pg_get_serial_sequence(?, 'id')) FROM generate_series(1, ?)",
            Long.class, tableName, count);
        long[] result = new long[ids.size()];
        for (int i = 0; i < ids.size(); i++) result[i] = ids.get(i);
        return result;
    }

    private void batchUpdate(String sql, List<Object[]> rows) {
        for (int i = 0; i < rows.size(); i += JDBC_BATCH_SIZE) {
            jdbcTemplate.batchUpdate(sql, rows.subList(i, Math.min(i + JDBC_BATCH_SIZE, rows.size())));
        }
    }

    private void deleteOldMetadata(Long connectionId) {
        partitionRepo.deleteByConnectionId(connectionId);
        indexRepo.deleteByConnectionId(connectionId);
        constraintRepo.deleteByConnectionId(connectionId);
        columnRepo.deleteByConnectionId(connectionId);
        tableRepo.deleteByConnectionId(connectionId);
        sequenceRepo.deleteByConnectionId(connectionId);
    }
}
