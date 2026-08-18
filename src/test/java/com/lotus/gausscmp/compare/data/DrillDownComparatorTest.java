package com.lotus.gausscmp.compare.data;

import com.lotus.gausscmp.compare.diff.RowDiff;
import com.lotus.gausscmp.compare.diff.RowDiffType;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DrillDownComparatorTest {

    private final DrillDownComparator cmp = new DrillDownComparator(List.of("id"));

    @Test
    void detectsMissingRowInTarget() {
        Map<String, Map<String, Object>> src = Map.of("1", row(1, "a"));
        Map<String, Map<String, Object>> tgt = Map.of();
        List<RowDiff> diffs = cmp.compare(src, tgt);
        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).type()).isEqualTo(RowDiffType.MISSING_IN_TARGET);
    }

    @Test
    void detectsExtraRowInTarget() {
        Map<String, Map<String, Object>> src = Map.of();
        Map<String, Map<String, Object>> tgt = Map.of("1", row(1, "a"));
        List<RowDiff> diffs = cmp.compare(src, tgt);
        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).type()).isEqualTo(RowDiffType.EXTRA_IN_TARGET);
    }

    @Test
    void detectsMismatchedValues() {
        Map<String, Map<String, Object>> src = Map.of("1", row(1, "a"));
        Map<String, Map<String, Object>> tgt = Map.of("1", row(1, "b"));
        List<RowDiff> diffs = cmp.compare(src, tgt);
        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).type()).isEqualTo(RowDiffType.MISMATCH);
        assertThat(diffs.get(0).mismatchColumns()).contains("name");
    }

    @Test
    void identicalRowsNoDiff() {
        Map<String, Map<String, Object>> src = Map.of("1", row(1, "a"));
        Map<String, Map<String, Object>> tgt = Map.of("1", row(1, "a"));
        assertThat(cmp.compare(src, tgt)).isEmpty();
    }

    private static Map<String, Object> row(int id, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        return m;
    }
}
