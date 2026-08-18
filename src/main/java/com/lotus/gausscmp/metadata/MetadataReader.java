package com.lotus.gausscmp.metadata;

import java.sql.Connection;
import java.util.List;

public interface MetadataReader {
    SchemaSnapshot read(Connection conn, String schema, List<String> includePatterns, List<String> excludePatterns);
}
