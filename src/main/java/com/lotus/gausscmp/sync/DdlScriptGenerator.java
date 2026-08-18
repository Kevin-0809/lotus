package com.lotus.gausscmp.sync;

import com.lotus.gausscmp.compare.diff.*;
import com.lotus.gausscmp.metadata.*;
import java.util.*;

public final class DdlScriptGenerator {
    private final String schema;

    public DdlScriptGenerator(String schema) { this.schema = schema; }

    public String generate(List<TableStructureDiff> diffs, Map<String, TableMeta> sourceTables) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- 结构同步 DDL 脚本（源 → 目标）\n");
        sb.append("-- 生成时间: ").append(new Date()).append("\n");
        sb.append("-- 请人工审核后执行\n");
        sb.append("SET search_path TO \"").append(schema).append("\";\n\n");
        for (TableStructureDiff diff : diffs) {
            if (diff.status() == TableStructureStatus.CONSISTENT) continue;
            sb.append("-- ====== 表: ").append(diff.tableName()).append(" ======\n");
            if (!diff.existsInTarget()) {
                generateCreateTable(sb, sourceTables.get(diff.tableName()));
                continue;
            }
            if (!diff.existsInSource()) {
                sb.append("-- TABLE_EXTRA_IN_TARGET（默认不自动删除，如需删除请取消注释）\n");
                sb.append("-- DROP TABLE \"").append(schema).append("\".\"").append(diff.tableName()).append("\";\n\n");
                continue;
            }
            for (ColumnDiff cd : diff.columnDiffs()) generateColumnDdl(sb, diff.tableName(), cd, sourceTables.get(diff.tableName()));
            for (ConstraintDiff cd : diff.constraintDiffs()) generateConstraintDdl(sb, diff.tableName(), cd);
            for (IndexDiff id : diff.indexDiffs()) generateIndexDdl(sb, diff.tableName(), id);
            generateCommentDdl(sb, diff);
        }
        return sb.toString();
    }

    private void generateCreateTable(StringBuilder sb, TableMeta t) {
        if (t == null) { sb.append("-- 无法获取源表元数据\n\n"); return; }
        sb.append("-- TABLE_MISSING_IN_TARGET\n");
        sb.append("CREATE TABLE \"").append(schema).append("\".\"").append(t.name()).append("\" (\n");
        for (int i = 0; i < t.columns().size(); i++) {
            ColumnMeta c = t.columns().get(i);
            sb.append("    \"").append(c.name()).append("\" ").append(c.dataType());
            if (!c.nullable()) sb.append(" NOT NULL");
            if (c.defaultValue() != null) sb.append(" DEFAULT ").append(c.defaultValue());
            if (i < t.columns().size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append(");\n");
        for (ConstraintMeta con : t.constraints()) {
            if (con.type() == ConstraintType.PRIMARY || con.type() == ConstraintType.UNIQUE)
                sb.append("ALTER TABLE \"").append(schema).append("\".\"").append(t.name())
                  .append("\" ADD CONSTRAINT \"").append(con.name()).append("\" ").append(con.definition()).append(";\n");
        }
        if (t.comment() != null)
            sb.append("COMMENT ON TABLE \"").append(schema).append("\".\"").append(t.name())
              .append("\" IS '").append(t.comment()).append("';\n");
        for (IndexMeta idx : t.indexes())
            sb.append(idx.definition()).append(";\n");
        sb.append("\n");
    }

    private void generateColumnDdl(StringBuilder sb, String table, ColumnDiff cd, TableMeta srcTable) {
        switch (cd.type()) {
            case COLUMN_MISSING_IN_TARGET -> sb.append("-- COLUMN_MISSING_IN_TARGET: ").append(cd.columnName()).append("\n")
                .append("ALTER TABLE \"").append(schema).append("\".\"").append(table)
                .append("\" ADD COLUMN \"").append(cd.columnName()).append("\";\n\n");
            case COLUMN_EXTRA_IN_TARGET -> sb.append("-- COLUMN_EXTRA_IN_TARGET: ").append(cd.columnName()).append("（默认注释，不执行）\n")
                .append("-- ALTER TABLE \"").append(schema).append("\".\"").append(table)
                .append("\" DROP COLUMN \"").append(cd.columnName()).append("\";\n\n");
            case COLUMN_MISMATCH -> {
                sb.append("-- COLUMN_MISMATCH: ").append(cd.columnName()).append(".").append(cd.field())
                  .append(" (源=").append(cd.sourceValue()).append(", 目标=").append(cd.targetValue()).append(")\n");
                if ("dataType".equals(cd.field()))
                    sb.append("ALTER TABLE \"").append(schema).append("\".\"").append(table)
                      .append("\" ALTER COLUMN \"").append(cd.columnName()).append("\" TYPE ").append(cd.sourceValue()).append(";\n");
                else if ("nullable".equals(cd.field()))
                    sb.append("ALTER TABLE \"").append(schema).append("\".\"").append(table)
                      .append("\" ALTER COLUMN \"").append(cd.columnName()).append("\" ")
                      .append("true".equals(cd.sourceValue()) ? "DROP NOT NULL" : "SET NOT NULL").append(";\n");
                else if ("defaultValue".equals(cd.field()))
                    sb.append("ALTER TABLE \"").append(schema).append("\".\"").append(table)
                      .append("\" ALTER COLUMN \"").append(cd.columnName()).append("\" ")
                      .append(cd.sourceValue() == null ? "DROP DEFAULT" : "SET DEFAULT " + cd.sourceValue()).append(";\n");
                sb.append("\n");
            }
            default -> {}
        }
    }

    private void generateConstraintDdl(StringBuilder sb, String table, ConstraintDiff cd) {
        switch (cd.type()) {
            case CONSTRAINT_MISSING_IN_TARGET -> sb.append("-- CONSTRAINT_MISSING_IN_TARGET: ").append(cd.constraintName()).append("\n")
                .append("ALTER TABLE \"").append(schema).append("\".\"").append(table)
                .append("\" ADD CONSTRAINT \"").append(cd.constraintName()).append("\" ").append(cd.sourceDef()).append(";\n\n");
            case CONSTRAINT_EXTRA_IN_TARGET -> sb.append("-- CONSTRAINT_EXTRA_IN_TARGET: ").append(cd.constraintName()).append("（默认注释）\n")
                .append("-- ALTER TABLE \"").append(schema).append("\".\"").append(table)
                .append("\" DROP CONSTRAINT \"").append(cd.constraintName()).append("\";\n\n");
            case CONSTRAINT_MISMATCH -> sb.append("-- CONSTRAINT_MISMATCH: ").append(cd.constraintName())
                .append(" (源=").append(cd.sourceDef()).append(", 目标=").append(cd.targetDef()).append(")\n")
                .append("ALTER TABLE \"").append(schema).append("\".\"").append(table)
                .append("\" DROP CONSTRAINT \"").append(cd.constraintName()).append("\";\n")
                .append("ALTER TABLE \"").append(schema).append("\".\"").append(table)
                .append("\" ADD CONSTRAINT \"").append(cd.constraintName()).append("\" ").append(cd.sourceDef()).append(";\n\n");
            default -> {}
        }
    }

    private void generateIndexDdl(StringBuilder sb, String table, IndexDiff id) {
        switch (id.type()) {
            case INDEX_MISSING_IN_TARGET -> sb.append("-- INDEX_MISSING_IN_TARGET: ").append(id.indexName()).append("\n")
                .append("CREATE INDEX \"").append(id.indexName()).append("\" ON \"").append(schema).append("\".\"").append(table).append("\" ")
                .append(extractIndexSpec(id.sourceDef())).append(";\n\n");
            case INDEX_EXTRA_IN_TARGET -> sb.append("-- INDEX_EXTRA_IN_TARGET: ").append(id.indexName()).append("（默认注释）\n")
                .append("-- DROP INDEX \"").append(id.indexName()).append("\";\n\n");
            case INDEX_MISMATCH -> sb.append("-- INDEX_MISMATCH: ").append(id.indexName()).append("\n")
                .append("DROP INDEX \"").append(id.indexName()).append("\";\n")
                .append("CREATE INDEX \"").append(id.indexName()).append("\" ON \"").append(schema).append("\".\"").append(table).append("\" ")
                .append(extractIndexSpec(id.sourceDef())).append(";\n\n");
            default -> {}
        }
    }

    private String extractIndexSpec(String definition) {
        if (definition == null) return "";
        int onIdx = definition.toUpperCase().indexOf(" ON ");
        if (onIdx < 0) return "";
        String afterOn = definition.substring(onIdx + 4);
        int parenIdx = afterOn.indexOf("(");
        if (parenIdx < 0) return afterOn.trim();
        return afterOn.substring(parenIdx).trim();
    }

    private void generateCommentDdl(StringBuilder sb, TableStructureDiff diff) {
        if (diff.commentDiff().isPresent()) {
            String comment = diff.commentDiff().get();
            sb.append("-- TABLE_COMMENT_MISMATCH\n");
            if (comment != null) {
                sb.append("COMMENT ON TABLE \"").append(schema).append("\".\"").append(diff.tableName())
                  .append("\" IS '").append(comment.replace("'", "''")).append("';\n");
            }
            sb.append("\n");
        }
        for (ColumnDiff cd : diff.columnDiffs()) {
            if ("comment".equals(cd.field()) && cd.sourceValue() != null) {
                sb.append("-- COLUMN_COMMENT_MISMATCH: ").append(cd.columnName()).append("\n");
                sb.append("COMMENT ON COLUMN \"").append(schema).append("\".\"").append(diff.tableName())
                  .append("\".\"").append(cd.columnName()).append("\" IS '")
                  .append(cd.sourceValue().replace("'", "''")).append("';\n");
            }
        }
    }
}
