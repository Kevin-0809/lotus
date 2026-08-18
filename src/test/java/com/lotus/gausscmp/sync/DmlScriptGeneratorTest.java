package com.lotus.gausscmp.sync;

import com.lotus.gausscmp.compare.diff.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DmlScriptGeneratorTest {

    private final DmlScriptGenerator gen = new DmlScriptGenerator("app", "source-to-target");

    @Test
    void generatesInsertForMissingRow() {
        RowDiff diff = new RowDiff(RowDiffType.MISSING_IN_TARGET,
            Map.of("id", 1), Map.of("id", 1, "name", "a"), null, List.of());
        TableDataDiff tdd = new TableDataDiff("t", List.of("id"), TableDataStatus.DIFFERENT, 1, 0,
            new ChunkStats(1, 0, 1), List.of(diff), null);
        String dml = gen.generate(List.of(tdd));
        assertThat(dml).contains("INSERT INTO \"app\".\"t\"");
        assertThat(dml).contains("'a'");
    }

    @Test
    void generatesDeleteForExtraRow() {
        RowDiff diff = new RowDiff(RowDiffType.EXTRA_IN_TARGET,
            Map.of("id", 2), null, Map.of("id", 2, "name", "b"), List.of());
        TableDataDiff tdd = new TableDataDiff("t", List.of("id"), TableDataStatus.DIFFERENT, 0, 1,
            new ChunkStats(1, 0, 1), List.of(diff), null);
        String dml = gen.generate(List.of(tdd));
        assertThat(dml).contains("DELETE FROM \"app\".\"t\"");
        assertThat(dml).contains("\"id\" = 2");
    }

    @Test
    void generatesUpdateForMismatchRow() {
        RowDiff diff = new RowDiff(RowDiffType.MISMATCH,
            Map.of("id", 3), Map.of("id", 3, "name", "x"), Map.of("id", 3, "name", "y"), List.of("name"));
        TableDataDiff tdd = new TableDataDiff("t", List.of("id"), TableDataStatus.DIFFERENT, 1, 1,
            new ChunkStats(1, 0, 1), List.of(diff), null);
        String dml = gen.generate(List.of(tdd));
        assertThat(dml).contains("UPDATE \"app\".\"t\"");
        assertThat(dml).contains("\"name\" = 'x'");
        assertThat(dml).contains("\"id\" = 3");
    }

    @Test
    void skipsConsistentAndSkippedTables() {
        TableDataDiff ok = new TableDataDiff("ok", List.of("id"), TableDataStatus.CONSISTENT, 5, 5,
            new ChunkStats(1, 1, 0), List.of(), null);
        TableDataDiff skip = new TableDataDiff("skip", List.of(), TableDataStatus.SKIPPED, 0, 0,
            new ChunkStats(0, 0, 0), List.of(), "无主键");
        String dml = gen.generate(List.of(ok, skip));
        assertThat(dml).doesNotContain("INSERT").doesNotContain("UPDATE").doesNotContain("DELETE");
        assertThat(dml).contains("-- 跳过: skip");
    }
}
