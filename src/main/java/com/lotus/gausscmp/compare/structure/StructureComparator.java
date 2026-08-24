package com.lotus.gausscmp.compare.structure;

import com.lotus.gausscmp.compare.diff.*;
import com.lotus.gausscmp.concurrency.TableTaskExecutor;
import com.lotus.gausscmp.metadata.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.stream.Collectors;

public final class StructureComparator {
    private static final Logger LOG = LoggerFactory.getLogger(StructureComparator.class);

    public StructureDiffResult compare(SchemaSnapshot source, SchemaSnapshot target) {
        return compare(source, target, 1);
    }

    public StructureDiffResult compare(SchemaSnapshot source, SchemaSnapshot target, int parallelism) {
        Map<String, TableMeta> srcMap = source.tables().stream()
            .collect(Collectors.toMap(TableMeta::name, t -> t, (a, b) -> a, TreeMap::new));
        Map<String, TableMeta> tgtMap = target.tables().stream()
            .collect(Collectors.toMap(TableMeta::name, t -> t, (a, b) -> a, TreeMap::new));
        Set<String> allNames = new TreeSet<>();
        allNames.addAll(srcMap.keySet());
        allNames.addAll(tgtMap.keySet());
        Map<String, TableStructureDiff> diffMap;
        try (TableTaskExecutor<TableStructureDiff> executor = new TableTaskExecutor<>(parallelism)) {
            diffMap = executor.execute(new ArrayList<>(allNames), name ->
                compareTable(name, srcMap.get(name), tgtMap.get(name)));
        } catch (Exception e) {
            throw new IllegalStateException("按表并发结构比对失败", e);
        }
        List<TableStructureDiff> diffs = new ArrayList<>(allNames.size());
        for (String name : allNames) {
            TableStructureDiff diff = diffMap.get(name);
            if (diff == null) {
                LOG.debug("跳过无结果的表比对: table={}", name);
                continue;
            }
            diffs.add(diff);
        }
        List<SequenceDiff> seqDiffs = compareSequences(
            source.sequences() != null ? source.sequences() : List.of(),
            target.sequences() != null ? target.sequences() : List.of());
        return new StructureDiffResult(diffs, seqDiffs);
    }

    public TableStructureDiff compareTable(String name, TableMeta s, TableMeta t) {
        if (s == null) {
            return new TableStructureDiff(name, false, true, TableStructureStatus.DIFFERENT,
                List.of(new ColumnDiff(DiffType.TABLE_EXTRA_IN_TARGET, name, null, null, null)),
                List.of(), List.of(), List.of(), Optional.empty());
        }
        if (t == null) {
            return new TableStructureDiff(name, true, false, TableStructureStatus.DIFFERENT,
                List.of(new ColumnDiff(DiffType.TABLE_MISSING_IN_TARGET, name, null, null, null)),
                List.of(), List.of(), List.of(), Optional.empty());
        }
        List<ColumnDiff> colDiffs = compareColumns(s, t);
        List<ConstraintDiff> conDiffs = compareConstraints(s, t);
        List<IndexDiff> idxDiffs = compareIndexes(s, t);
        List<PartitionDiff> partDiffs = comparePartitions(s, t);
        Optional<String> commentDiff = compareComment(s.comment(), t.comment());
        boolean consistent = colDiffs.isEmpty() && conDiffs.isEmpty() && idxDiffs.isEmpty()
            && partDiffs.isEmpty() && commentDiff.isEmpty();
        return new TableStructureDiff(name, true, true,
            consistent ? TableStructureStatus.CONSISTENT : TableStructureStatus.DIFFERENT,
            colDiffs, conDiffs, idxDiffs, partDiffs, commentDiff);
    }

