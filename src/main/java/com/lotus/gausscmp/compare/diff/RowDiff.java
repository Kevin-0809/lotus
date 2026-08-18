package com.lotus.gausscmp.compare.diff;

import java.util.List;
import java.util.Map;

public record RowDiff(RowDiffType type, Map<String, Object> keyValues,
                      Map<String, Object> sourceValues, Map<String, Object> targetValues,
                      List<String> mismatchColumns) {}
