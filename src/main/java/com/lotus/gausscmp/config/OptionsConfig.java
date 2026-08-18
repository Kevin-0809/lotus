package com.lotus.gausscmp.config;

import java.util.List;

public record OptionsConfig(int parallelism, int chunkSize, boolean drillDown,
                            String checksumFunction, TableFilterConfig tableFilter,
                            OutputConfig output, String syncDirection, int maxDisplayRows,
                            long tableTimeout, List<String> dataCompareTables) {
    public OptionsConfig {
        dataCompareTables = dataCompareTables == null ? List.of() : List.copyOf(dataCompareTables);
    }
}
