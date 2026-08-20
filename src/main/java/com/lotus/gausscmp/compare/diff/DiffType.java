package com.lotus.gausscmp.compare.diff;

public enum DiffType {
    TABLE_MISSING_IN_TARGET("目标库缺失表"),
    TABLE_EXTRA_IN_TARGET("目标库多余表"),
    COLUMN_MISSING_IN_TARGET("目标库缺失字段"),
    COLUMN_EXTRA_IN_TARGET("目标库多余字段"),
    COLUMN_MISMATCH("字段不一致"),
    CONSTRAINT_MISSING_IN_TARGET("目标库缺失约束"),
    CONSTRAINT_EXTRA_IN_TARGET("目标库多余约束"),
    CONSTRAINT_MISMATCH("约束不一致"),
    INDEX_MISSING_IN_TARGET("目标库缺失索引"),
    INDEX_EXTRA_IN_TARGET("目标库多余索引"),
    INDEX_MISMATCH("索引不一致"),
    TABLE_COMMENT_MISMATCH("表注释不一致"),
    SEQUENCE_MISSING_IN_TARGET("目标库缺失序列"),
    SEQUENCE_EXTRA_IN_TARGET("目标库多余序列"),
    SEQUENCE_MISMATCH("序列不一致"),
    PARTITION_MISSING_IN_TARGET("目标库缺失分区"),
    PARTITION_EXTRA_IN_TARGET("目标库多余分区"),
    PARTITION_MISMATCH("分区不一致"),
    PARTITION_STRATEGY_MISMATCH("分区策略不一致");

    private final String label;

    DiffType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
