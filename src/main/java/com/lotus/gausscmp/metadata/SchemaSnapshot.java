package com.lotus.gausscmp.metadata;

import java.util.List;

public record SchemaSnapshot(String schemaName, List<TableMeta> tables) {}
