package com.lotus.gausscmp.compare.diff;

public record IndexDiff(DiffType type, String indexName, String sourceDef, String targetDef) {}
