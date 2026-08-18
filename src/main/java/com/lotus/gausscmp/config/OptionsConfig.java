package com.lotus.gausscmp.config;

public record OptionsConfig(int parallelism, int chunkSize, boolean drillDown,
                            String checksumFunction, TableFilterConfig tableFilter,
                            OutputConfig output, String syncDirection, int maxDisplayRows,
                            long tableTimeout) {}
