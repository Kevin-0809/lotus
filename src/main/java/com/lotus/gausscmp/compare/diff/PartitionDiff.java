package com.lotus.gausscmp.compare.diff;

public record PartitionDiff(DiffType type, String partitionName,
                            String sourceBoundary, String targetBoundary) {}
