package com.lotus.gausscmp.web.service;

import com.lotus.gausscmp.metadata.*;
import com.lotus.gausscmp.web.entity.*;
import com.lotus.gausscmp.web.repository.*;
import com.lotus.gausscmp.web.entity.DbConnection;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class MetadataStoreService {

    private final ConnectionRepository connectionRepo;
    private final MetaTableRepository tableRepo;
    private final MetaColumnRepository columnRepo;
    private final MetaConstraintRepository constraintRepo;
    private final MetaIndexRepository indexRepo;
    private final MetaSequenceRepository sequenceRepo;
    private final MetaPartitionRepository partitionRepo;

    public MetadataStoreService(ConnectionRepository connectionRepo,
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

    public SchemaSnapshot loadSnapshot(Long connectionId) {
        DbConnection conn = connectionRepo.findById(connectionId)
            .orElseThrow(() -> new NoSuchElementException("数据源不存在: " + connectionId));
        if (conn.getCollectedAt() == null) {
            throw new IllegalStateException("数据源尚未采集元数据: " + conn.getName());
        }
        return loadSnapshot(connectionId, conn.getSchema());
    }

    public SchemaSnapshot loadSnapshot(Long connectionId, String schemaName) {
        List<MetaTable> metaTables = tableRepo.findByConnectionIdOrderByTableNameAsc(connectionId);
        List<MetaSequence> metaSeqs = sequenceRepo.findByConnectionIdOrderBySequenceNameAsc(connectionId);

        List<TableMeta> tables = new ArrayList<>();
        for (MetaTable mt : metaTables) {
            tables.add(buildTableMeta(mt));
        }
        List<SequenceMeta> sequences = metaSeqs.stream()
            .map(this::buildSequenceMeta)
            .collect(Collectors.toList());
        return new SchemaSnapshot(schemaName, tables, sequences);
    }

    private TableMeta buildTableMeta(MetaTable mt) {
        List<ColumnMeta> columns = columnRepo.findByTableIdOrderByOrdinalAsc(mt.getId()).stream()
            .map(c -> new ColumnMeta(c.getColumnName(), c.getDataType(), c.isNullable(),
                c.getDefaultValue(), c.getComment(), c.getOrdinal()))
            .collect(Collectors.toList());
        List<ConstraintMeta> constraints = constraintRepo.findByTableId(mt.getId()).stream()
            .map(c -> new ConstraintMeta(c.getConstraintName(), parseType(c.getType()),
                c.getDefinition(), parseList(c.getColumns()), c.getRefTable()))
            .collect(Collectors.toList());
        List<IndexMeta> indexes = indexRepo.findByTableId(mt.getId()).stream()
            .map(i -> new IndexMeta(i.getIndexName(), mt.getTableName(), parseList(i.getColumns()),
                i.isUnique(), i.isPartial(), i.getWhereClause(), i.getDefinition()))
            .collect(Collectors.toList());
        List<PartitionMeta> partitions = partitionRepo.findByTableIdOrderByOrdinalAsc(mt.getId()).stream()
            .map(p -> new PartitionMeta(p.getPartitionName(), p.getParentName(), p.getOrdinal(),
                p.getBoundaryExpr(), p.isSubPartition(), p.getTablespace(), p.getEstimatedRows() != null ? p.getEstimatedRows() : 0))
            .collect(Collectors.toList());
        return new TableMeta(mt.getTableName(), mt.getComment(), columns, constraints, indexes,
            mt.isPartitioned(), mt.getPartitionStrategy(), mt.getPartitionKey(), partitions);
    }

    private SequenceMeta buildSequenceMeta(MetaSequence ms) {
        return new SequenceMeta(ms.getSequenceName(), ms.getDataType(),
            ms.getStartValue() != null ? ms.getStartValue() : 0,
            ms.getIncrementBy() != null ? ms.getIncrementBy() : 1,
            ms.getMinValue() != null ? ms.getMinValue() : 0,
            ms.getMaxValue() != null ? ms.getMaxValue() : 0,
            ms.getCacheSize() != null ? ms.getCacheSize() : 1,
            ms.isCycle());
    }

    private static ConstraintType parseType(String code) {
        if (code == null || code.isEmpty()) return ConstraintType.CHECK;
        return switch (code.charAt(0)) {
            case 'p' -> ConstraintType.PRIMARY;
            case 'u' -> ConstraintType.UNIQUE;
            case 'f' -> ConstraintType.FOREIGN;
            default -> ConstraintType.CHECK;
        };
    }

    private static List<String> parseList(String s) {
        if (s == null || s.isBlank()) return List.of();
        return Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }
}
