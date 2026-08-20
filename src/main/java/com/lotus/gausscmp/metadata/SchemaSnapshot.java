package com.lotus.gausscmp.metadata;

import java.util.List;

public record SchemaSnapshot(String schemaName, List<TableMeta> tables,
                             List<SequenceMeta> sequences) {
    public SchemaSnapshot(String schemaName, List<TableMeta> tables) {
        this(schemaName, tables, List.of());
    }
}
