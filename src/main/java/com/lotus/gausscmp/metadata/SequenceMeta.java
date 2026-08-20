package com.lotus.gausscmp.metadata;

public record SequenceMeta(String name, String dataType,
                           long startValue, long incrementBy,
                           long minValue, long maxValue,
                           long cacheSize, boolean cycle) {}
