package com.lotus.gausscmp.compare.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Set;

public final class ChecksumCalculator {
    public static final Set<String> ALLOWED_HASH_FUNCTIONS = Set.of("md5", "sha256");

    public record ChunkResult(int offset, int limit, long rowCount, String checksum) {}

    private final String hashFunction;
    private final int chunkSize;

    public ChecksumCalculator(String hashFunction, int chunkSize) {
        String normalized = hashFunction == null ? "md5" : hashFunction.trim().toLowerCase();
        if (!ALLOWED_HASH_FUNCTIONS.contains(normalized)) {
            throw new IllegalArgumentException(
                "不支持的校验和函数: " + hashFunction + "，仅支持: " + String.join("/", ALLOWED_HASH_FUNCTIONS));
        }
        this.hashFunction = normalized;
        this.chunkSize = chunkSize;
    }

    public ChunkResult calculate(Connection conn, String schema, String table, List<String> keyColumns) {
        return calculateChunk(conn, schema, table, keyColumns, 0, chunkSize);
    }

    public ChunkResult calculateChunk(Connection conn, String schema, String table,
                                       List<String> keyColumns, int offset, int limit) {
        String keys = String.join(", ", keyColumns);
        String sql = String.format(
            "SELECT count(*) AS cnt, %s(string_agg(%s(t::text), ',' ORDER BY %s)) AS chk " +
            "FROM (SELECT * FROM \"%s\".\"%s\" ORDER BY %s LIMIT %d OFFSET %d) t",
            hashFunction, hashFunction, keys, schema, table, keys, limit, offset);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new ChunkResult(offset, limit, rs.getLong("cnt"), rs.getString("chk"));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("块校验和计算失败: " + schema + "." + table + " offset=" + offset, e);
        }
        return new ChunkResult(offset, limit, 0, null);
    }
}
