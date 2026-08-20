package com.lotus.gausscmp.metadata;

public record PartitionMeta(String name, String parentName, int ordinal,
                            String boundaryExpr, boolean subPartition,
                            String tablespace, long estimatedRows) {}
