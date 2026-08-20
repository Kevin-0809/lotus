package com.lotus.gausscmp.compare.diff;

public record SequenceDiff(DiffType type, String sequenceName,
                           String sourceDef, String targetDef,
                           String mismatchField) {}
