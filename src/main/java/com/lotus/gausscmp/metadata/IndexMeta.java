package com.lotus.gausscmp.metadata;

import java.util.List;

public record IndexMeta(String name, String tableName, List<String> columns,
                        boolean isUnique, boolean isPartial, String whereClause, String definition) {}
