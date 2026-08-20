package com.lotus.gausscmp.web;

import java.util.List;

public record CompareRequest(
    Long sourceConnectionId,
    Long targetConnectionId,
    Integer parallelism, Integer chunkSize, Boolean drillDown,
    String checksumFunction, String syncDirection, Integer maxDisplayRows,
    List<String> includeTables, List<String> excludeTables,
    List<String> dataCompareTables, Boolean generateDdl, Boolean generateDml
) {}
