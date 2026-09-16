package com.lotus.gausscmp.compare.diff;

public record IndexDiff(DiffType type, String indexName, String sourceDef, String targetDef, String note) {

    /** 兼容仅携带定义差异的调用 */
    public IndexDiff(DiffType type, String indexName, String sourceDef, String targetDef) {
        this(type, indexName, sourceDef, targetDef, null);
    }
}
