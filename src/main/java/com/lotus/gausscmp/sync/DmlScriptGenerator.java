package com.lotus.gausscmp.sync;

import com.lotus.gausscmp.compare.diff.*;
import java.util.*;

public final class DmlScriptGenerator {
    private final String schema;
    private final String syncDirection;

    public DmlScriptGenerator(String schema, String syncDirection) {
        this.schema = schema;
        this.syncDirection = syncDirection;
    }

    public String generate(List<TableDataDiff> diffs) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- 数据同步 DML 脚本（").append(syncDirection).append("）\n");
        sb.append("-- 生成时间: ").append(new Date()).append("\n");
        sb.append("-- 请人工审核后执行\n");
        sb.append("SET search_path TO \"").append(schema).append("\";\n\n");
        for (TableDataDiff diff : diffs) {
            if (diff.status() != TableDataStatus.DIFFERENT) {
                if (diff.status() == TableDataStatus.SKIPPED)
                    sb.append("-- 跳过: ").append(diff.tableName()).append(" (").append(diff.skippedReason()).append(")\n\n");
                continue;
            }
            sb.append("-- ====== 表: ").append(diff.tableName()).append(" ======\n");
            for (RowDiff rd : diff.rowDiffs()) generateRowDml(sb, diff.tableName(), diff.keyColumns(), rd);
            sb.append("\n");
        }
        return sb.toString();
    }

    private void generateRowDml(StringBuilder sb, String table, List<String> keyColumns, RowDiff rd) {
        String fqTable = "\"" + schema + "\".\"" + table + "\"";
        switch (rd.type()) {
            case MISSING_IN_TARGET -> {
                sb.append("INSERT INTO ").append(fqTable).append(" (");
                var src = rd.sourceValues();
                sb.append(String.join(", ", src.keySet().stream().map(c -> "\"" + c + "\"").toList()));
                sb.append(") VALUES (");
                sb.append(String.join(", ", src.values().stream().map(this::formatValue).toList()));
                sb.append(");\n");
            }
            case EXTRA_IN_TARGET -> {
                sb.append("DELETE FROM ").append(fqTable).append(" WHERE ");
                sb.append(whereClause(rd.keyValues()));
                sb.append(";\n");
            }
            case MISMATCH -> {
                sb.append("UPDATE ").append(fqTable).append(" SET ");
                var sets = new ArrayList<String>();
                for (String col : rd.mismatchColumns()) {
                    Object val = rd.sourceValues().get(col);
                    sets.add("\"" + col + "\" = " + formatValue(val));
                }
                sb.append(String.join(", ", sets));
                sb.append(" WHERE ").append(whereClause(rd.keyValues()));
                sb.append(";\n");
            }
        }
    }

    private String whereClause(Map<String, Object> keys) {
        var parts = new ArrayList<String>();
        for (var e : keys.entrySet()) parts.add("\"" + e.getKey() + "\" = " + formatValue(e.getValue()));
        return String.join(" AND ", parts);
    }

    private String formatValue(Object v) {
        if (v == null) return "NULL";
        if (v instanceof Number) return v.toString();
        if (v instanceof Boolean) return v.toString();
        String s = v.toString().replace("'", "''");
        return "'" + s + "'";
    }
}
