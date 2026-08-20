package com.lotus.gausscmp.compare.structure;

import com.lotus.gausscmp.compare.diff.*;
import com.lotus.gausscmp.metadata.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class StructureComparatorTest {

    @Test
    void identicalSnapshotsAreConsistent() {
        ColumnMeta c = new ColumnMeta("id", "integer", false, null, null, 1);
        ConstraintMeta pk = new ConstraintMeta("pk", ConstraintType.PRIMARY, "primary key (id)", List.of("id"), null);
        TableMeta t = new TableMeta("t", null, List.of(c), List.of(pk), List.of(), false);
        SchemaSnapshot s = new SchemaSnapshot("app", List.of(t));
        StructureDiffResult r = new StructureComparator().compare(s, s);
        assertThat(r.tableDiffs()).hasSize(1);
        assertThat(r.tableDiffs().get(0).status()).isEqualTo(TableStructureStatus.CONSISTENT);
    }

    @Test
    void missingTableInTarget() {
        ColumnMeta c = new ColumnMeta("id", "integer", false, null, null, 1);
        TableMeta t = new TableMeta("t", null, List.of(c), List.of(), List.of(), false);
        SchemaSnapshot src = new SchemaSnapshot("app", List.of(t));
        SchemaSnapshot tgt = new SchemaSnapshot("app", List.of());
        StructureDiffResult r = new StructureComparator().compare(src, tgt);
        assertThat(r.tableDiffs().get(0).status()).isEqualTo(TableStructureStatus.DIFFERENT);
        assertThat(r.tableDiffs().get(0).columnDiffs().get(0).type()).isEqualTo(DiffType.TABLE_MISSING_IN_TARGET);
    }

    @Test
    void columnTypeMismatch() {
        ColumnMeta srcCol = new ColumnMeta("id", "integer", false, null, null, 1);
        ColumnMeta tgtCol = new ColumnMeta("id", "bigint", false, null, null, 1);
        TableMeta srcT = new TableMeta("t", null, List.of(srcCol), List.of(), List.of(), false);
        TableMeta tgtT = new TableMeta("t", null, List.of(tgtCol), List.of(), List.of(), false);
        StructureDiffResult r = new StructureComparator().compare(
            new SchemaSnapshot("app", List.of(srcT)), new SchemaSnapshot("app", List.of(tgtT)));
        ColumnDiff cd = r.tableDiffs().get(0).columnDiffs().get(0);
        assertThat(cd.type()).isEqualTo(DiffType.COLUMN_MISMATCH);
        assertThat(cd.field()).isEqualTo("dataType");
        assertThat(cd.sourceValue()).isEqualTo("integer");
        assertThat(cd.targetValue()).isEqualTo("bigint");
    }

    @Test
    void missingConstraintAndIndex() {
        ColumnMeta c = new ColumnMeta("id", "integer", false, null, null, 1);
        ConstraintMeta pk = new ConstraintMeta("pk", ConstraintType.PRIMARY, "primary key (id)", List.of("id"), null);
        IndexMeta idx = new IndexMeta("idx", "t", List.of("id"), false, false, null, "create index idx on t (id)");
        TableMeta srcT = new TableMeta("t", null, List.of(c), List.of(pk), List.of(idx), false);
        TableMeta tgtT = new TableMeta("t", null, List.of(c), List.of(), List.of(), false);
        StructureDiffResult r = new StructureComparator().compare(
            new SchemaSnapshot("app", List.of(srcT)), new SchemaSnapshot("app", List.of(tgtT)));
        List<ConstraintDiff> cds = r.tableDiffs().get(0).constraintDiffs();
        List<IndexDiff> ids = r.tableDiffs().get(0).indexDiffs();
        assertThat(cds).anyMatch(d -> d.type() == DiffType.CONSTRAINT_MISSING_IN_TARGET);
        assertThat(ids).anyMatch(d -> d.type() == DiffType.INDEX_MISSING_IN_TARGET);
    }

    @Test
    void commentMismatchDetected() {
        ColumnMeta c = new ColumnMeta("id", "integer", false, null, null, 1);
        TableMeta srcT = new TableMeta("t", "comment-a", List.of(c), List.of(), List.of(), false);
        TableMeta tgtT = new TableMeta("t", "comment-b", List.of(c), List.of(), List.of(), false);
        StructureDiffResult r = new StructureComparator().compare(
            new SchemaSnapshot("app", List.of(srcT)), new SchemaSnapshot("app", List.of(tgtT)));
        assertThat(r.tableDiffs().get(0).commentDiff()).isPresent();
        assertThat(r.tableDiffs().get(0).status()).isEqualTo(TableStructureStatus.DIFFERENT);
    }

    @Test
    void indexStorageAttributesDoNotCauseMismatch() {
        ColumnMeta c = new ColumnMeta("id", "integer", false, null, null, 1);
        IndexMeta sourceIndex = new IndexMeta("idx", "t", List.of("id"), false, false, null,
            "create index idx on t using ubtree (id) with (storage_type=ustore) tablespace pg_default");
        IndexMeta targetIndex = new IndexMeta("idx", "t", List.of("id"), false, false, null,
            "create index idx on t using ubtree (id) with (active_pages=12) tablespace other");
        TableMeta sourceTable = new TableMeta("t", null, List.of(c), List.of(), List.of(sourceIndex), false);
        TableMeta targetTable = new TableMeta("t", null, List.of(c), List.of(), List.of(targetIndex), false);

        StructureDiffResult result = new StructureComparator().compare(
            new SchemaSnapshot("app", List.of(sourceTable)), new SchemaSnapshot("app", List.of(targetTable)));

        assertThat(result.tableDiffs().get(0).indexDiffs()).isEmpty();
        assertThat(result.tableDiffs().get(0).status()).isEqualTo(TableStructureStatus.CONSISTENT);
    }

    @Test
    void partitionedIndexScopeDifferenceIsDetected() {
        ColumnMeta c = new ColumnMeta("id", "integer", false, null, null, 1);
        IndexMeta sourceIndex = new IndexMeta("idx", "t", List.of("id"), false, false, null,
            "create index idx on t using ubtree (id) global");
        IndexMeta targetIndex = new IndexMeta("idx", "t", List.of("id"), false, false, null,
            "create index idx on t using ubtree (id) local");
        TableMeta sourceTable = new TableMeta("t", null, List.of(c), List.of(), List.of(sourceIndex), true);
        TableMeta targetTable = new TableMeta("t", null, List.of(c), List.of(), List.of(targetIndex), true);

        StructureDiffResult result = new StructureComparator().compare(
            new SchemaSnapshot("app", List.of(sourceTable)), new SchemaSnapshot("app", List.of(targetTable)));

        assertThat(result.tableDiffs().get(0).indexDiffs()).anyMatch(d -> d.type() == DiffType.INDEX_MISMATCH);
    }
}
