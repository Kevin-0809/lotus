package com.lotus.gausscmp.report;

import com.lotus.gausscmp.compare.diff.DataDiffResult;
import com.lotus.gausscmp.compare.diff.StructureDiffResult;

public record ReportModel(
    String sourceSchema, String targetSchema,
    long structureConsistent, long structureDifferent,
    long dataConsistent, long dataDifferent, long dataSkipped,
    StructureDiffResult structureDiff,
    DataDiffResult dataDiff
) {}
