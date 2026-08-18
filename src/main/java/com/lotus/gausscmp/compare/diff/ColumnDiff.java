package com.lotus.gausscmp.compare.diff;

public record ColumnDiff(DiffType type, String columnName,
                         String sourceValue, String targetValue, String field) {}
