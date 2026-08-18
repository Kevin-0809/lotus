package com.lotus.gausscmp.compare.diff;

import java.util.List;

public record TableDataDiff(String tableName, List<String> keyColumns,
                            TableDataStatus status, long sourceRowCount, long targetRowCount,
                            ChunkStats chunkStats, List<RowDiff> rowDiffs, String skippedReason) {}
