package com.lotus.gausscmp.compare.data;

import com.lotus.gausscmp.compare.diff.*;
import com.lotus.gausscmp.config.SourceConfig;
import java.sql.*;
import java.util.*;

public final class DataComparator {
    private final SourceConfig sourceConfig;
    private final SourceConfig targetConfig;
    private final int chunkSize;
    private final String hashFunction;
    private final boolean drillDown;
    private final int maxDisplayRows;

    public DataComparator(SourceConfig sourceConfig, SourceConfig targetConfig, int chunkSize,
                          String hashFunction, boolean drillDown, int maxDisplayRows) {
        this.sourceConfig = sourceConfig;
        this.targetConfig = targetConfig;
        this.chunkSize = chunkSize;
        this.hashFunction = hashFunction;
        this.drillDown = drillDown;
        this.maxDisplayRows = maxDisplayRows;
    }

    public TableDataDiff compareTable(Connection sConn, Connection tConn, String table,
                                      List<String> keyColumns, TableDataStatus structureStatus) {
        if (structureStatus != TableDataStatus.CONSISTENT) {
            return new TableDataDiff(table, keyColumns, TableDataStatus.SKIPPED, 0, 0,
                new ChunkStats(0, 0, 0), List.of(), "结构不一致，跳过数据比对");
        }
        try {
            long sCount = rowCount(sConn, sourceConfig.schema(), table);
            long tCount = rowCount(tConn, targetConfig.schema(), table);
            TableDataStatus status = sCount == tCount ? TableDataStatus.CONSISTENT : TableDataStatus.DIFFERENT;
            ChunkStats stats = new ChunkStats(1, status == TableDataStatus.CONSISTENT ? 1 : 0,
                status == TableDataStatus.DIFFERENT ? 1 : 0);
            return new TableDataDiff(table, keyColumns, status, sCount, tCount, stats, List.of(), null);
        } catch (Exception e) {
            throw new RuntimeException("数据比对失败: " + table, e);
        }
    }

    private long rowCount(Connection conn, String schema, String table) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT count(*) FROM \"" + schema + "\".\"" + table + "\"")) {
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getLong(1); }
        }
        return 0;
    }

}
