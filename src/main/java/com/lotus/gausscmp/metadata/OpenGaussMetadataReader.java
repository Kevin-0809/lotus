package com.lotus.gausscmp.metadata;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class OpenGaussMetadataReader implements MetadataReader {

    /** 批量读取进度回调（用于采集进度展示） */
    public interface BatchProgressListener {
        default void onTableCount(int count) {}
        default void onTableDone(String tableName) {}
    }

    @Override
    public SchemaSnapshot read(Connection conn, String schema,
                               List<String> includePatterns, List<String> excludePatterns) {
        List<TableMeta> tables = new ArrayList<>();
        List<String> tableNames = readTableNames(conn, schema);
        for (String name : tableNames) {
            if (!matchesFilters(name, includePatterns, excludePatterns)) continue;
            try {
                tables.add(readTable(conn, schema, name));
            } catch (Exception e) {
                throw new RuntimeException("抽取表元数据失败: " + schema + "." + name, e);
            }
        }
        List<SequenceMeta> sequences = readSequences(conn, schema);
        return new SchemaSnapshot(schema, tables, sequences);
    }

    public List<SequenceMeta> readSequences(Connection conn, String schema) {
        return readSequencesInternal(conn, schema);
    }

    public List<String> readTableNames(Connection conn, String schema) {
        String sql = """
            SELECT c.relname FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relkind IN ('r', 'p')
              AND NOT EXISTS (SELECT 1 FROM pg_inherits ih WHERE ih.inhrelid = c.oid)
            ORDER BY c.relname
            """;
        List<String> names = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) names.add(rs.getString("relname"));
            }
        } catch (Exception e) {
            throw new RuntimeException("读取表名失败: " + schema, e);
        }
        return names;
    }

    /**
     * 整个 schema 一次性批量读取全部表元数据。
     * 仅执行 6 条 SQL（表清单/列/约束/索引/分区定义/分区明细），替代逐表查询的 1000×6 次往返。
     */
    public List<TableMeta> readAllTables(Connection conn, String schema) throws Exception {
        return readAllTables(conn, schema, null);
    }

    public List<TableMeta> readAllTables(Connection conn, String schema, BatchProgressListener progress) throws Exception {
        record TableHeader(String name, String comment, boolean partitioned) {}
        Map<String, TableHeader> headers = new LinkedHashMap<>();
        String tablesSql = """
            SELECT c.relname, obj_description(c.oid, 'pg_class') AS cmt,
                   EXISTS(SELECT 1 FROM pg_partition p WHERE p.parentid = c.oid AND p.parttype = 'r') AS ispart
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relkind IN ('r', 'p')
              AND NOT EXISTS (SELECT 1 FROM pg_inherits ih WHERE ih.inhrelid = c.oid)
            ORDER BY c.relname
            """;
        try (PreparedStatement ps = conn.prepareStatement(tablesSql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("relname");
                    if (name != null && !name.isBlank()) {
                        headers.put(name, new TableHeader(name, rs.getString("cmt"), rs.getBoolean("ispart")));
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("批量读取表清单失败: " + schema, e);
        }
        if (progress != null) progress.onTableCount(headers.size());

        Map<String, List<ColumnMeta>> columnsByTable = new HashMap<>();
        String columnsSql = """
            SELECT c.relname AS tablename, a.attname, format_type(a.atttypid, a.atttypmod) AS type,
                   a.attnotnull, pg_get_expr(d.adbin, d.adrelid) AS defaultval,
                   col_description(a.attrelid, a.attnum) AS colcomment, a.attnum
            FROM pg_attribute a
            JOIN pg_class c ON c.oid = a.attrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
            WHERE n.nspname = ? AND c.relkind IN ('r', 'p')
              AND NOT EXISTS (SELECT 1 FROM pg_inherits ih WHERE ih.inhrelid = c.oid)
              AND a.attnum > 0 AND NOT a.attisdropped
            ORDER BY c.relname, a.attnum
            """;
        try (PreparedStatement ps = conn.prepareStatement(columnsSql)) {
            ps.setFetchSize(2000);
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columnsByTable.computeIfAbsent(rs.getString("tablename"), k -> new ArrayList<>())
                        .add(new ColumnMeta(
                            rs.getString("attname"),
                            TypeNormalizer.normalize(rs.getString("type")),
                            !rs.getBoolean("attnotnull"),
                            DefinitionNormalizer.normalizeDefaultValue(rs.getString("defaultval")),
                            rs.getString("colcomment"),
                            rs.getInt("attnum")));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("批量读取字段元数据失败: " + schema, e);
        }

        Map<String, List<ConstraintMeta>> constraintsByTable = new HashMap<>();
        String constraintsSql = """
            SELECT c.relname AS tablename, con.conname, con.contype, pg_get_constraintdef(con.oid) AS def,
                   NULLIF(con.confrelid, 0)::regclass AS reftable,
                   (SELECT string_agg(a.attname, ',' ORDER BY k.ord)
                    FROM generate_subscripts(con.conkey, 1) AS k(ord)
                    JOIN pg_attribute a ON a.attrelid = con.conrelid
                        AND a.attnum = con.conkey[k.ord]) AS colnames
            FROM pg_constraint con
            JOIN pg_class c ON c.oid = con.conrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ?
            ORDER BY c.relname, con.conname
            """;
        try (PreparedStatement ps = conn.prepareStatement(constraintsSql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    char ct = rs.getString("contype").charAt(0);
                    ConstraintType type = switch (ct) {
                        case 'p' -> ConstraintType.PRIMARY;
                        case 'u' -> ConstraintType.UNIQUE;
                        case 'f' -> ConstraintType.FOREIGN;
                        case 'c' -> ConstraintType.CHECK;
                        default -> throw new IllegalStateException("未知约束类型: " + ct);
                    };
                    constraintsByTable.computeIfAbsent(rs.getString("tablename"), k -> new ArrayList<>())
                        .add(new ConstraintMeta(
                            rs.getString("conname"),
                            type,
                            DefinitionNormalizer.normalize(rs.getString("def"), schema),
                            parseColumnNames(rs.getString("colnames")),
                            DefinitionNormalizer.stripSchemaPrefix(rs.getString("reftable"), schema)));
                }
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("批量读取约束元数据失败: " + schema, e);
        }

        Map<String, List<IndexMeta>> indexesByTable = new HashMap<>();
        String indexesSql = """
            SELECT c.relname AS tablename, i.relname AS idxname, pg_get_indexdef(ix.indexrelid) AS def,
                   ix.indisunique, pg_get_expr(ix.indpred, ix.indrelid) AS pred,
                   (SELECT string_agg(a.attname, ',' ORDER BY k.ord)
                    FROM generate_subscripts(ix.indkey::int2[], 1) AS k(ord)
                    JOIN pg_attribute a ON a.attrelid = ix.indrelid
                        AND a.attnum = (ix.indkey::int2[])[k.ord]) AS colnames
            FROM pg_index ix
            JOIN pg_class c ON c.oid = ix.indrelid
            JOIN pg_class i ON i.oid = ix.indexrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ?
              AND NOT ix.indisprimary
              AND NOT EXISTS (SELECT 1 FROM pg_constraint con WHERE con.conindid = ix.indexrelid)
            ORDER BY c.relname, i.relname
            """;
        try (PreparedStatement ps = conn.prepareStatement(indexesSql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String tableName = rs.getString("tablename");
                    String pred = rs.getString("pred");
                    indexesByTable.computeIfAbsent(tableName, k -> new ArrayList<>())
                        .add(new IndexMeta(
                            rs.getString("idxname"), tableName,
                            parseColumnNames(rs.getString("colnames")),
                            rs.getBoolean("indisunique"),
                            pred != null,
                            pred != null ? DefinitionNormalizer.normalize(pred, schema) : null,
                            DefinitionNormalizer.normalize(rs.getString("def"), schema)));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("批量读取索引元数据失败: " + schema, e);
        }

        Map<String, String[]> partitionInfoByTable = new HashMap<>();
        String partitionInfoSql = """
            SELECT c.relname AS tablename, p.partstrategy,
                   (SELECT string_agg(a.attname, ',' ORDER BY k.ord)
                    FROM generate_subscripts(p.partkey, 1) AS k(ord)
                    JOIN pg_attribute a ON a.attrelid = c.oid
                        AND a.attnum = p.partkey[k.ord]) AS partkey
            FROM pg_partition p
            JOIN pg_class c ON c.oid = p.parentid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND p.parttype = 'r'
            """;
        try (PreparedStatement ps = conn.prepareStatement(partitionInfoSql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    partitionInfoByTable.put(rs.getString("tablename"),
                        new String[]{rs.getString("partstrategy"), rs.getString("partkey")});
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("批量读取分区定义失败: " + schema, e);
        }

        Map<String, List<PartitionMeta>> partitionsByTable = new HashMap<>();
        String partitionsSql = """
            SELECT c.relname AS tablename, p.relname AS partname,
                   p.boundaries, p.reltuples::bigint AS est_rows, p.reltablespace
            FROM pg_partition p
            JOIN pg_class c ON c.oid = p.parentid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND p.parttype = 'p'
            ORDER BY c.relname, p.relname
            """;
        try (PreparedStatement ps = conn.prepareStatement(partitionsSql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String tableName = rs.getString("tablename");
                    java.sql.Array boundArr = rs.getArray("boundaries");
                    String boundary = boundArr != null ? boundArr.toString() : null;
                    if (boundary != null) {
                        boundary = boundary.replaceAll("(?i)\\bNULL\\b", "MAXVALUE");
                    }
                    List<PartitionMeta> parts = partitionsByTable.computeIfAbsent(tableName, k -> new ArrayList<>());
                    parts.add(new PartitionMeta(
                        rs.getString("partname"),
                        tableName,
                        parts.size(),
                        boundary,
                        false,
                        null,
                        rs.getLong("est_rows")));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("批量读取分区明细失败: " + schema, e);
        }

        List<TableMeta> tables = new ArrayList<>(headers.size());
        for (TableHeader header : headers.values()) {
            String name = header.name();
            String[] info = partitionInfoByTable.get(name);
            tables.add(new TableMeta(
                name,
                header.comment(),
                columnsByTable.getOrDefault(name, List.of()),
                constraintsByTable.getOrDefault(name, List.of()),
                indexesByTable.getOrDefault(name, List.of()),
                header.partitioned(),
                header.partitioned() && info != null ? info[0] : null,
                header.partitioned() && info != null ? info[1] : null,
                header.partitioned() ? partitionsByTable.getOrDefault(name, List.of()) : List.of()));
            if (progress != null) progress.onTableDone(name);
        }
        return tables;
    }

    public TableMeta readTable(Connection conn, String schema, String tableName) throws Exception {
        String comment = readTableComment(conn, schema, tableName);
        List<ColumnMeta> columns = readColumns(conn, schema, tableName);
        List<ConstraintMeta> constraints = readConstraints(conn, schema, tableName);
        List<IndexMeta> indexes = readIndexes(conn, schema, tableName);
        boolean partitioned = checkPartitioned(conn, schema, tableName);
        String partitionStrategy = null;
        String partitionKey = null;
        List<PartitionMeta> partitions = List.of();
        if (partitioned) {
            var info = readPartitionInfo(conn, schema, tableName);
            partitionStrategy = info.strategy();
            partitionKey = info.key();
            partitions = readPartitions(conn, schema, tableName);
        }
        return new TableMeta(tableName, comment, columns, constraints, indexes,
            partitioned, partitionStrategy, partitionKey, partitions);
    }

    private String readTableComment(Connection conn, String schema, String table) throws Exception {
        String sql = "SELECT obj_description(c.oid, 'pg_class') AS cmt FROM pg_class c " +
                     "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname=? AND c.relname=?";
        return queryString(conn, sql, schema, table);
    }

    private List<ColumnMeta> readColumns(Connection conn, String schema, String table) throws Exception {
        String sql = """
            SELECT a.attname, format_type(a.atttypid, a.atttypmod) AS type,
                   a.attnotnull, pg_get_expr(d.adbin, d.adrelid) AS defaultval,
                   col_description(a.attrelid, a.attnum) AS colcomment, a.attnum
            FROM pg_attribute a
            JOIN pg_class c ON c.oid = a.attrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
            WHERE n.nspname = ? AND c.relname = ? AND a.attnum > 0 AND NOT a.attisdropped
            ORDER BY a.attnum
            """;
        List<ColumnMeta> cols = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema); ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cols.add(new ColumnMeta(
                        rs.getString("attname"),
                        TypeNormalizer.normalize(rs.getString("type")),
                        !rs.getBoolean("attnotnull"),
                        DefinitionNormalizer.normalizeDefaultValue(rs.getString("defaultval")),
                        rs.getString("colcomment"),
                        rs.getInt("attnum")));
                }
            }
        }
        return cols;
    }

    private List<ConstraintMeta> readConstraints(Connection conn, String schema, String table) throws Exception {
        String sql = """
            SELECT con.conname, con.contype, pg_get_constraintdef(con.oid) AS def,
                   NULLIF(con.confrelid, 0)::regclass AS reftable,
                   (SELECT string_agg(a.attname, ',' ORDER BY k.ord)
                    FROM generate_subscripts(con.conkey, 1) AS k(ord)
                    JOIN pg_attribute a ON a.attrelid = con.conrelid
                        AND a.attnum = con.conkey[k.ord]) AS colnames
            FROM pg_constraint con
            JOIN pg_class c ON c.oid = con.conrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relname = ?
            ORDER BY con.conname
            """;
        List<ConstraintMeta> cons = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema); ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    char ct = rs.getString("contype").charAt(0);
                    ConstraintType type = switch (ct) {
                        case 'p' -> ConstraintType.PRIMARY;
                        case 'u' -> ConstraintType.UNIQUE;
                        case 'f' -> ConstraintType.FOREIGN;
                        case 'c' -> ConstraintType.CHECK;
                        default -> throw new IllegalStateException("未知约束类型: " + ct);
                    };
                    String def = DefinitionNormalizer.normalize(rs.getString("def"), schema);
                    String refTable = DefinitionNormalizer.stripSchemaPrefix(rs.getString("reftable"), schema);
                    String colnames = rs.getString("colnames");
                    List<String> columns = parseColumnNames(colnames);
                    cons.add(new ConstraintMeta(rs.getString("conname"), type, def, columns, refTable));
                }
            }
        }
        return cons;
    }

    private List<IndexMeta> readIndexes(Connection conn, String schema, String table) throws Exception {
        String sql = """
            SELECT i.relname AS idxname, pg_get_indexdef(ix.indexrelid) AS def,
                   ix.indisunique, pg_get_expr(ix.indpred, ix.indrelid) AS pred,
                   (SELECT string_agg(a.attname, ',' ORDER BY k.ord)
                    FROM generate_subscripts(ix.indkey::int2[], 1) AS k(ord)
                    JOIN pg_attribute a ON a.attrelid = ix.indrelid
                        AND a.attnum = (ix.indkey::int2[])[k.ord]) AS colnames
            FROM pg_index ix
            JOIN pg_class c ON c.oid = ix.indrelid
            JOIN pg_class i ON i.oid = ix.indexrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relname = ?
              AND NOT ix.indisprimary
              AND NOT EXISTS (SELECT 1 FROM pg_constraint con WHERE con.conindid = ix.indexrelid)
            ORDER BY i.relname
            """;
        List<IndexMeta> idxs = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema); ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String pred = rs.getString("pred");
                    String colnames = rs.getString("colnames");
                    List<String> columns = parseColumnNames(colnames);
                    idxs.add(new IndexMeta(
                        rs.getString("idxname"), table, columns,
                        rs.getBoolean("indisunique"),
                        pred != null,
                        pred != null ? DefinitionNormalizer.normalize(pred, schema) : null,
                        DefinitionNormalizer.normalize(rs.getString("def"), schema)));
                }
            }
        }
        return idxs;
    }

    private record PartitionInfo(String strategy, String key) {}

    private PartitionInfo readPartitionInfo(Connection conn, String schema, String table) throws Exception {
        String sql = """
            SELECT p.partstrategy,
                   (SELECT string_agg(a.attname, ',' ORDER BY k.ord)
                    FROM generate_subscripts(p.partkey, 1) AS k(ord)
                    JOIN pg_attribute a ON a.attrelid = c.oid
                        AND a.attnum = p.partkey[k.ord]) AS partkey
            FROM pg_partition p
            JOIN pg_class c ON c.oid = p.parentid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relname = ? AND p.parttype = 'r'
            LIMIT 1
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema); ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new PartitionInfo(rs.getString("partstrategy"), rs.getString("partkey"));
                }
            }
        }
        return new PartitionInfo(null, null);
    }

    private List<PartitionMeta> readPartitions(Connection conn, String schema, String table) throws Exception {
        String sql = """
            SELECT p.relname AS partname,
                   p.boundaries,
                   p.reltuples::bigint AS est_rows,
                   p.reltablespace,
                   p.partstrategy
            FROM pg_partition p
            JOIN pg_class c ON c.oid = p.parentid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relname = ? AND p.parttype = 'p'
            ORDER BY p.relname
            """;
        List<PartitionMeta> parts = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema); ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                int ordinal = 0;
                while (rs.next()) {
                    java.sql.Array boundArr = rs.getArray("boundaries");
                    String boundary = boundArr != null ? boundArr.toString() : null;
                    if (boundary != null) {
                        boundary = boundary.replaceAll("(?i)\\bNULL\\b", "MAXVALUE");
                    }
                    parts.add(new PartitionMeta(
                        rs.getString("partname"),
                        table,
                        ordinal++,
                        boundary,
                        false,
                        null,
                        rs.getLong("est_rows")
                    ));
                }
            }
        }
        return parts;
    }

    private List<SequenceMeta> readSequencesInternal(Connection conn, String schema) {
        String sql = """
            SELECT c.relname AS seqname
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relkind = 'S'
            ORDER BY c.relname
            """;
        List<SequenceMeta> seqs = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    seqs.add(new SequenceMeta(
                        rs.getString("seqname"),
                        "bigint", 1L, 1L, 1L, Long.MAX_VALUE, 1L, false
                    ));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("读取序列失败: " + schema, e);
        }
        return seqs;
    }

    private List<String> parseColumnNames(String colnames) {
        if (colnames == null || colnames.isBlank()) return List.of();
        return Arrays.stream(colnames.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }

    private boolean checkPartitioned(Connection conn, String schema, String table) throws Exception {
        String sql = """
            SELECT EXISTS(
                SELECT 1 FROM pg_partition p
                JOIN pg_class c ON c.oid = p.parentid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND c.relname = ?
                  AND p.parttype = 'r'
            ) AS ispart
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema); ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getBoolean("ispart");
            }
        }
        return false;
    }

    private String queryString(Connection conn, String sql, String... params) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setString(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        }
        return null;
    }

    private boolean matchesFilters(String name, List<String> include, List<String> exclude) {
        boolean inc = include == null || include.isEmpty() ||
            include.stream().anyMatch(p -> Pattern.matches(p, name));
        boolean exc = exclude != null &&
            exclude.stream().anyMatch(p -> Pattern.matches(p, name));
        return inc && !exc;
    }
}
