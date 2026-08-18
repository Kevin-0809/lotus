package com.lotus.gausscmp.compare.structure;

import com.lotus.gausscmp.compare.diff.*;
import com.lotus.gausscmp.metadata.*;
import java.util.*;
import java.util.stream.Collectors;

public final class StructureComparator {

    public StructureDiffResult compare(SchemaSnapshot source, SchemaSnapshot target) {
        Map<String, TableMeta> srcMap = source.tables().stream()
            .collect(Collectors.toMap(TableMeta::name, t -> t, (a, b) -> a, TreeMap::new));
        Map<String, TableMeta> tgtMap = target.tables().stream()
            .collect(Collectors.toMap(TableMeta::name, t -> t, (a, b) -> a, TreeMap::new));
        List<TableStructureDiff> diffs = new ArrayList<>();
        Set<String> allNames = new TreeSet<>();
        allNames.addAll(srcMap.keySet());
        allNames.addAll(tgtMap.keySet());
        for (String name : allNames) {
            TableMeta s = srcMap.get(name);
            TableMeta t = tgtMap.get(name);
            diffs.add(compareTable(name, s, t));
        }
        return new StructureDiffResult(diffs);
    }

    private TableStructureDiff compareTable(String name, TableMeta s, TableMeta t) {
        if (s == null) {
            return new TableStructureDiff(name, false, true, TableStructureStatus.DIFFERENT,
                List.of(new ColumnDiff(DiffType.TABLE_EXTRA_IN_TARGET, name, null, null, null)),
                List.of(), List.of(), Optional.empty());
        }
        if (t == null) {
            return new TableStructureDiff(name, true, false, TableStructureStatus.DIFFERENT,
                List.of(new ColumnDiff(DiffType.TABLE_MISSING_IN_TARGET, name, null, null, null)),
                List.of(), List.of(), Optional.empty());
        }
        List<ColumnDiff> colDiffs = compareColumns(s, t);
        List<ConstraintDiff> conDiffs = compareConstraints(s, t);
        List<IndexDiff> idxDiffs = compareIndexes(s, t);
        Optional<String> commentDiff = compareComment(s.comment(), t.comment());
        boolean consistent = colDiffs.isEmpty() && conDiffs.isEmpty() && idxDiffs.isEmpty() && commentDiff.isEmpty();
        return new TableStructureDiff(name, true, true,
            consistent ? TableStructureStatus.CONSISTENT : TableStructureStatus.DIFFERENT,
            colDiffs, conDiffs, idxDiffs, commentDiff);
    }

    private List<ColumnDiff> compareColumns(TableMeta s, TableMeta t) {
        List<ColumnDiff> diffs = new ArrayList<>();
        Map<String, ColumnMeta> sm = toMap(s.columns(), ColumnMeta::name);
        Map<String, ColumnMeta> tm = toMap(t.columns(), ColumnMeta::name);
        for (String n : unionKeys(sm, tm)) {
            ColumnMeta sc = sm.get(n), tc = tm.get(n);
            if (sc == null) { diffs.add(new ColumnDiff(DiffType.COLUMN_EXTRA_IN_TARGET, n, null, null, null)); continue; }
            if (tc == null) { diffs.add(new ColumnDiff(DiffType.COLUMN_MISSING_IN_TARGET, n, null, null, null)); continue; }
            if (!eq(sc.dataType(), tc.dataType())) diffs.add(new ColumnDiff(DiffType.COLUMN_MISMATCH, n, sc.dataType(), tc.dataType(), "dataType"));
            if (sc.nullable() != tc.nullable()) diffs.add(new ColumnDiff(DiffType.COLUMN_MISMATCH, n, String.valueOf(sc.nullable()), String.valueOf(tc.nullable()), "nullable"));
            if (!eq(sc.defaultValue(), tc.defaultValue())) diffs.add(new ColumnDiff(DiffType.COLUMN_MISMATCH, n, sc.defaultValue(), tc.defaultValue(), "defaultValue"));
            if (!eq(sc.comment(), tc.comment())) diffs.add(new ColumnDiff(DiffType.COLUMN_MISMATCH, n, sc.comment(), tc.comment(), "comment"));
        }
        return diffs;
    }

    private List<ConstraintDiff> compareConstraints(TableMeta s, TableMeta t) {
        List<ConstraintDiff> diffs = new ArrayList<>();
        Map<String, ConstraintMeta> sm = toMap(s.constraints(), ConstraintMeta::name);
        Map<String, ConstraintMeta> tm = toMap(t.constraints(), ConstraintMeta::name);
        for (String n : unionKeys(sm, tm)) {
            ConstraintMeta sc = sm.get(n), tc = tm.get(n);
            if (sc == null) { diffs.add(new ConstraintDiff(DiffType.CONSTRAINT_EXTRA_IN_TARGET, n, null, null)); continue; }
            if (tc == null) { diffs.add(new ConstraintDiff(DiffType.CONSTRAINT_MISSING_IN_TARGET, n, sc.definition(), null)); continue; }
            if (!eq(sc.definition(), tc.definition())) diffs.add(new ConstraintDiff(DiffType.CONSTRAINT_MISMATCH, n, sc.definition(), tc.definition()));
        }
        return diffs;
    }

    private List<IndexDiff> compareIndexes(TableMeta s, TableMeta t) {
        List<IndexDiff> diffs = new ArrayList<>();
        Map<String, IndexMeta> sm = toMap(s.indexes(), IndexMeta::name);
        Map<String, IndexMeta> tm = toMap(t.indexes(), IndexMeta::name);
        for (String n : unionKeys(sm, tm)) {
            IndexMeta si = sm.get(n), ti = tm.get(n);
            if (si == null) { diffs.add(new IndexDiff(DiffType.INDEX_EXTRA_IN_TARGET, n, null, null)); continue; }
            if (ti == null) { diffs.add(new IndexDiff(DiffType.INDEX_MISSING_IN_TARGET, n, si.definition(), null)); continue; }
            if (!eq(si.definition(), ti.definition())) diffs.add(new IndexDiff(DiffType.INDEX_MISMATCH, n, si.definition(), ti.definition()));
        }
        return diffs;
    }

    private Optional<String> compareComment(String s, String t) {
        return eq(s, t) ? Optional.empty() : Optional.ofNullable(s);
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static <K, V> Map<K, V> toMap(List<V> list, java.util.function.Function<V, K> keyFn) {
        return list.stream().collect(Collectors.toMap(keyFn, v -> v, (a, b) -> a, TreeMap::new));
    }

    private static <K extends Comparable<K>> Set<K> unionKeys(Map<K, ?> a, Map<K, ?> b) {
        Set<K> keys = new TreeSet<>(a.keySet());
        keys.addAll(b.keySet());
        return keys;
    }
}
