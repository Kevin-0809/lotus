package com.lotus.gausscmp.compare.diff;

import java.util.List;

public record StructureDiffResult(List<TableStructureDiff> tableDiffs,
                                  List<SequenceDiff> sequenceDiffs) {
    public StructureDiffResult(List<TableStructureDiff> tableDiffs) {
        this(tableDiffs, List.of());
    }
}
