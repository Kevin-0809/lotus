package com.lotus.gausscmp.metadata;

public record ColumnMeta(String name, String dataType, boolean nullable,
                         String defaultValue, String comment, int ordinal) {}
