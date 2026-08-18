package com.lotus.gausscmp.metadata;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public final class OpenGaussMetadataReader implements MetadataReader {

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
        return new SchemaSnapshot(schema, tables);
    }

    private List<String> readTableNames(Connection conn, String schema) {
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

    private TableMeta readTable(Connection conn, String schema, String tableName) throws Exception {
        String comment = readTableComment(conn, schema, tableName);
        List<ColumnMeta> columns = readColumns(conn, schema, tableName);
        List<ConstraintMeta> constraints = readConstraints(conn, schema, tableName);
        List<IndexMeta> indexes = readIndexes(conn, schema, tableName);
        boolean partitioned = checkPartitioned(conn, schema, tableName);
        return new TableMeta(tableName, comment, columns, constraints, indexes, partitioned);
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
                        rs.getBoolean("attnotnull"),
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
                    FROM unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord)
                    JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.attnum) AS colnames
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
                    String def = DefinitionNormalizer.normalize(rs.getString("def"));
                    String refTable = rs.getString("reftable");
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
                    FROM unnest(ix.indkey::int2[]) WITH ORDINALITY AS k(attnum, ord)
                    JOIN pg_attribute a ON a.attrelid = ix.indrelid AND a.attnum = k.attnum) AS colnames
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
                        pred != null ? DefinitionNormalizer.normalize(pred) : null,
                        DefinitionNormalizer.normalize(rs.getString("def"))));
                }
            }
        }
        return idxs;
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
            SELECT c.parttype = 'p' AS ispart
            FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relname = ? AND c.relkind IN ('r', 'p')
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
