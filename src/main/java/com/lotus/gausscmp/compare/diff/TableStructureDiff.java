package com.lotus.gausscmp.compare.diff;

import java.util.List;
import java.util.Optional;

public record TableStructureDiff(String tableName, boolean existsInSource, boolean existsInTarget,
                                 TableStructureStatus status, List<ColumnDiff> columnDiffs,
                                 List<ConstraintDiff> constraintDiffs, List<IndexDiff> indexDiffs,
                                 List<PartitionDiff> partitionDiffs,
                                 Optional<String> commentDiff) {

    public TableStructureDiff(String tableName, boolean existsInSource, boolean existsInTarget,
                             TableStructureStatus status, List<ColumnDiff> columnDiffs,
                             List<ConstraintDiff> constraintDiffs, List<IndexDiff> indexDiffs,
                             Optional<String> commentDiff) {
        this(tableName, existsInSource, existsInTarget, status, columnDiffs,
             constraintDiffs, indexDiffs, List.of(), commentDiff);
    }
}
