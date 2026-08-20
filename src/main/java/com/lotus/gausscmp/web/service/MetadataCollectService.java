package com.lotus.gausscmp.web.service;

import com.lotus.gausscmp.metadata.*;
import com.lotus.gausscmp.web.entity.*;
import com.lotus.gausscmp.web.repository.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class MetadataCollectService {

    private final ConnectionRepository connectionRepo;
    private final MetaTableRepository tableRepo;
    private final MetaColumnRepository columnRepo;
    private final MetaConstraintRepository constraintRepo;
    private final MetaIndexRepository indexRepo;
    private final MetaSequenceRepository sequenceRepo;
    private final MetaPartitionRepository partitionRepo;

    public MetadataCollectService(ConnectionRepository connectionRepo,
                                  MetaTableRepository tableRepo,
                                  MetaColumnRepository columnRepo,
                                  MetaConstraintRepository constraintRepo,
                                  MetaIndexRepository indexRepo,
                                  MetaSequenceRepository sequenceRepo,
                                  MetaPartitionRepository partitionRepo) {
        this.connectionRepo = connectionRepo;
        this.tableRepo = tableRepo;
        this.columnRepo = columnRepo;
        this.constraintRepo = constraintRepo;
        this.indexRepo = indexRepo;
        this.sequenceRepo = sequenceRepo;
        this.partitionRepo = partitionRepo;
    }

    public static class CollectResult {
        public final int tableCount;
        public final int sequenceCount;
        public final long durationMs;
        public CollectResult(int t, int s, long d) { tableCount = t; sequenceCount = s; durationMs = d; }
    }

    @Transactional
    public CollectResult collect(Long connectionId) throws Exception {
        DbConnection conn = connectionRepo.findById(connectionId)
            .orElseThrow(() -> new NoSuchElementException("数据源不存在: " + connectionId));

        long start = System.currentTimeMillis();
        HikariDataSource ds = null;
        try {
            HikariConfig hc = new HikariConfig();
            hc.setJdbcUrl(conn.getUrl());
            hc.setUsername(conn.getUsername());
            hc.setPassword(conn.getPassword());
            hc.setMaximumPoolSize(3);
            hc.setConnectionTimeout(30000);
            hc.setReadOnly(true);
            ds = new HikariDataSource(hc);

            try (Connection jdbc = ds.getConnection()) {
                deleteOldMetadata(connectionId);

                MetadataReader reader = new OpenGaussMetadataReader();
                SchemaSnapshot snapshot = reader.read(jdbc, conn.getSchema(), List.of(), List.of());

                Map<String, Long> tableNameToId = new LinkedHashMap<>();
                for (TableMeta tm : snapshot.tables()) {
                    MetaTable mt = new MetaTable();
                    mt.setConnectionId(connectionId);
                    mt.setSchemaName(conn.getSchema());
                    mt.setTableName(tm.name());
                    mt.setComment(tm.comment());
                    mt.setPartitioned(tm.partitioned());
                    mt.setPartitionStrategy(tm.partitionStrategy());
                    mt.setPartitionKey(tm.partitionKey());
                    mt.setCollectedAt(LocalDateTime.now());
                    mt = tableRepo.save(mt);
                    tableNameToId.put(tm.name(), mt.getId());

                    saveColumns(mt.getId(), tm.columns());
                    saveConstraints(mt.getId(), tm.constraints());
                    saveIndexes(mt.getId(), tm.indexes());
                    savePartitions(mt.getId(), tm.partitions());
                }

                List<SequenceMeta> sequences = snapshot.sequences();
                for (SequenceMeta sm : sequences) {
                    MetaSequence ms = new MetaSequence();
                    ms.setConnectionId(connectionId);
                    ms.setSchemaName(conn.getSchema());
                    ms.setSequenceName(sm.name());
                    ms.setDataType(sm.dataType());
                    ms.setStartValue(sm.startValue());
                    ms.setIncrementBy(sm.incrementBy());
                    ms.setMinValue(sm.minValue());
                    ms.setMaxValue(sm.maxValue());
                    ms.setCacheSize(sm.cacheSize());
                    ms.setCycle(sm.cycle());
                    ms.setCollectedAt(LocalDateTime.now());
                    sequenceRepo.save(ms);
                }

                conn.setCollectedAt(LocalDateTime.now());
                connectionRepo.save(conn);

                int tableCount = snapshot.tables().size();
                int seqCount = sequences.size();
                long duration = System.currentTimeMillis() - start;
                return new CollectResult(tableCount, seqCount, duration);
            }
        } finally {
            if (ds != null) ds.close();
        }
    }

    private void deleteOldMetadata(Long connectionId) {
        partitionRepo.deleteByConnectionId(connectionId);
        indexRepo.deleteByConnectionId(connectionId);
        constraintRepo.deleteByConnectionId(connectionId);
        columnRepo.deleteByConnectionId(connectionId);
        tableRepo.deleteByConnectionId(connectionId);
        sequenceRepo.deleteByConnectionId(connectionId);
        tableRepo.flush();
    }

    private void saveColumns(Long tableId, List<ColumnMeta> columns) {
        for (ColumnMeta c : columns) {
            MetaColumn mc = new MetaColumn();
            mc.setTableId(tableId);
            mc.setColumnName(c.name());
            mc.setDataType(c.dataType());
            mc.setNullable(c.nullable());
            mc.setDefaultValue(c.defaultValue());
            mc.setComment(c.comment());
            mc.setOrdinal(c.ordinal());
            columnRepo.save(mc);
        }
    }

    private void saveConstraints(Long tableId, List<ConstraintMeta> constraints) {
        for (ConstraintMeta c : constraints) {
            MetaConstraint mc = new MetaConstraint();
            mc.setTableId(tableId);
            mc.setConstraintName(c.name());
            char code = switch (c.type()) {
                case PRIMARY -> 'p';
                case UNIQUE -> 'u';
                case FOREIGN -> 'f';
                case CHECK -> 'c';
            };
            mc.setType(String.valueOf(code));
            mc.setDefinition(c.definition());
            mc.setColumns(c.columns() != null ? String.join(",", c.columns()) : null);
            mc.setRefTable(c.referencesTable());
            constraintRepo.save(mc);
        }
    }

    private void saveIndexes(Long tableId, List<IndexMeta> indexes) {
        for (IndexMeta i : indexes) {
            MetaIndex mi = new MetaIndex();
            mi.setTableId(tableId);
            mi.setIndexName(i.name());
            mi.setColumns(i.columns() != null ? String.join(",", i.columns()) : null);
            mi.setUnique(i.isUnique());
            mi.setPartial(i.isPartial());
            mi.setWhereClause(i.whereClause());
            mi.setDefinition(i.definition());
            indexRepo.save(mi);
        }
    }

    private void savePartitions(Long tableId, List<PartitionMeta> partitions) {
        for (PartitionMeta p : partitions) {
            MetaPartition mp = new MetaPartition();
            mp.setTableId(tableId);
            mp.setPartitionName(p.name());
            mp.setParentName(p.parentName());
            mp.setOrdinal(p.ordinal());
            mp.setBoundaryExpr(p.boundaryExpr());
            mp.setSubPartition(p.subPartition());
            mp.setTablespace(p.tablespace());
            mp.setEstimatedRows(p.estimatedRows());
            partitionRepo.save(mp);
        }
    }
}
