package com.lotus.gausscmp.compare.diff;

public record ConstraintDiff(DiffType type, String constraintName,
                             String sourceDef, String targetDef) {}
