package com.lotus.gausscmp.web.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/** 元数据历史快照查询：快照列表、快照明细、表/字段历史链 */
@Service
public class MetadataHistoryService {

    private final JdbcTemplate jdbcTemplate;

    public MetadataHistoryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 快照列表（含当前快照，最新在前） */
    public List<Map<String, Object>> listSnapshots(Long connectionId) {
        return jdbcTemplate.queryForList(
            "SELECT s.id AS snapshot_id, s.connection_id, s.collected_at, s.table_count, " +
            "s.sequence_count, s.duration_ms, " +
            "(s.id = (SELECT MAX(id) FROM meta_snapshot WHERE connection_id = s.connection_id)) AS is_current " +
            "FROM meta_snapshot s WHERE s.connection_id = ? ORDER BY s.collected_at DESC, s.id DESC",
            connectionId);
    }

    /** 表历史链：该表在每个快照中的版本（字段数由子查询统计） */
    public List<Map<String, Object>> tableHistory(Long connectionId, String tableName) {
        return jdbcTemplate.queryForList(
            "SELECT s.id AS snapshot_id, s.collected_at, h.id AS table_id, h.comment, " +
            "h.partitioned, h.partition_strategy, h.partition_key, " +
            "(SELECT count(*) FROM meta_column_history c WHERE c.snapshot_id = h.snapshot_id " +
            " AND c.table_id = h.id) AS column_count, " +
            "(SELECT count(*) FROM meta_index_history i WHERE i.snapshot_id = h.snapshot_id " +
            " AND i.table_id = h.id) AS index_count, " +
            "(SELECT count(*) FROM meta_constraint_history k WHERE k.snapshot_id = h.snapshot_id " +
            " AND k.table_id = h.id) AS constraint_count " +
            "FROM meta_snapshot s JOIN meta_table_history h ON h.snapshot_id = s.id " +
            "WHERE s.connection_id = ? AND h.table_name = ? " +
            "ORDER BY s.collected_at DESC, s.id DESC",
            connectionId, tableName);
    }

    /** 字段历史链：该字段在每个快照中的定义；字段不存在的快照返回 exists=false（表也存在但列缺失） */
    public List<Map<String, Object>> columnHistory(Long connectionId, String tableName, String columnName) {
        return jdbcTemplate.queryForList(
            "SELECT s.id AS snapshot_id, s.collected_at, c.column_name, c.data_type, c.nullable, " +
            "c.default_value, c.comment, c.ordinal, (c.column_name IS NOT NULL) AS column_exists " +
            "FROM meta_snapshot s " +
            "JOIN meta_table_history h ON h.snapshot_id = s.id " +
            "LEFT JOIN meta_column_history c ON c.snapshot_id = s.id AND c.table_id = h.id " +
            " AND c.column_name = ? " +
            "WHERE s.connection_id = ? AND h.table_name = ? " +
            "ORDER BY s.collected_at DESC, s.id DESC",
            columnName, connectionId, tableName);
    }

    /** 某快照下某表的完整明细（表信息 + 字段/约束/索引/分区） */
    public Map<String, Object> snapshotTableDetail(Long connectionId, long snapshotId, String tableName) {
        List<Map<String, Object>> heads = jdbcTemplate.queryForList(
            "SELECT h.id AS table_id, h.table_name, h.comment, h.partitioned, h.partition_strategy, " +
            "h.partition_key, s.collected_at, s.id AS snapshot_id " +
            "FROM meta_snapshot s JOIN meta_table_history h ON h.snapshot_id = s.id " +
            "WHERE s.connection_id = ? AND s.id = ? AND h.table_name = ?",
            connectionId, snapshotId, tableName);
        if (heads.isEmpty()) throw new NoSuchElementException("快照或表不存在: snapshotId=" + snapshotId + ", table=" + tableName);
        Map<String, Object> head = new LinkedHashMap<>(heads.get(0));
        Long tableId = ((Number) head.get("table_id")).longValue();

        head.put("columns", jdbcTemplate.queryForList(
            "SELECT column_name, data_type, nullable, default_value, comment, ordinal " +
            "FROM meta_column_history WHERE snapshot_id = ? AND table_id = ? ORDER BY ordinal",
            snapshotId, tableId));
        head.put("constraints", jdbcTemplate.queryForList(
            "SELECT constraint_name, type, definition, columns, ref_table " +
            "FROM meta_constraint_history WHERE snapshot_id = ? AND table_id = ? ORDER BY constraint_name",
            snapshotId, tableId));
        head.put("indexes", jdbcTemplate.queryForList(
            "SELECT index_name, columns, is_unique, is_partial, where_clause, definition " +
            "FROM meta_index_history WHERE snapshot_id = ? AND table_id = ? ORDER BY index_name",
            snapshotId, tableId));
        head.put("partitions", jdbcTemplate.queryForList(
            "SELECT partition_name, parent_name, ordinal, boundary_expr, is_sub_partition, " +
            "tablespace, estimated_rows FROM meta_partition_history " +
            "WHERE snapshot_id = ? AND table_id = ? ORDER BY ordinal",
            snapshotId, tableId));
        return head;
    }

