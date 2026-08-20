package com.lotus.gausscmp.metadata;

import java.util.List;
import java.util.Optional;

public record TableMeta(String name, String comment, List<ColumnMeta> columns,
                        List<ConstraintMeta> constraints, List<IndexMeta> indexes,
                        boolean partitioned,
                        String partitionStrategy, String partitionKey,
                        List<PartitionMeta> partitions) {

    public TableMeta(String name, String comment, List<ColumnMeta> columns,
                     List<ConstraintMeta> constraints, List<IndexMeta> indexes,
                     boolean partitioned) {
        this(name, comment, columns, constraints, indexes, partitioned, null, null, List.of());
    }

    public Optional<ConstraintMeta> primaryKey() {
        return constraints.stream()
            .filter(c -> c.type() == ConstraintType.PRIMARY)
            .findFirst();
    }

    public boolean hasPrimaryKeyOrUnique() {
        if (primaryKey().isPresent()) return true;
        return constraints.stream().anyMatch(c -> c.type() == ConstraintType.UNIQUE)
            || indexes.stream().anyMatch(IndexMeta::isUnique);
    }
}
