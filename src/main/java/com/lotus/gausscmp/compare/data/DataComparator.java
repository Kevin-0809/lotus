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
            if (sCount == 0 && tCount == 0) {
                return new TableDataDiff(table, keyColumns, TableDataStatus.CONSISTENT, 0, 0,
                    new ChunkStats(0, 0, 0), List.of(), null);
            }
            Chunker chunker = new Chunker(chunkSize);
            int maxCount = (int) Math.max(sCount, tCount);
            List<Chunker.Chunk> chunks = chunker.plan(maxCount);
            var calc = new ChecksumCalculator(hashFunction, chunkSize);
            int consistent = 0, mismatched = 0;
            List<Chunker.Chunk> mismatchChunks = new ArrayList<>();
            for (Chunker.Chunk chunk : chunks) {
                var sRes = calc.calculateChunk(sConn, sourceConfig.schema(), table, keyColumns, chunk.offset(), chunk.limit());
                var tRes = calc.calculateChunk(tConn, targetConfig.schema(), table, keyColumns, chunk.offset(), chunk.limit());
                if (Objects.equals(sRes.checksum(), tRes.checksum()) && sRes.rowCount() == tRes.rowCount()) {
                    consistent++;
                } else {
                    mismatched++;
                    mismatchChunks.add(chunk);
                }
            }
            if (mismatched == 0) {
                return new TableDataDiff(table, keyColumns, TableDataStatus.CONSISTENT, sCount, tCount,
                    new ChunkStats(chunks.size(), consistent, mismatched), List.of(), null);
            }
            List<RowDiff> rowDiffs = new ArrayList<>();
            if (drillDown) {
                var drill = new DrillDownComparator(keyColumns);
                for (Chunker.Chunk chunk : mismatchChunks) {
                    Map<String, Map<String, Object>> sRows = fetchRows(sConn, sourceConfig.schema(), table, keyColumns, chunk);
                    Map<String, Map<String, Object>> tRows = fetchRows(tConn, targetConfig.schema(), table, keyColumns, chunk);
                    rowDiffs.addAll(drill.compare(sRows, tRows));
                    if (rowDiffs.size() >= maxDisplayRows) break;
                }
            }
            return new TableDataDiff(table, keyColumns, TableDataStatus.DIFFERENT, sCount, tCount,
                new ChunkStats(chunks.size(), consistent, mismatched), rowDiffs, null);
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

    private Map<String, Map<String, Object>> fetchRows(Connection conn, String schema, String table,
            List<String> keyColumns, Chunker.Chunk chunk) throws SQLException {
        String keys = String.join(", ", keyColumns);
        String sql = "SELECT * FROM \"" + schema + "\".\"" + table + "\" ORDER BY " + keys +
                     " LIMIT " + chunk.limit() + " OFFSET " + chunk.offset();
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setFetchSize(1000);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    StringBuilder keyBuilder = new StringBuilder();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        String col = meta.getColumnName(i);
                        Object val = rs.getObject(i);
                        row.put(col, val);
                    }
                    for (String k : keyColumns) {
                        if (keyBuilder.length() > 0) keyBuilder.append('\u0001');
                        keyBuilder.append(row.get(k));
                    }
                    rows.put(keyBuilder.toString(), row);
                }
            }
        }
        return rows;
    }
}