    /** 两个快照间表结构差异（表级：字段/约束/索引/分区的增删改） */
    public Map<String, Object> diffSnapshots(Long connectionId, long oldSnapshotId, long newSnapshotId, String tableName) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("oldSnapshotId", oldSnapshotId);
        result.put("newSnapshotId", newSnapshotId);
        result.put("tableName", tableName);

        Long oldTableId = queryTableId(connectionId, oldSnapshotId, tableName);
        Long newTableId = queryTableId(connectionId, newSnapshotId, tableName);

        List<Map<String, Object>> columnDiffs = new ArrayList<>();
        Map<String, Map<String, Object>> oldCols = columnMap(oldSnapshotId, oldTableId);
        Map<String, Map<String, Object>> newCols = columnMap(newSnapshotId, newTableId);
        TreeSet<String> allCols = new TreeSet<>();
        if (oldCols != null) allCols.addAll(oldCols.keySet());
        if (newCols != null) allCols.addAll(newCols.keySet());
        for (String col : allCols) {
            Map<String, Object> o = oldCols == null ? null : oldCols.get(col);
            Map<String, Object> n = newCols == null ? null : newCols.get(col);
            if (o == null) {
                columnDiffs.add(Map.of("type", "ADDED", "columnName", col,
                    "newValue", describeColumn(n)));
            } else if (n == null) {
                columnDiffs.add(Map.of("type", "REMOVED", "columnName", col,
                    "oldValue", describeColumn(o)));
            } else {
                List<String> changed = new ArrayList<>();
                compareField(changed, "dataType", o.get("data_type"), n.get("data_type"));
                compareField(changed, "nullable", o.get("nullable"), n.get("nullable"));
                compareField(changed, "defaultValue", o.get("default_value"), n.get("default_value"));
                compareField(changed, "comment", o.get("comment"), n.get("comment"));
                if (!changed.isEmpty()) {
                    columnDiffs.add(Map.of("type", "CHANGED", "columnName", col,
                        "oldValue", describeColumn(o), "newValue", describeColumn(n),
                        "changedFields", String.join(",", changed)));
                }
            }
        }
        result.put("tableOld", oldTableId == null ? null : tableHead(connectionId, oldSnapshotId, tableName));
        result.put("tableNew", newTableId == null ? null : tableHead(connectionId, newSnapshotId, tableName));
        result.put("tableExists", Map.of("old", oldTableId != null, "new", newTableId != null));
        result.put("columnDiffs", columnDiffs);
        return result;
    }

    private Long queryTableId(Long connectionId, long snapshotId, String tableName) {
        List<Long> ids = jdbcTemplate.queryForList(
            "SELECT h.id FROM meta_snapshot s JOIN meta_table_history h ON h.snapshot_id = s.id " +
            "WHERE s.connection_id = ? AND s.id = ? AND h.table_name = ? LIMIT 1",
            Long.class, connectionId, snapshotId, tableName);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private Map<String, Object> tableHead(Long connectionId, long snapshotId, String tableName) {
        return jdbcTemplate.queryForMap(
            "SELECT h.comment, h.partitioned, h.partition_strategy, h.partition_key " +
            "FROM meta_snapshot s JOIN meta_table_history h ON h.snapshot_id = s.id " +
            "WHERE s.connection_id = ? AND s.id = ? AND h.table_name = ?",
            connectionId, snapshotId, tableName);
    }

    private Map<String, Map<String, Object>> columnMap(long snapshotId, Long tableId) {
        if (tableId == null) return null;
        Map<String, Map<String, Object>> map = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT column_name, data_type, nullable, default_value, comment, ordinal " +
                "FROM meta_column_history WHERE snapshot_id = ? AND table_id = ?",
                snapshotId, tableId)) {
            map.put((String) row.get("column_name"), row);
        }
        return map;
    }

    private static String describeColumn(Map<String, Object> c) {
        if (c == null) return null;
        return c.get("data_type") + (Boolean.TRUE.equals(c.get("nullable")) ? " NULL" : " NOT NULL") +
            (c.get("default_value") != null ? " DEFAULT " + c.get("default_value") : "");
    }

    private static void compareField(List<String> changed, String field, Object o, Object n) {
        if (!Objects.equals(o, n)) changed.add(field);
    }
}
