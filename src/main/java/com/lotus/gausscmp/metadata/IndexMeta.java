package com.lotus.gausscmp.metadata;

import java.util.List;

public record IndexMeta(String name, String tableName, List<String> columns,
                        boolean isUnique, boolean isPartial, String whereClause, String definition,
                        boolean usable) {

    /** 兼容旧调用：未采集可用性时按可用处理 */
    public IndexMeta(String name, String tableName, List<String> columns,
                     boolean isUnique, boolean isPartial, String whereClause, String definition) {
        this(name, tableName, columns, isUnique, isPartial, whereClause, definition, true);
    }
}