    private List<ColumnDiff> compareColumns(TableMeta s, TableMeta t) {
        List<ColumnDiff> diffs = new ArrayList<>();
        Map<String, ColumnMeta> sm = toMap(s.columns(), ColumnMeta::name);
        Map<String, ColumnMeta> tm = toMap(t.columns(), ColumnMeta::name);
        for (String n : unionKeys(sm, tm)) {
            ColumnMeta sc = sm.get(n), tc = tm.get(n);
            if (sc == null) { diffs.add(new ColumnDiff(DiffType.COLUMN_EXTRA_IN_TARGET, n, null, null, null)); continue; }
            if (tc == null) {
                diffs.add(new ColumnDiff(DiffType.COLUMN_MISSING_IN_TARGET, n, null, null, null));
                diffs.add(new ColumnDiff(DiffType.COLUMN_MISMATCH, n, sc.dataType(), null, "dataType"));
                diffs.add(new ColumnDiff(DiffType.COLUMN_MISMATCH, n, String.valueOf(sc.nullable()), null, "nullable"));
                diffs.add(new ColumnDiff(DiffType.COLUMN_MISMATCH, n, sc.defaultValue(), null, "defaultValue"));
                diffs.add(new ColumnDiff(DiffType.COLUMN_MISMATCH, n, sc.comment(), null, "comment"));
                continue;
            }
            if (!eq(DefinitionNormalizer.normalize(sc.dataType()), DefinitionNormalizer.normalize(tc.dataType()))) diffs.add(new ColumnDiff(DiffType.COLUMN_MISMATCH, n, sc.dataType(), tc.dataType(), "dataType"));
            if (sc.nullable() != tc.nullable()) diffs.add(new ColumnDiff(DiffType.COLUMN_MISMATCH, n, String.valueOf(sc.nullable()), String.valueOf(tc.nullable()), "nullable"));
            if (!eq(DefinitionNormalizer.normalizeDefaultValue(sc.defaultValue()), DefinitionNormalizer.normalizeDefaultValue(tc.defaultValue()))) diffs.add(new ColumnDiff(DiffType.COLUMN_MISMATCH, n, sc.defaultValue(), tc.defaultValue(), "defaultValue"));
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
            if (sc.type() == ConstraintType.CHECK) continue;
            if (!eq(DefinitionNormalizer.normalize(sc.definition(), s.name()), DefinitionNormalizer.normalize(tc.definition(), t.name()))) diffs.add(new ConstraintDiff(DiffType.CONSTRAINT_MISMATCH, n, sc.definition(), tc.definition()));
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
            if (!eq(DefinitionNormalizer.normalizeIndexDefinition(si.definition(), s.name(), s.partitioned()),
                     DefinitionNormalizer.normalizeIndexDefinition(ti.definition(), t.name(), t.partitioned()))) {
                diffs.add(new IndexDiff(DiffType.INDEX_MISMATCH, n, si.definition(), ti.definition()));
            }
        }
        return diffs;
    }

    private List<PartitionDiff> comparePartitions(TableMeta s, TableMeta t) {
        List<PartitionDiff> diffs = new ArrayList<>();
        if (!eq(s.partitionStrategy(), t.partitionStrategy())) {
            diffs.add(new PartitionDiff(DiffType.PARTITION_STRATEGY_MISMATCH, "(strategy)",
                s.partitionStrategy(), t.partitionStrategy()));
        }
        Map<String, PartitionMeta> sm = toMap(s.partitions() != null ? s.partitions() : List.of(), PartitionMeta::name);
        Map<String, PartitionMeta> tm = toMap(t.partitions() != null ? t.partitions() : List.of(), PartitionMeta::name);
        for (String n : unionKeys(sm, tm)) {
            PartitionMeta sp = sm.get(n), tp = tm.get(n);
            if (sp == null) { diffs.add(new PartitionDiff(DiffType.PARTITION_EXTRA_IN_TARGET, n, null, tp.boundaryExpr())); continue; }
            if (tp == null) { diffs.add(new PartitionDiff(DiffType.PARTITION_MISSING_IN_TARGET, n, sp.boundaryExpr(), null)); continue; }
            if (!eq(sp.boundaryExpr(), tp.boundaryExpr())) diffs.add(new PartitionDiff(DiffType.PARTITION_MISMATCH, n, sp.boundaryExpr(), tp.boundaryExpr()));
        }
        return diffs;
    }

    private List<SequenceDiff> compareSequences(List<SequenceMeta> source, List<SequenceMeta> target) {
        List<SequenceDiff> diffs = new ArrayList<>();
        Map<String, SequenceMeta> sm = toMap(source, SequenceMeta::name);
        Map<String, SequenceMeta> tm = toMap(target, SequenceMeta::name);
        Set<String> all = new TreeSet<>();
        all.addAll(sm.keySet());
        all.addAll(tm.keySet());
        for (String n : all) {
            SequenceMeta s = sm.get(n), t = tm.get(n);
            if (s == null) { diffs.add(new SequenceDiff(DiffType.SEQUENCE_EXTRA_IN_TARGET, n, null, formatSeq(t), null)); continue; }
            if (t == null) { diffs.add(new SequenceDiff(DiffType.SEQUENCE_MISSING_IN_TARGET, n, formatSeq(s), null, null)); continue; }
            String srcDef = formatSeq(s), tgtDef = formatSeq(t);
            if (!srcDef.equals(tgtDef)) {
                String field = findMismatchField(s, t);
                diffs.add(new SequenceDiff(DiffType.SEQUENCE_MISMATCH, n, srcDef, tgtDef, field));
            }
        }
        return diffs;
    }

    private static String formatSeq(SequenceMeta s) {
        return String.format("start=%d,increment=%d,min=%d,max=%d,cache=%d,cycle=%s,type=%s",
            s.startValue(), s.incrementBy(), s.minValue(), s.maxValue(), s.cacheSize(), s.cycle(), s.dataType());
    }

    private static String findMismatchField(SequenceMeta s, SequenceMeta t) {
        if (s.incrementBy() != t.incrementBy()) return "incrementBy";
        if (s.cycle() != t.cycle()) return "cycle";
        if (s.minValue() != t.minValue()) return "minValue";
        if (s.maxValue() != t.maxValue()) return "maxValue";
        if (s.cacheSize() != t.cacheSize()) return "cacheSize";
        if (!eq(s.dataType(), t.dataType())) return "dataType";
        if (s.startValue() != t.startValue()) return "startValue";
        return "unknown";
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
