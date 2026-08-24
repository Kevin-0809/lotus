package com.lotus.gausscmp.config;

import java.util.List;

public record OptionsConfig(int parallelism, int chunkSize, boolean drillDown,
                            String checksumFunction, TableFilterConfig tableFilter,
                            String syncDirection, int maxDisplayRows,
                            long tableTimeoutSeconds, List<String> dataCompareTables) {
    public OptionsConfig {
        dataCompareTables = dataCompareTables == null ? List.of() : List.copyOf(dataCompareTables);
        tableTimeoutSeconds = Math.max(0, tableTimeoutSeconds);
    }
}
